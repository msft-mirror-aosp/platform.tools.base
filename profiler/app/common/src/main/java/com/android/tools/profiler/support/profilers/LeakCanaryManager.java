/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.profiler.support.profilers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Keep;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A helper class that acts as a bridge between the native profiler agent (perfa) and the
 * Studio-LeakCanary library in the app.
 */
@Keep
public class LeakCanaryManager {

    private static final String TAG = "studio.profiler";

    public static final String HEAP_DUMP_COMPLETE_INTENT = "studio.leakcanary.HEAP_DUMP_FINISHED";

    public static final String HEAP_DUMP_COMPLETE_EXTRA = "heap_dump_timestamp";

    public static final String OBJECT_COUNT_UPDATE_INTENT = "studio.leakcanary.OBJECT_COUNT_UPDATE";

    public static final String OBJECT_COUNT_UPDATE_EXTRA = "count";

    public static final String START_LISTENING_INTENT = "studio.leakcanary.START_LISTENING";

    public static final String GET_THRESHOLD_INTENT = "studio.leakcanary.GET_THRESHOLD";

    public static final String THRESHOLD_RESULT_INTENT = "studio.leakcanary.THRESHOLD_RESULT";

    public static final String THRESHOLD_EXTRA = "threshold";

    private static final String LEAKCANARY_CLASS_NAME = "leakcanary.AppWatcher";

    /**
     * Timeout for waiting for the Studio-LeakCanary library to respond to the threshold check
     * broadcast.
     */
    private static final long LEAKCANARY_CHECK_TIMEOUT_SECONDS = 2;

    private static final int DEFAULT_RETAINED_VISIBLE_THRESHOLD = 5;

    private static Context sApplicationContext;

    private static BroadcastReceiver sObjectCountReceiver;

    private static native boolean sendObjectCountNative(int count);

    /** Called from the profiler agent (perfa.cc) via JNI to check for LeakCanary's presence. */
    @Keep
    @SuppressWarnings("unused") // Called via JNI
    public static boolean isPresent() {
        Context context = getApplicationContext();
        if (context == null) {
            Log.d(TAG, "LeakCanary class check: Could not get Application instance.");
            return true;
        }

        try {
            Class.forName(LEAKCANARY_CLASS_NAME, false, context.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            // We are certain that LeakCanary is not present.
            Log.e(TAG, "LeakCanary class check: FAILED. AppWatcher class not found.");
            return false;
        } catch (Exception e) {
            Log.d(TAG, "LeakCanary class check: FAILED with exception.", e);
            return true;
        }
    }

    /**
     * Called from the profiler agent (perfa.cc) via JNI to get the retained visible threshold. This
     * method sends a broadcast to the app's Studio-LeakCanary library and waits for a response.
     *
     * @return The threshold value if retrieved successfully, or 0 if the check failed/timed out.
     */
    @Keep
    @SuppressWarnings("unused") // Called via JNI
    public static int getRetainedVisibleThreshold() {
        Context context = getApplicationContext();
        if (context == null) {
            Log.w(TAG, "Could not get application context to retrieve LeakCanary threshold.");
            return DEFAULT_RETAINED_VISIBLE_THRESHOLD;
        }

        // Wrapper to hold the result from the inner BroadcastReceiver class.
        final AtomicInteger result = new AtomicInteger(0);
        // Synchronization primitive that allows the main thread to wait until the broadcast is
        // received.
        final CountDownLatch latch = new CountDownLatch(1);

        BroadcastReceiver receiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent != null && THRESHOLD_RESULT_INTENT.equals(intent.getAction())) {
                            int threshold = intent.getIntExtra(THRESHOLD_EXTRA, 0);
                            Log.d(TAG, "Received LeakCanary threshold via broadcast: " + threshold);
                            result.set(threshold);
                            // Signal that the result has been received, releasing the waiting
                            // thread.
                            latch.countDown();
                        }
                    }
                };

        try {
            IntentFilter filter = new IntentFilter(THRESHOLD_RESULT_INTENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                String permissionName =
                        context.getPackageName() + ".permission.LEAK_CANARY_INTERNAL";
                context.registerReceiver(receiver, filter, permissionName, null);
            }

            Intent intent = new Intent(GET_THRESHOLD_INTENT);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent GET_THRESHOLD broadcast to check for LeakCanary presence.");

            // Wait for response with timeout
            Log.d(TAG, "Waiting for threshold response...");
            if (!latch.await(LEAKCANARY_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for LeakCanary threshold broadcast response.");
            } else {
                Log.d(TAG, "Successfully received LeakCanary threshold response.");
            }

            context.unregisterReceiver(receiver);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get LeakCanary threshold via broadcast.", e);
        }

        int threshold = result.get();
        if (threshold == 0) {
            Log.w(TAG, "LeakCanary threshold is 0. Returning 0 to indicate failure/absence.");
        }
        return threshold;
    }

    /** Broadcasts the heap dump completion signal to the app. Called via JNI. */
    @Keep
    @SuppressWarnings("unused")
    public static void signalHeapDumpComplete(long heapDumpTimestamp) {
        if (heapDumpTimestamp <= 0) {
            Log.w(TAG, "Invalid heap dump timestamp: " + heapDumpTimestamp);
            return;
        }

        Context context = getApplicationContext();
        if (context == null) {
            Log.w(TAG, "Could not get application context to signal heap dump complete.");
            return;
        }

        try {
            Intent intent = new Intent(HEAP_DUMP_COMPLETE_INTENT);
            intent.putExtra(HEAP_DUMP_COMPLETE_EXTRA, heapDumpTimestamp);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            Log.d(TAG, "HEAP_DUMP_FINISHED broadcast sent successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send HEAP_DUMP_FINISHED broadcast.", e);
        }
    }

    /** Starts listening for retained object count updates from the app. */
    @Keep
    @SuppressWarnings("unused")
    public static void startListeningForRetainedObjects() {
        // This prevents reinitialize of sObjectCountReceiver, if previous stop didn't happen
        if (sObjectCountReceiver != null) {
            Log.d(TAG, "Already listening for retained object count updates.");
            return;
        }

        sObjectCountReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (intent != null
                                && OBJECT_COUNT_UPDATE_INTENT.equals(intent.getAction())) {
                            int count = intent.getIntExtra(OBJECT_COUNT_UPDATE_EXTRA, -1);
                            Log.d(TAG, "Received retained object count: " + count);

                            boolean success = sendObjectCountNative(count);
                            if (!success) {
                                Log.e(TAG, "Failed to send count to agent. Stopping listener.");
                                stopListeningForRetainedObjects();
                            }
                        }
                    }
                };

        try {
            Context context = getApplicationContext();
            if (context == null) {
                stopListeningForRetainedObjects();
                return;
            }
            IntentFilter filter = new IntentFilter(OBJECT_COUNT_UPDATE_INTENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        sObjectCountReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                String permissionName =
                        context.getPackageName() + ".permission.LEAK_CANARY_INTERNAL";
                context.registerReceiver(sObjectCountReceiver, filter, permissionName, null);
            }
            Log.d(TAG, "Started listening for retained object count updates.");

            // Notify the library that Studio is listening, so it can send the current count
            // immediately.
            Intent intent = new Intent(START_LISTENING_INTENT);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent START_LISTENING broadcast.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receiver for object count updates.", e);
            stopListeningForRetainedObjects();
        }
    }

    /** Stops listening for retained object count updates. */
    @Keep
    @SuppressWarnings("unused")
    public static void stopListeningForRetainedObjects() {
        if (sObjectCountReceiver == null) {
            Log.d(TAG, "Not listening for retained object count updates, nothing to stop.");
            return;
        }

        Context context = getApplicationContext();
        if (context != null) {
            try {
                context.unregisterReceiver(sObjectCountReceiver);
                Log.d(TAG, "Stopped listening for retained object count updates.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister receiver.", e);
            }
        }
        sObjectCountReceiver = null;
    }

    private static synchronized Context getApplicationContext() {
        if (sApplicationContext == null) {
            try {
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Method currentApplicationMethod =
                        activityThreadClass.getMethod("currentApplication");
                sApplicationContext = (Context) currentApplicationMethod.invoke(null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get application context.", e);
            }
        }
        if (sApplicationContext == null) {
            Log.w(TAG, "Application context is null.");
        }
        return sApplicationContext;
    }
}
