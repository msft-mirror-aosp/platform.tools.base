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
import android.os.StrictMode;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.VisibleForTesting;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    public static final String LEAKCANARY_MODE_EXTRA = "leakcanary_mode";

    public static final String GET_THRESHOLD_INTENT = "studio.leakcanary.GET_THRESHOLD";

    public static final String THRESHOLD_RESULT_INTENT = "studio.leakcanary.THRESHOLD_RESULT";

    public static final String THRESHOLD_EXTRA = "threshold";

    public static final String STUDIO_LEAKCANARY_VERSION_EXTRA = "studio_leakcanary_version";

    public static final String FORCE_DUMP_ON_DEVICE_INTENT =
            "studio.leakcanary.FORCE_DUMP_ON_DEVICE";

    public static final String ANALYSIS_PROGRESS_INTENT = "studio.leakcanary.ANALYSIS_PROGRESS";

    public static final String ANALYSIS_PROGRESS_EXTRA = "percent";

    public static final String ANALYSIS_RESULT_INTENT = "studio.leakcanary.ANALYSIS_RESULT";

    public static final String ANALYSIS_RESULT_FILE_PATH_EXTRA = "file_path";

    public static final String DEVICE_ERROR_INTENT = "studio.leakcanary.DEVICE_ERROR";

    public static final String DEVICE_ERROR_CODE_EXTRA = "error_code";

    private static final int MODE_ON_DEVICE = 0;

    /**
     * Timeout for waiting for the Studio-LeakCanary library to respond to the threshold check
     * broadcast.
     */
    private static long LEAKCANARY_CHECK_TIMEOUT_SECONDS = 3;

    private static final int DEFAULT_RETAINED_VISIBLE_THRESHOLD = 5;

    private static Context sApplicationContext;

    /**
     * The studio-leakcanary version from the last {@link #getRetainedVisibleThreshold()} call.
     * Empty if the app did not send it.
     */
    private static String sStudioLeakCanaryVersion = "";

    // Error constants mapping to LeakCanaryDeviceError in common.proto
    // These must be kept strictly synced with LeakCanaryDeviceError.ErrorType enum
    // in tools/base/transport/proto/common.proto because they are natively casted in perfa.cc.
    private static final int LEAKCANARY_ERROR_APP_CONTEXT_NULL = 1;
    private static final int LEAKCANARY_ERROR_BROADCAST_DELIVERY_FAILED = 2;
    private static final int LEAKCANARY_ERROR_UNSUPPORTED_VERSION = 4;
    private static final int LEAKCANARY_ERROR_ON_DEVICE_HEAP_DUMP_FAILED = 5;
    private static final int LEAKCANARY_ERROR_ON_DEVICE_SAVE_FAILED = 6;
    private static final int LEAKCANARY_ERROR_ON_DEVICE_RESULT_READ_FAILED = 7;

    /**
     * The largest analysis result perfa sends. Messages from perfa to the daemon can be at most 4
     * MB, the gRPC default, so a bigger result would be lost.
     */
    private static final long MAX_ANALYSIS_RESULT_BYTES = 3 * 1024 * 1024;

    private static BroadcastReceiver sObjectCountReceiver;

    private static BroadcastReceiver sAnalysisProgressReceiver;

    private static BroadcastReceiver sAnalysisResultReceiver;

    private static BroadcastReceiver sDeviceErrorReceiver;

    private static native boolean sendObjectCountNative(int count);

    private static native boolean sendAnalysisProgressNative(int percent);

    private static native boolean sendAnalysisResultNative(byte[] data);

    private static native void sendErrorNative(int errorCode);

    /**
     * Called from the profiler agent (perfa.cc) via JNI to get the retained visible threshold. This
     * method sends a broadcast to the app's Studio-LeakCanary library and waits for a response.
     *
     * <p>It also saves the library version sent with the threshold. Read it with {@link
     * #getStudioLeakCanaryVersion()} after this call.
     *
     * @return The threshold value if retrieved successfully, or 0 if the check failed/timed out.
     */
    @Keep
    @SuppressWarnings("unused") // Called via JNI
    public static int getRetainedVisibleThreshold() {
        sStudioLeakCanaryVersion = "";
        Context context = getApplicationContext();
        if (context == null) {
            Log.w(TAG, "Could not get application context to retrieve LeakCanary threshold.");
            return DEFAULT_RETAINED_VISIBLE_THRESHOLD;
        }

        // Wrapper to hold the result from the inner BroadcastReceiver class.
        final AtomicInteger result = new AtomicInteger(0);
        final AtomicReference<String> version = new AtomicReference<>("");
        // Synchronization primitive that allows the main thread to wait until the broadcast is
        // received.
        final CountDownLatch latch = new CountDownLatch(1);

        BroadcastReceiver receiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent != null && THRESHOLD_RESULT_INTENT.equals(intent.getAction())) {
                            int threshold = intent.getIntExtra(THRESHOLD_EXTRA, 0);
                            // An old library doesn't send the version.
                            String libraryVersion =
                                    intent.getStringExtra(STUDIO_LEAKCANARY_VERSION_EXTRA);
                            if (libraryVersion == null) {
                                libraryVersion = "";
                            }
                            Log.d(
                                    TAG,
                                    "Received LeakCanary threshold via broadcast: "
                                            + threshold
                                            + ", library version: "
                                            + libraryVersion);
                            result.set(threshold);
                            version.set(libraryVersion);
                            // Signal that the result has been received, releasing the waiting
                            // thread.
                            latch.countDown();
                        }
                    }
                };

        try {
            registerInternalReceiver(context, receiver, THRESHOLD_RESULT_INTENT);

            Intent intent = new Intent(GET_THRESHOLD_INTENT);
            sendBroadcastToApp(context, intent);
            Log.d(TAG, "Sent GET_THRESHOLD broadcast to check for LeakCanary presence.");

            // Wait for response with timeout
            Log.d(TAG, "Waiting for threshold response...");
            if (!latch.await(LEAKCANARY_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for LeakCanary threshold broadcast response.");
            } else {
                Log.d(TAG, "Successfully received LeakCanary threshold response.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get LeakCanary threshold via broadcast.", e);
            sendErrorNative(LEAKCANARY_ERROR_BROADCAST_DELIVERY_FAILED);
        } finally {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister threshold receiver.", e);
            }
        }

        int threshold = result.get();
        if (threshold == 0) {
            Log.w(TAG, "LeakCanary threshold is 0. Returning 0 to indicate failure/absence.");
        }
        sStudioLeakCanaryVersion = version.get();
        return threshold;
    }

    /**
     * Called from the profiler agent (perfa.cc) via JNI, right after {@link
     * #getRetainedVisibleThreshold()}.
     *
     * @return The studio-leakcanary version the app sent with the threshold, or an empty string if
     *     it sent none.
     */
    @Keep
    @SuppressWarnings("unused") // Called via JNI
    public static String getStudioLeakCanaryVersion() {
        return sStudioLeakCanaryVersion;
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
            sendBroadcastToApp(context, intent);
            Log.d(TAG, "HEAP_DUMP_FINISHED broadcast sent successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send HEAP_DUMP_FINISHED broadcast.", e);
            sendErrorNative(LEAKCANARY_ERROR_BROADCAST_DELIVERY_FAILED);
        }
    }

    /**
     * Same as {@link #startListeningForRetainedObjects(int, boolean)} with the occurrences flag
     * off, which is its default value. Used by tests.
     *
     * @param mode The selected LeakCanary tracking mode (0 = ON_DEVICE, 1 = ON_HOST).
     */
    @VisibleForTesting
    static void startListeningForRetainedObjects(int mode) {
        startListeningForRetainedObjects(mode, false);
    }

    /**
     * Starts listening for updates from the app.
     *
     * <p>ON_HOST receives the retained object count, with the flag on or off. ON_DEVICE receives
     * the count, the analysis progress, the analysis result and device errors only when the
     * occurrences flag is on. When it is off, ON_DEVICE receives nothing.
     *
     * <p>All needed receivers must be registered. If one fails, all are removed and the app is not
     * told to start.
     *
     * @param mode The selected LeakCanary tracking mode (0 = ON_DEVICE, 1 = ON_HOST).
     * @param occurrencesEnabled The value of Studio's LeakCanary occurrences flag.
     */
    @Keep
    @SuppressWarnings("unused")
    public static void startListeningForRetainedObjects(int mode, boolean occurrencesEnabled) {
        Log.d(
                TAG,
                "startListeningForRetainedObjects called with mode: "
                        + mode
                        + ", occurrences enabled: "
                        + occurrencesEnabled);
        Context context = getApplicationContext();
        if (context == null) {
            Log.w(TAG, "Cannot start listening: context is null.");
            return;
        }

        boolean onDevice = mode == MODE_ON_DEVICE;
        boolean listenForCount = !onDevice || occurrencesEnabled;
        boolean listenForOnDeviceUpdates = onDevice && occurrencesEnabled;

        // Remove receivers left from an earlier start that are not needed now.
        if (!listenForCount) {
            stopListeningForObjectCount();
        }
        if (!listenForOnDeviceUpdates) {
            stopListeningForAnalysisProgress();
            stopListeningForAnalysisResult();
            stopListeningForDeviceErrors();
        }

        boolean registered = true;
        if (listenForCount) {
            registered = registerObjectCountReceiver(context);
        }
        if (registered && listenForOnDeviceUpdates) {
            registered =
                    registerAnalysisProgressReceiver(context)
                            && registerAnalysisResultReceiver(context)
                            && registerDeviceErrorReceiver(context);
        }
        if (!registered) {
            Log.e(TAG, "Cannot start listening: a receiver could not be registered.");
            stopListeningForRetainedObjects();
            return;
        }

        sendStartListeningBroadcast(context, mode);
    }

    /**
     * Registers a BroadcastReceiver to listen for retained object count updates from the app.
     *
     * @param context The application context.
     * @return true if the receiver was successfully registered (or already registered), false
     *     otherwise.
     */
    private static boolean registerObjectCountReceiver(Context context) {
        if (sObjectCountReceiver != null) {
            Log.d(TAG, "Already listening for retained object count updates.");
            return true;
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
                                Log.w(
                                        TAG,
                                        "Failed to send count to agent. Dropping current count.");
                            }
                        }
                    }
                };

        try {
            registerInternalReceiver(context, sObjectCountReceiver, OBJECT_COUNT_UPDATE_INTENT);
            Log.d(TAG, "Started listening for retained object count updates.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receiver for object count updates.", e);
            stopListeningForObjectCount();
            return false;
        }
    }

    /**
     * Registers a BroadcastReceiver to listen for analysis progress updates from the app.
     *
     * @param context The application context.
     * @return true if the receiver was successfully registered (or already registered), false
     *     otherwise.
     */
    private static boolean registerAnalysisProgressReceiver(Context context) {
        if (sAnalysisProgressReceiver != null) {
            Log.d(TAG, "Already listening for analysis progress updates.");
            return true;
        }

        sAnalysisProgressReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (intent != null && ANALYSIS_PROGRESS_INTENT.equals(intent.getAction())) {
                            int percent = intent.getIntExtra(ANALYSIS_PROGRESS_EXTRA, -1);
                            Log.d(TAG, "Received analysis progress: " + percent);

                            boolean success = sendAnalysisProgressNative(percent);
                            if (!success) {
                                Log.w(
                                        TAG,
                                        "Failed to send analysis progress to agent. Dropping it.");
                            }
                        }
                    }
                };

        try {
            registerInternalReceiver(context, sAnalysisProgressReceiver, ANALYSIS_PROGRESS_INTENT);
            Log.d(TAG, "Started listening for analysis progress updates.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receiver for analysis progress updates.", e);
            stopListeningForAnalysisProgress();
            return false;
        }
    }

    /**
     * Registers a BroadcastReceiver to listen for the analysis result from the app.
     *
     * @param context The application context.
     * @return true if the receiver was successfully registered (or already registered), false
     *     otherwise.
     */
    private static boolean registerAnalysisResultReceiver(Context context) {
        if (sAnalysisResultReceiver != null) {
            Log.d(TAG, "Already listening for the analysis result.");
            return true;
        }

        sAnalysisResultReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (intent != null && ANALYSIS_RESULT_INTENT.equals(intent.getAction())) {
                            String path = intent.getStringExtra(ANALYSIS_RESULT_FILE_PATH_EXTRA);
                            Log.d(TAG, "Received analysis result: " + path);
                            // When the result can't be read or sent, tell Studio, so it
                            // doesn't wait for it.
                            if (path == null) {
                                Log.w(TAG, "The analysis result has no file path. Dropping it.");
                                sendErrorNative(LEAKCANARY_ERROR_ON_DEVICE_RESULT_READ_FAILED);
                                return;
                            }

                            byte[] data = readAndDeleteFile(new File(path));
                            if (data == null) {
                                sendErrorNative(LEAKCANARY_ERROR_ON_DEVICE_RESULT_READ_FAILED);
                                return;
                            }
                            if (data.length == 0) {
                                Log.w(TAG, "The analysis result file is empty. Dropping it.");
                                sendErrorNative(LEAKCANARY_ERROR_ON_DEVICE_RESULT_READ_FAILED);
                                return;
                            }

                            boolean success = sendAnalysisResultNative(data);
                            if (!success) {
                                Log.w(TAG, "Failed to send analysis result to agent. Dropping it.");
                                sendErrorNative(LEAKCANARY_ERROR_ON_DEVICE_RESULT_READ_FAILED);
                            }
                        }
                    }
                };

        try {
            registerInternalReceiver(context, sAnalysisResultReceiver, ANALYSIS_RESULT_INTENT);
            Log.d(TAG, "Started listening for the analysis result.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receiver for the analysis result.", e);
            stopListeningForAnalysisResult();
            return false;
        }
    }

    /**
     * Registers a BroadcastReceiver to listen for errors from the app. Only known error codes are
     * sent to Studio.
     *
     * @param context The application context.
     * @return true if the receiver was successfully registered (or already registered), false
     *     otherwise.
     */
    private static boolean registerDeviceErrorReceiver(Context context) {
        if (sDeviceErrorReceiver != null) {
            Log.d(TAG, "Already listening for device errors.");
            return true;
        }

        sDeviceErrorReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (intent != null && DEVICE_ERROR_INTENT.equals(intent.getAction())) {
                            int errorCode = intent.getIntExtra(DEVICE_ERROR_CODE_EXTRA, -1);
                            Log.d(TAG, "Received device error: " + errorCode);
                            if (!isKnownDeviceError(errorCode)) {
                                Log.w(TAG, "Dropping unknown device error: " + errorCode);
                                return;
                            }
                            sendErrorNative(errorCode);
                        }
                    }
                };

        try {
            registerInternalReceiver(context, sDeviceErrorReceiver, DEVICE_ERROR_INTENT);
            Log.d(TAG, "Started listening for device errors.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receiver for device errors.", e);
            stopListeningForDeviceErrors();
            return false;
        }
    }

    /** Returns true for the error codes the app can send. */
    private static boolean isKnownDeviceError(int errorCode) {
        return errorCode == LEAKCANARY_ERROR_UNSUPPORTED_VERSION
                || errorCode == LEAKCANARY_ERROR_ON_DEVICE_HEAP_DUMP_FAILED
                || errorCode == LEAKCANARY_ERROR_ON_DEVICE_SAVE_FAILED;
    }

    /**
     * Reads the whole file, then always deletes it, so an old result is never read twice.
     *
     * <p>This runs on the main thread. The file is small and was just written, so this is quick.
     * Disk access is allowed for this call only, so an app with StrictMode turned on doesn't report
     * or crash.
     *
     * @return the file content, or null if the file is missing, can't be read, or is larger than
     *     {@link #MAX_ANALYSIS_RESULT_BYTES}.
     */
    private static byte[] readAndDeleteFile(File file) {
        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try (FileInputStream fileStream = new FileInputStream(file);
                DataInputStream in = new DataInputStream(fileStream)) {
            long size = fileStream.getChannel().size();
            if (size > MAX_ANALYSIS_RESULT_BYTES) {
                Log.w(
                        TAG,
                        "Analysis result file is too large to send (" + size + " bytes): " + file);
                return null;
            }
            byte[] data = new byte[(int) size];
            in.readFully(data);
            return data;
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Analysis result file not found or can't be opened: " + file);
            return null;
        } catch (IOException e) {
            Log.e(TAG, "Failed to read analysis result file: " + file, e);
            return null;
        } finally {
            if (!file.delete() && file.exists()) {
                Log.w(TAG, "Failed to delete analysis result file: " + file);
            }
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }

    /**
     * Broadcasts the START_LISTENING intent to the app to notify the LeakCanary library of the
     * tracking mode. This is done after the needed receivers are set up, so the count receiver (if
     * used) catches the immediate count response.
     *
     * @param context The application context.
     * @param mode The selected LeakCanary tracking mode (0 = ON_DEVICE, 1 = ON_HOST).
     */
    private static void sendStartListeningBroadcast(Context context, int mode) {
        try {
            Intent intent = new Intent(START_LISTENING_INTENT);
            intent.putExtra(LEAKCANARY_MODE_EXTRA, mode);
            sendBroadcastToApp(context, intent);
            Log.d(TAG, "Sent START_LISTENING broadcast with mode: " + mode);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send START_LISTENING broadcast.", e);
            sendErrorNative(LEAKCANARY_ERROR_BROADCAST_DELIVERY_FAILED);
        }
    }

    /**
     * Broadcasts the force dump signal to the app. Called from the profiler agent (perfa.cc) via
     * JNI. This signals the ForceDumpReceiver to trigger LeakCanary.dumpHeap() on the device.
     */
    @Keep
    @SuppressWarnings("unused")
    public static void triggerOnDeviceDump() {
        Context context = getApplicationContext();
        if (context == null) {
            Log.w(TAG, "Could not get application context to trigger on-device dump.");
            return;
        }

        try {
            Intent intent = new Intent(FORCE_DUMP_ON_DEVICE_INTENT);
            sendBroadcastToApp(context, intent);
            Log.d(TAG, "FORCE_DUMP_ON_DEVICE broadcast sent successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send FORCE_DUMP_ON_DEVICE broadcast.", e);
            sendErrorNative(LEAKCANARY_ERROR_BROADCAST_DELIVERY_FAILED);
        }
    }

    /** Stops listening for all updates from the app, in any mode. */
    @Keep
    @SuppressWarnings("unused")
    public static void stopListeningForRetainedObjects() {
        Log.d(TAG, "stopListeningForRetainedObjects called.");
        stopListeningForObjectCount();
        stopListeningForAnalysisProgress();
        stopListeningForAnalysisResult();
        stopListeningForDeviceErrors();
    }

    /** Stops listening for retained object count updates. */
    private static void stopListeningForObjectCount() {
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
                Log.e(TAG, "Failed to unregister object count receiver.", e);
            }
        }
        sObjectCountReceiver = null;
    }

    /** Stops listening for analysis progress updates. */
    private static void stopListeningForAnalysisProgress() {
        if (sAnalysisProgressReceiver == null) {
            Log.d(TAG, "Not listening for analysis progress updates, nothing to stop.");
            return;
        }

        Context context = getApplicationContext();
        if (context != null) {
            try {
                context.unregisterReceiver(sAnalysisProgressReceiver);
                Log.d(TAG, "Stopped listening for analysis progress updates.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister analysis progress receiver.", e);
            }
        }
        sAnalysisProgressReceiver = null;
    }

    /** Stops listening for the analysis result. */
    private static void stopListeningForAnalysisResult() {
        if (sAnalysisResultReceiver == null) {
            Log.d(TAG, "Not listening for the analysis result, nothing to stop.");
            return;
        }

        Context context = getApplicationContext();
        if (context != null) {
            try {
                context.unregisterReceiver(sAnalysisResultReceiver);
                Log.d(TAG, "Stopped listening for the analysis result.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister analysis result receiver.", e);
            }
        }
        sAnalysisResultReceiver = null;
    }

    /** Stops listening for device errors. */
    private static void stopListeningForDeviceErrors() {
        if (sDeviceErrorReceiver == null) {
            Log.d(TAG, "Not listening for device errors, nothing to stop.");
            return;
        }

        Context context = getApplicationContext();
        if (context != null) {
            try {
                context.unregisterReceiver(sDeviceErrorReceiver);
                Log.d(TAG, "Stopped listening for device errors.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister device error receiver.", e);
            }
        }
        sDeviceErrorReceiver = null;
    }

    private static void registerInternalReceiver(
            Context context, BroadcastReceiver receiver, String action) {
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            String permissionName = context.getPackageName() + ".permission.LEAK_CANARY_INTERNAL";
            context.registerReceiver(receiver, filter, permissionName, null);
        }
    }

    private static void sendBroadcastToApp(Context context, Intent intent) {
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
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
                sendErrorNative(LEAKCANARY_ERROR_APP_CONTEXT_NULL);
            }
        }
        if (sApplicationContext == null) {
            Log.w(TAG, "Application context is null.");
        }
        return sApplicationContext;
    }
}
