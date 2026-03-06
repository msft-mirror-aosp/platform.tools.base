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

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Helper class to centralize and cache all reflection calls to LeakCanary internals.
 *
 * <p>This avoids duplicating reflection logic across different receivers and listeners, and
 * improves performance by caching Class, Method, and Field lookups.
 */
public class LeakCanaryReflectionHelper {

    private static volatile boolean initialized = false;

    // Cached LeakCanary Config Methods/Fields
    private static Method getConfigMethod;
    private static Field dumpHeapField;
    private static Object leakCanaryInstance;

    // Cached AppWatcher / ObjectWatcher Methods
    private static Object objectWatcherInstance;
    private static Method getRetainedObjectCountMethod;
    private static Method clearObjectsMethod;
    private static Method removeListenerMethod;
    private static Method addListenerMethod;

    // Cached internal listener instance
    private static Object internalLcInstance;

    // Cached GC Trigger Methods
    private static Object gcTriggerInstance;
    private static Method runGcMethod;

    // Cached Dump Heap Method
    private static Method dumpHeapMethod;

    private LeakCanaryReflectionHelper() {}

    /**
     * Initializes the reflection cache. Safe to call multiple times (idempotent).
     *
     * <p>Uses the Double-Checked Locking pattern. This method can be called from multiple threads
     * (e.g., Main Thread during broadcast receives, and the Background HandlerThread during leak
     * checks). The first check avoids the expensive synchronization overhead for the 99% of calls
     * that happen after initialization is already complete. The second check inside the
     * synchronized block ensures that if two threads race to initialize, only the first one
     * actually performs the reflection.
     */
    public static void ensureInitialized() {
        // 1st Check (Fast Path): If already initialized, return immediately without locking.
        if (initialized) return;

        synchronized (LeakCanaryReflectionHelper.class) {
            // 2nd Check (Safe Path): If another thread initialized it while we were waiting for the
            // lock, return.
            if (initialized) return;

            // 1. Initialize LeakCanary Configuration cache and Dump Heap Method
            try {
                Class<?> leakCanaryClass = Class.forName(HelperConfig.LEAK_CANARY_CLASS);
                Field leakCanaryInstanceField =
                        leakCanaryClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                leakCanaryInstance = leakCanaryInstanceField.get(null);
                getConfigMethod = leakCanaryClass.getDeclaredMethod(HelperConfig.GET_CONFIG_METHOD);
                dumpHeapMethod = leakCanaryClass.getMethod(HelperConfig.DUMP_HEAP_METHOD);
            } catch (Throwable t) {
                Log.w(
                        HelperConfig.LOG_TAG,
                        "Failed to initialize LeakCanary configuration/dumpHeap reflection.");
            }

            // 2. Initialize internal listener
            try {
                Class<?> internalLcClass = Class.forName(HelperConfig.LEAK_CANARY_INTERNAL_CLASS);
                Field internalInstanceField =
                        internalLcClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                internalInstanceField.setAccessible(true);
                internalLcInstance = internalInstanceField.get(null);
            } catch (Throwable t) {
                Log.w(HelperConfig.LOG_TAG, "Failed to initialize internal listener reflection.");
            }

            // 3. Initialize AppWatcher and ObjectWatcher cache
            try {
                Class<?> appWatcherClass = Class.forName(HelperConfig.APP_WATCHER_CLASS);
                Field appWatcherInstanceField =
                        appWatcherClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                Object appWatcherInstance = appWatcherInstanceField.get(null);

                Method getObjectWatcherMethod =
                        appWatcherClass.getMethod(HelperConfig.GET_OBJECT_WATCHER_METHOD);
                objectWatcherInstance = getObjectWatcherMethod.invoke(appWatcherInstance);

                Class<?> objectWatcherClass = objectWatcherInstance.getClass();
                getRetainedObjectCountMethod =
                        objectWatcherClass.getMethod(HelperConfig.GET_RETAINED_OBJECT_COUNT_METHOD);
                clearObjectsMethod =
                        objectWatcherClass.getMethod(
                                HelperConfig.CLEAR_OBJECTS_WATCHED_BEFORE_METHOD, long.class);

                Class<?> listenerInterface =
                        Class.forName(HelperConfig.ON_OBJECT_RETAINED_LISTENER_CLASS);
                removeListenerMethod =
                        objectWatcherClass.getMethod(
                                HelperConfig.REMOVE_ON_OBJECT_RETAINED_LISTENER_METHOD,
                                listenerInterface);
                addListenerMethod =
                        objectWatcherClass.getMethod(
                                HelperConfig.ADD_ON_OBJECT_RETAINED_LISTENER_METHOD,
                                listenerInterface);
            } catch (Throwable t) {
                Log.w(
                        HelperConfig.LOG_TAG,
                        "Failed to initialize AppWatcher/ObjectWatcher reflection.");
            }

            // 4. Initialize GC Trigger cache
            try {
                Class<?> gcTriggerClass = Class.forName(HelperConfig.GC_TRIGGER_DEFAULT_CLASS);
                Field gcInstanceField =
                        gcTriggerClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                gcTriggerInstance = gcInstanceField.get(null);
                runGcMethod = gcTriggerInstance.getClass().getMethod(HelperConfig.RUN_GC_METHOD);
            } catch (Throwable t) {
                Log.w(
                        HelperConfig.LOG_TAG,
                        "Failed to initialize GcTrigger reflection. GC features will be disabled.");
            }

            initialized = true;
        }
    }

    /** Modifies the 'dumpHeap' field of LeakCanary's current Config object. */
    public static void setDumpHeapEnabled(boolean enabled) {
        ensureInitialized();
        if (leakCanaryInstance == null || getConfigMethod == null) return;

        try {
            Object currentConfig = getConfigMethod.invoke(leakCanaryInstance);
            if (currentConfig != null) {
                if (dumpHeapField == null) {
                    Class<?> configClass = currentConfig.getClass();
                    dumpHeapField = configClass.getDeclaredField(HelperConfig.DUMP_HEAP_FIELD);
                    dumpHeapField.setAccessible(true);
                }
                dumpHeapField.setBoolean(currentConfig, enabled);
                Log.d(
                        HelperConfig.LOG_TAG,
                        "Successfully set heap dumping to " + enabled + " via reflection.");
            }
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to configure LeakCanary heap dumping.", t);
        }
    }

    /**
     * Swaps the OnObjectRetainedListener between the internal LeakCanary listener and our Studio
     * listener.
     *
     * @param studioListener The proxy instance of the StudioLeakCanaryListener.
     * @param useStudioListener True to use the studio listener, false to restore LeakCanary's
     *     default.
     * @return True if successful, false otherwise.
     */
    public static boolean swapOnObjectRetainedListener(
            Object studioListener, boolean useStudioListener) {
        ensureInitialized();
        if (objectWatcherInstance == null
                || removeListenerMethod == null
                || addListenerMethod == null
                || internalLcInstance == null) {
            Log.e(
                    HelperConfig.LOG_TAG,
                    "Cannot swap listener. Required reflection variables are null.");
            return false;
        }

        try {
            // Remove both to be safe and avoid duplicates
            removeListenerMethod.invoke(objectWatcherInstance, internalLcInstance);
            if (studioListener != null) {
                removeListenerMethod.invoke(objectWatcherInstance, studioListener);
            }

            if (useStudioListener && studioListener != null) {
                addListenerMethod.invoke(objectWatcherInstance, studioListener);
                Log.d(
                        HelperConfig.LOG_TAG,
                        "Replaced LeakCanary default listener with Studio listener.");
            } else {
                addListenerMethod.invoke(objectWatcherInstance, internalLcInstance);
                Log.d(HelperConfig.LOG_TAG, "Restored LeakCanary default listener.");
            }
            return true;
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to swap LeakCanary listener.", t);
            return false;
        }
    }

    /** Natively checks the AppWatcher object watcher to get the retained object count. */
    public static int getRetainedObjectCount() {
        ensureInitialized();
        if (objectWatcherInstance == null || getRetainedObjectCountMethod == null) return 0;

        try {
            return (int) getRetainedObjectCountMethod.invoke(objectWatcherInstance);
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to get retained object count.", t);
            return 0;
        }
    }

    /** Clears objects watched before the specified timestamp. */
    public static void clearObjectsWatchedBefore(long timestampMillis) {
        ensureInitialized();
        if (objectWatcherInstance == null || clearObjectsMethod == null) return;

        try {
            clearObjectsMethod.invoke(objectWatcherInstance, timestampMillis);
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to clear objects watched before timestamp.", t);
        }
    }

    /** Triggers garbage collection using LeakCanary's GcTrigger. */
    public static void runGc() {
        ensureInitialized();
        if (gcTriggerInstance == null || runGcMethod == null) return;

        try {
            runGcMethod.invoke(gcTriggerInstance);
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to run GC via reflection.", t);
        }
    }

    /** Forces LeakCanary to dump the heap immediately. */
    public static void triggerDumpHeap() {
        ensureInitialized();
        if (leakCanaryInstance == null || dumpHeapMethod == null) return;

        try {
            dumpHeapMethod.invoke(leakCanaryInstance);
        } catch (Throwable t) {
            Log.e(
                    HelperConfig.LOG_TAG,
                    "Failed to trigger LeakCanary.dumpHeap() via reflection.",
                    t);
        }
    }

    /**
     * Gets the retained visible threshold from LeakCanary config.
     *
     * @return The threshold value, or default if reflection fails.
     */
    public static int getRetainedVisibleThreshold() {
        ensureInitialized();
        int threshold = HelperConfig.DEFAULT_THRESHOLD;
        if (leakCanaryInstance == null || getConfigMethod == null) return threshold;

        try {
            Object config = getConfigMethod.invoke(leakCanaryInstance);
            if (config != null) {
                Method getThresholdMethod =
                        config.getClass()
                                .getMethod(HelperConfig.GET_RETAINED_VISIBLE_THRESHOLD_METHOD);
                threshold = (int) getThresholdMethod.invoke(config);
                Log.d(
                        HelperConfig.LOG_TAG,
                        "Successfully retrieved threshold via reflection: " + threshold);
            }
        } catch (Throwable t) {
            Log.e(
                    HelperConfig.LOG_TAG,
                    "Failed to get threshold via reflection. Returning default: " + threshold,
                    t);
        }
        return threshold;
    }
}
