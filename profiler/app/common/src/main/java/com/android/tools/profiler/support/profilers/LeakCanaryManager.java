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

    private static final String LEAKCANARY_CLASS_NAME = "leakcanary.AppWatcher";

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

    /** Called from the profiler agent (perfa.cc) via JNI to get the retained visible threshold. */
    @Keep
    @SuppressWarnings("unused") // Called via JNI
    public static int getRetainedVisibleThreshold() {
        Context context = getApplicationContext();
        if (context == null) {
            Log.w(TAG, "Could not get application context to retrieve LeakCanary threshold.");
            return DEFAULT_RETAINED_VISIBLE_THRESHOLD;
        }

        try {
            ClassLoader classLoader = context.getClassLoader();
            Class<?> leakCanaryClass = Class.forName("leakcanary.LeakCanary", false, classLoader);
            java.lang.reflect.Field instanceField = leakCanaryClass.getField("INSTANCE");
            Object leakCanaryInstance = instanceField.get(null);
            Method getConfigMethod = leakCanaryClass.getMethod("getConfig");
            Object config = getConfigMethod.invoke(leakCanaryInstance);
            Method getThresholdMethod = config.getClass().getMethod("getRetainedVisibleThreshold");
            return (int) getThresholdMethod.invoke(config);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "LeakCanary class not found when getting threshold.", e);
        } catch (NoSuchFieldException e) {
            Log.e(TAG, "INSTANCE field not found in LeakCanary class.", e);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Method not found when getting threshold.", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get LeakCanary threshold", e);
        }
        return DEFAULT_RETAINED_VISIBLE_THRESHOLD;
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
                context.registerReceiver(sObjectCountReceiver, filter);
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
