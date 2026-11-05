/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tools.studio.leakcanary;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Listens to LeakCanary's events and sends the heap analysis progress, the analysis result and
 * device errors to Android Studio.
 *
 * <p>Used in ON_DEVICE only, where LeakCanary dumps and analyzes the heap on the device. It is
 * added to LeakCanary's Config.eventListeners next to LeakCanary's own listeners. LeakCanary calls
 * it on its own background threads, after its own listeners.
 *
 * <p>NOTE: Like {@link StudioLeakCanaryListener}, this uses a dynamic proxy, so there is no
 * compile-time dependency on LeakCanary.
 */
final class StudioLeakCanaryEventListener {

    /** Lets only one result at a time write the result file and send its broadcast. */
    private static final Object RESULT_FILE_LOCK = new Object();

    private final Application application;

    private StudioLeakCanaryEventListener(Application application) {
        this.application = application;
    }

    /**
     * Creates a proxy of leakcanary.EventListener that calls a new StudioLeakCanaryEventListener.
     * Returns null if LeakCanary is missing or the proxy can't be made.
     */
    static Object createInstance(Application application) {
        try {
            Class<?> listenerInterface = Class.forName(HelperConfig.EVENT_LISTENER_CLASS);
            final StudioLeakCanaryEventListener listener =
                    new StudioLeakCanaryEventListener(application);

            return Proxy.newProxyInstance(
                    listenerInterface.getClassLoader(),
                    new Class<?>[] {listenerInterface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if (HelperConfig.ON_EVENT_METHOD.equals(name)) {
                                listener.onEvent(args != null && args.length > 0 ? args[0] : null);
                                return null;
                            }
                            // Object methods, needed when the proxy is in a list or logged.
                            if (HelperConfig.TO_STRING_METHOD.equals(name)) {
                                return "StudioLeakCanaryEventListenerProxy";
                            }
                            if (HelperConfig.HASH_CODE_METHOD.equals(name)) {
                                return listener.hashCode();
                            }
                            if (HelperConfig.EQUALS_METHOD.equals(name)) {
                                return args[0] != null
                                        && Proxy.isProxyClass(args[0].getClass())
                                        && Proxy.getInvocationHandler(args[0]) == this;
                            }
                            Log.w(
                                    HelperConfig.LOG_TAG,
                                    "Unhandled method call on StudioLeakCanaryEventListener"
                                            + " proxy: "
                                            + name);
                            return StudioLeakCanaryListener.getDefaultValue(method.getReturnType());
                        }
                    });
        } catch (ClassNotFoundException e) {
            Log.i(HelperConfig.LOG_TAG, "LeakCanary EventListener not found. Progress not sent.");
            return null;
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to create StudioLeakCanaryEventListener proxy.", t);
            return null;
        }
    }

    /** Called by LeakCanary on its own threads. Must never throw, so the app can't crash. */
    private void onEvent(Object event) {
        try {
            if (StudioLeakCanaryManager.getInstance().getCurrentMode()
                    != StudioLeakCanaryManager.MODE_ON_DEVICE) {
                return;
            }
            int percent = LeakCanaryReflectionHelper.getHeapAnalysisProgressPercent(event);
            if (percent != HelperConfig.NO_ANALYSIS_PROGRESS) {
                sendProgress(percent);
            } else if (LeakCanaryReflectionHelper.isHeapAnalysisDone(event)) {
                sendAnalysisResult(event);
            } else if (LeakCanaryReflectionHelper.isHeapDumpFailed(event)) {
                onHeapDumpFailed(event);
            }
            // Other events send nothing.
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to handle LeakCanary event.", t);
        }
    }

    private void sendProgress(int percent) {
        Intent intent = new Intent(HelperConfig.ANALYSIS_PROGRESS_INTENT);
        intent.putExtra(HelperConfig.ANALYSIS_PROGRESS_EXTRA, percent);
        intent.setPackage(application.getPackageName()); // Security: Keep within app
        application.sendBroadcast(intent);
        Log.d(HelperConfig.LOG_TAG, "Sent analysis progress broadcast with value: " + percent);
    }

    /**
     * Converts the finished analysis to our proto, saves it to a file in the app's cache folder and
     * sends the file path. The result can be too big for a broadcast (about 1 MB limit), so only
     * the path is sent. If converting fails, sends the unsupported version error instead. If saving
     * fails, sends the save failed error instead. In all cases, then sends the retained count at
     * that moment. It only counts objects LeakCanary started watching after the heap dump began:
     * the older ones are in the dump, and LeakCanary stops watching them when the dump ends.
     *
     * <p>One result is handled at a time: with WorkManager, LeakCanary can run two analyses at the
     * same time on different threads, and both would write the same file. The lock is held only
     * while one result is converted, saved and sent (usually well under 100 ms), on LeakCanary's
     * background thread, never on the main thread.
     */
    private void sendAnalysisResult(Object event) {
        synchronized (RESULT_FILE_LOCK) {
            try {
                LeakCanaryAnalysisData result;
                try {
                    result =
                            SharkResultConverter.convert(
                                    LeakCanaryReflectionHelper.getHeapAnalysis(event));
                } catch (Throwable t) {
                    Log.e(
                            HelperConfig.LOG_TAG,
                            "Failed to convert the LeakCanary analysis result.",
                            t);
                    sendResultError(HelperConfig.ERROR_CODE_UNSUPPORTED_VERSION);
                    return;
                }
                File file;
                try {
                    file = writeToCacheFile(result);
                } catch (Throwable t) {
                    Log.e(
                            HelperConfig.LOG_TAG,
                            "Failed to save the LeakCanary analysis result.",
                            t);
                    sendResultError(HelperConfig.ERROR_CODE_ON_DEVICE_SAVE_FAILED);
                    return;
                }
                Intent intent = new Intent(HelperConfig.ANALYSIS_RESULT_INTENT);
                intent.putExtra(
                        HelperConfig.ANALYSIS_RESULT_FILE_PATH_EXTRA, file.getAbsolutePath());
                intent.setPackage(application.getPackageName()); // Security: Keep within app
                application.sendBroadcast(intent);
                Log.d(
                        HelperConfig.LOG_TAG,
                        "Sent analysis result broadcast: " + file.getAbsolutePath());
            } finally {
                StudioLeakCanaryListener.sendCurrentCount();
            }
        }
    }

    /**
     * Sends an error instead of the result. Deletes the old result, as Studio was not told of it.
     */
    private void sendResultError(int errorCode) {
        deleteCacheFile();
        sendDeviceError(errorCode);
    }

    /**
     * Writes the result to the same file every time, so the cache folder never holds more than one.
     * It writes a temp file first and then renames it, so a reader never sees a half-written file:
     * a reader that already opened the old file keeps reading it, whole.
     *
     * <p>If a second result is saved before the first one is read, only the second one can be read.
     * This needs two analyses ending within a few milliseconds of each other, which is very rare.
     */
    private File writeToCacheFile(LeakCanaryAnalysisData result) throws IOException {
        File cacheDir = application.getCacheDir();
        if (cacheDir == null) {
            throw new IOException("The app has no cache folder.");
        }
        File file = new File(cacheDir, HelperConfig.ON_DEVICE_ANALYSIS_FILE_NAME);
        File tempFile = new File(cacheDir, HelperConfig.ON_DEVICE_ANALYSIS_FILE_NAME + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                result.writeTo(out);
            }
            if (!tempFile.renameTo(file)) {
                // Android replaces the old file. Some other systems (like Windows, for host
                // tests) can't, so delete it and try once more.
                file.delete();
                if (!tempFile.renameTo(file)) {
                    throw new IOException("Could not rename " + tempFile + " to " + file);
                }
            }
        } catch (Throwable e) {
            tempFile.delete();
            throw e;
        }
        return file;
    }

    /** Deletes the result file, if there is one. */
    private void deleteCacheFile() {
        File cacheDir = application.getCacheDir();
        if (cacheDir != null) {
            new File(cacheDir, HelperConfig.ON_DEVICE_ANALYSIS_FILE_NAME).delete();
        }
    }

    /**
     * Sends the heap dump failed error, so Studio knows right away that no result will come. If
     * LeakCanary will try again by itself (a dump it started on its own), nothing is sent: the next
     * try may work.
     */
    private void onHeapDumpFailed(Object event) throws ReflectiveOperationException {
        boolean willRetryLater = LeakCanaryReflectionHelper.heapDumpWillRetryLater(event);
        Throwable cause = heapDumpFailedCause(event);
        if (willRetryLater) {
            Log.w(HelperConfig.LOG_TAG, "LeakCanary heap dump failed. It will try again.", cause);
            return;
        }
        Log.w(HelperConfig.LOG_TAG, "LeakCanary heap dump failed. Sending the error.", cause);
        sendDeviceError(HelperConfig.ERROR_CODE_ON_DEVICE_HEAP_DUMP_FAILED);
    }

    /** Returns why the dump failed, only for the log. Returns null if it can't be read. */
    private static Throwable heapDumpFailedCause(Object event) {
        try {
            return LeakCanaryReflectionHelper.getHeapDumpFailedException(event);
        } catch (Throwable t) {
            return null;
        }
    }

    private void sendDeviceError(int errorCode) {
        Intent intent = new Intent(HelperConfig.DEVICE_ERROR_INTENT);
        intent.putExtra(HelperConfig.DEVICE_ERROR_CODE_EXTRA, errorCode);
        intent.setPackage(application.getPackageName()); // Security: Keep within app
        application.sendBroadcast(intent);
        Log.d(HelperConfig.LOG_TAG, "Sent device error broadcast with code: " + errorCode);
    }
}
