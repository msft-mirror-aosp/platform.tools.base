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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper class to centralize and cache all reflection calls to LeakCanary internals.
 *
 * <p>This avoids duplicating reflection logic across different receivers and listeners, and
 * improves performance by caching Class, Method, and Field lookups.
 *
 * <p>All LeakCanary class names used here must be {@code *_CLASS} constants in {@link HelperConfig}
 * with a matching rule in proguard-rules.pro (see HelperConfig).
 */
public class LeakCanaryReflectionHelper {

    public enum ReflectionState {
        UNINITIALIZED,
        COMPLETED,
        FAILED
    }

    private static volatile ReflectionState state = ReflectionState.UNINITIALIZED;

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

    // Cached Config.eventListeners field
    private static Field eventListenersField;

    // Cached HeapAnalysisProgress lookups
    private static Class<?> heapAnalysisProgressClass;
    private static Method getProgressPercentMethod;

    // Cached HeapAnalysisDone and HeapDumpFailed lookups
    private static Class<?> heapAnalysisDoneClass;
    private static Method getHeapAnalysisMethod;
    private static Class<?> heapDumpFailedClass;
    private static Method getWillRetryLaterMethod;
    private static Method getHeapDumpFailedExceptionMethod;

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
        // 1st Check (Fast Path): If already initialized or failed, return immediately without
        // locking.
        if (state != ReflectionState.UNINITIALIZED) return;

        synchronized (LeakCanaryReflectionHelper.class) {
            // 2nd Check (Safe Path): If another thread initialized it while we were waiting for the
            // lock, return.
            if (state != ReflectionState.UNINITIALIZED) return;

            // 1. Initialize LeakCanary Configuration cache and Dump Heap Method
            try {
                Class<?> leakCanaryClass = Class.forName(HelperConfig.LEAK_CANARY_CLASS);
                Field leakCanaryInstanceField =
                        leakCanaryClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                leakCanaryInstance = leakCanaryInstanceField.get(null);
                getConfigMethod = leakCanaryClass.getDeclaredMethod(HelperConfig.GET_CONFIG_METHOD);
                dumpHeapMethod = leakCanaryClass.getMethod(HelperConfig.DUMP_HEAP_METHOD);

                // 2. Initialize internal listener
                Class<?> internalLcClass = Class.forName(HelperConfig.LEAK_CANARY_INTERNAL_CLASS);
                Field internalInstanceField =
                        internalLcClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                internalInstanceField.setAccessible(true);
                internalLcInstance = internalInstanceField.get(null);

                // 3. Initialize AppWatcher and ObjectWatcher cache
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

                // 4. Initialize GC Trigger cache
                Class<?> gcTriggerClass = Class.forName(HelperConfig.GC_TRIGGER_DEFAULT_CLASS);
                Field gcInstanceField =
                        gcTriggerClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                gcTriggerInstance = gcInstanceField.get(null);
                runGcMethod = gcTriggerInstance.getClass().getMethod(HelperConfig.RUN_GC_METHOD);

                // 5. Initialize HeapAnalysisProgress cache
                heapAnalysisProgressClass =
                        Class.forName(HelperConfig.HEAP_ANALYSIS_PROGRESS_CLASS);
                getProgressPercentMethod =
                        heapAnalysisProgressClass.getMethod(
                                HelperConfig.GET_PROGRESS_PERCENT_METHOD);

                // 6. Initialize HeapAnalysisDone and HeapDumpFailed cache (ON_DEVICE result and
                // errors)
                heapAnalysisDoneClass = Class.forName(HelperConfig.HEAP_ANALYSIS_DONE_CLASS);
                getHeapAnalysisMethod =
                        heapAnalysisDoneClass.getMethod(HelperConfig.GET_HEAP_ANALYSIS_METHOD);
                heapDumpFailedClass = Class.forName(HelperConfig.HEAP_DUMP_FAILED_CLASS);
                getWillRetryLaterMethod =
                        heapDumpFailedClass.getMethod(HelperConfig.GET_WILL_RETRY_LATER_METHOD);
                getHeapDumpFailedExceptionMethod =
                        heapDumpFailedClass.getMethod(
                                HelperConfig.GET_HEAP_DUMP_FAILED_EXCEPTION_METHOD);

                // 7. Initialize the Shark lookups used to convert the analysis result.
                SharkResultConverter.loadReflection();

                // If we reached this point without throwing an exception, reflection was completely
                // successful.
                state = ReflectionState.COMPLETED;
            } catch (Throwable t) {
                Log.w(HelperConfig.LOG_TAG, "Failed to fully initialize LeakCanary reflection.", t);
                state = ReflectionState.FAILED;
            }
        }
    }

    /** Modifies the 'dumpHeap' field of LeakCanary's current Config object. */
    public static void setDumpHeapEnabled(boolean enabled) {
        ensureInitialized();
        if (state != ReflectionState.COMPLETED
                || leakCanaryInstance == null
                || getConfigMethod == null) {
            Log.w(HelperConfig.LOG_TAG, "Cannot set LeakCanary heap dumping; reflection failed.");
            return;
        }

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
     * Sets which listeners LeakCanary calls when an object is retained.
     *
     * <p>ON_HOST: only our Studio listener, so LeakCanary does not dump the heap itself. ON_DEVICE:
     * LeakCanary's own listener plus ours, so LeakCanary works as usual and we only read the count.
     *
     * @param studioListener The proxy instance of the StudioLeakCanaryListener. May be null.
     * @param replaceLeakCanaryListener True for ON_HOST (remove LeakCanary's listener), false for
     *     ON_DEVICE (keep it).
     * @return True if successful, false otherwise.
     */
    public static boolean setOnObjectRetainedListeners(
            Object studioListener, boolean replaceLeakCanaryListener) {
        ensureInitialized();
        if (state != ReflectionState.COMPLETED
                || objectWatcherInstance == null
                || removeListenerMethod == null
                || addListenerMethod == null
                || internalLcInstance == null) {
            Log.w(HelperConfig.LOG_TAG, "Cannot set LeakCanary listeners; reflection failed.");
            return false;
        }

        try {
            // Remove both first, so nothing is added twice.
            removeListenerMethod.invoke(objectWatcherInstance, internalLcInstance);
            if (studioListener != null) {
                removeListenerMethod.invoke(objectWatcherInstance, studioListener);
            }

            // Without our listener, always keep LeakCanary's, so LeakCanary still works.
            boolean keepLeakCanaryListener = !replaceLeakCanaryListener || studioListener == null;
            if (keepLeakCanaryListener) {
                addListenerMethod.invoke(objectWatcherInstance, internalLcInstance);
            }
            if (studioListener != null) {
                addListenerMethod.invoke(objectWatcherInstance, studioListener);
            }
            Log.d(
                    HelperConfig.LOG_TAG,
                    "Set LeakCanary listeners. LeakCanary's listener kept: "
                            + keepLeakCanaryListener
                            + ", Studio listener added: "
                            + (studioListener != null));
            return true;
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to set LeakCanary listeners.", t);
            return false;
        }
    }

    /**
     * Adds the listener to LeakCanary's Config.eventListeners, if it is not there yet. Keeps
     * LeakCanary's own listeners, because one of them starts the heap analysis. The list is
     * read-only, so it is replaced with a copy that has the listener at the end.
     *
     * <p>Does nothing (and logs) if reflection fails. Call it only from
     * StudioLeakCanaryManager.setMode(), which is synchronized.
     */
    public static void addEventListener(Object listener) {
        if (listener == null) return;
        ensureInitialized();
        if (state != ReflectionState.COMPLETED
                || leakCanaryInstance == null
                || getConfigMethod == null) {
            Log.w(HelperConfig.LOG_TAG, "Cannot add LeakCanary event listener; reflection failed.");
            return;
        }

        try {
            Object currentConfig = getConfigMethod.invoke(leakCanaryInstance);
            if (currentConfig == null) {
                Log.w(HelperConfig.LOG_TAG, "LeakCanary config is null; event listener not added.");
                return;
            }
            if (eventListenersField == null) {
                Field field =
                        currentConfig
                                .getClass()
                                .getDeclaredField(HelperConfig.EVENT_LISTENERS_FIELD);
                field.setAccessible(true);
                eventListenersField = field;
            }
            List<?> currentListeners = (List<?>) eventListenersField.get(currentConfig);
            if (currentListeners != null && currentListeners.contains(listener)) {
                Log.d(HelperConfig.LOG_TAG, "LeakCanary event listener already added.");
                return;
            }
            // The list can't be changed in place, so write a new list with ours at the end.
            List<Object> newListeners = new ArrayList<>();
            if (currentListeners != null) {
                newListeners.addAll(currentListeners);
            }
            newListeners.add(listener);
            // Wrapped, so LeakCanary threads always see the full list (the wrapper has a final
            // field). It is also read-only, like the original list.
            eventListenersField.set(currentConfig, Collections.unmodifiableList(newListeners));
            Log.d(HelperConfig.LOG_TAG, "Added LeakCanary event listener.");
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to add LeakCanary event listener.", t);
        }
    }

    /**
     * Returns the analysis progress (0 to 100) if the event is a HeapAnalysisProgress event.
     * Returns {@code HelperConfig.NO_ANALYSIS_PROGRESS} for any other event, or if reflection
     * fails.
     */
    public static int getHeapAnalysisProgressPercent(Object event) {
        ensureInitialized();
        if (event == null
                || state != ReflectionState.COMPLETED
                || !heapAnalysisProgressClass.isInstance(event)) {
            return HelperConfig.NO_ANALYSIS_PROGRESS;
        }

        try {
            double progress = (double) getProgressPercentMethod.invoke(event);
            int percent = (int) (progress * 100);
            return Math.max(0, Math.min(100, percent));
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to read LeakCanary analysis progress.", t);
            return HelperConfig.NO_ANALYSIS_PROGRESS;
        }
    }

    /** Returns true if every lookup in the start-up check passed. */
    static boolean isInitialized() {
        ensureInitialized();
        return state == ReflectionState.COMPLETED;
    }

    /**
     * Returns true if the event is LeakCanary's HeapAnalysisDone event (a finished analysis, good
     * or failed). Returns false for other events, or if reflection failed.
     */
    static boolean isHeapAnalysisDone(Object event) {
        return isInitialized() && heapAnalysisDoneClass.isInstance(event);
    }

    /**
     * Returns the Shark HeapAnalysis inside a HeapAnalysisDone event. Call only when {@link
     * #isHeapAnalysisDone} is true.
     */
    static Object getHeapAnalysis(Object event) throws ReflectiveOperationException {
        return getHeapAnalysisMethod.invoke(event);
    }

    /**
     * Returns true if the event is LeakCanary's HeapDumpFailed event. Returns false for other
     * events, or if reflection failed.
     */
    static boolean isHeapDumpFailed(Object event) {
        return isInitialized() && heapDumpFailedClass.isInstance(event);
    }

    /**
     * Returns true if LeakCanary will try the failed heap dump again by itself. This is true for
     * dumps LeakCanary started on its own, and false for dumps a user asked for (Studio's
     * LeakCanary.dumpHeap() call, or a tap on LeakCanary's notification). Call only when {@link
     * #isHeapDumpFailed} is true.
     */
    static boolean heapDumpWillRetryLater(Object event) throws ReflectiveOperationException {
        return (Boolean) getWillRetryLaterMethod.invoke(event);
    }

    /** Returns why the heap dump failed. Call only when {@link #isHeapDumpFailed} is true. */
    static Throwable getHeapDumpFailedException(Object event) throws ReflectiveOperationException {
        return (Throwable) getHeapDumpFailedExceptionMethod.invoke(event);
    }

    /** Natively checks the AppWatcher object watcher to get the retained object count. */
    public static int getRetainedObjectCount() {
        ensureInitialized();
        if (state != ReflectionState.COMPLETED
                || objectWatcherInstance == null
                || getRetainedObjectCountMethod == null) {
            Log.w(HelperConfig.LOG_TAG, "Cannot get retained object count; reflection failed.");
            return 0;
        }

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
        if (state != ReflectionState.COMPLETED
                || objectWatcherInstance == null
                || clearObjectsMethod == null) {
            Log.w(HelperConfig.LOG_TAG, "Cannot clear watched objects; reflection failed.");
            return;
        }

        try {
            clearObjectsMethod.invoke(objectWatcherInstance, timestampMillis);
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to clear objects watched before timestamp.", t);
        }
    }

    /** Triggers garbage collection using LeakCanary's GcTrigger. */
    public static void runGc() {
        ensureInitialized();
        if (state != ReflectionState.COMPLETED
                || gcTriggerInstance == null
                || runGcMethod == null) {
            Log.w(HelperConfig.LOG_TAG, "Cannot trigger GC; reflection failed.");
            return;
        }

        try {
            runGcMethod.invoke(gcTriggerInstance);
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to run GC via reflection.", t);
        }
    }

    /** Forces LeakCanary to dump the heap immediately. */
    public static void triggerDumpHeap() {
        ensureInitialized();
        if (state != ReflectionState.COMPLETED
                || leakCanaryInstance == null
                || dumpHeapMethod == null) {
            Log.w(HelperConfig.LOG_TAG, "Cannot trigger heap dump; reflection failed.");
            return;
        }

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
     * @return The threshold value, -1 if reflection failed, or default if it succeeds but fails
     *     during invocation.
     */
    public static int getRetainedVisibleThreshold() {
        ensureInitialized();
        if (state != ReflectionState.COMPLETED) {
            Log.w(
                    HelperConfig.LOG_TAG,
                    "Reflection was not fully successful. Returning failure threshold to signal"
                            + " Studio.");
            return HelperConfig.REFLECTION_FAILED_THRESHOLD;
        }
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
