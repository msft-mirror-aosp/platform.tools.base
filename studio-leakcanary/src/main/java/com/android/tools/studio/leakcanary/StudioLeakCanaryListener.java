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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * A custom listener that replaces LeakCanary's default listener.
 *
 * <p>This listener adapts LeakCanary's object tracking for Android Studio's Profiler. Instead of
 * triggering a heap dump directly when a threshold is reached, it: 1. Listens for object retention
 * events from AppWatcher. 2. Schedules a debounced check on a background thread. 3. Runs garbage
 * collection (GC) to ensure weak references are cleared if possible. 4. Counts the remaining
 * retained objects. 5. Broadcasts the count to Android Studio via an intent.
 *
 * <p>Android Studio then decides whether to trigger a heap dump based on its own logic and user
 * configuration.
 *
 * <p>NOTE: This class uses reflection and dynamic proxies to avoid a compile-time dependency on the
 * leakcanary-android library, which may not be present in the build environment.
 */
@SuppressWarnings("unused")
public class StudioLeakCanaryListener {

    private static volatile StudioLeakCanaryListener instance;

    // Re-check in 5 seconds if objects are still retained.
    private static final long WAIT_FOR_OBJECT_THRESHOLD_MILLIS = 5000L;
    private static final long DEBOUNCE_DELAY_MILLIS = 100L;

    // Reflection cache
    private static volatile boolean reflectionInitializationAttempted = false;
    private static volatile Object objectWatcherInstance;
    private static volatile Method getRetainedObjectCountMethod;
    private static volatile Method clearObjectsMethod;
    private static volatile Object gcTriggerInstance;
    private static volatile Method runGcMethod;

    private final Application application;
    private final HandlerThread handlerThread;
    private final Handler backgroundHandler;

    // Store the last reported count to avoid redundant broadcasts.
    private int lastReportedCount = -1;

    // Create the Runnable once so we can reference it to cancel/reschedule.
    private final Runnable checkRunnable =
            new Runnable() {
                @Override
                public void run() {
                    checkRetainedObjects();
                }
            };

    private StudioLeakCanaryListener(Application application) {
        this.application = application;

        // A background thread for running GC and checking the count, preventing UI jank.
        this.handlerThread = new HandlerThread("StudioLeakCanaryHelper");
        this.handlerThread.start();
        this.backgroundHandler = new Handler(handlerThread.getLooper());

        // Register this instance so we can call it from the BroadcastReceiver.
        // We do this at the end to ensure the object is fully initialized before exposure.
        instance = this;
    }

    /**
     * Creates a dynamic proxy instance of leakcanary.OnObjectRetainedListener that delegates to a
     * new instance of StudioLeakCanaryListener.
     */
    public static Object createInstance(Application application) {
        try {
            Class<?> listenerInterface =
                    Class.forName(HelperConfig.ON_OBJECT_RETAINED_LISTENER_CLASS);
            final StudioLeakCanaryListener listener = new StudioLeakCanaryListener(application);

            return Proxy.newProxyInstance(
                    listenerInterface.getClassLoader(),
                    new Class<?>[] {listenerInterface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args)
                                throws Throwable {
                            if (HelperConfig.ON_OBJECT_RETAINED_METHOD.equals(method.getName())) {
                                listener.onObjectRetained();
                                return null;
                            }
                            // Handle Object methods like toString, hashCode, equals.
                            // This is required because the Proxy must behave like a valid Java
                            // object
                            // to avoid crashes if these methods are called (e.g. by collections or
                            // logging).
                            if (HelperConfig.TO_STRING_METHOD.equals(method.getName())) {
                                return "StudioLeakCanaryListenerProxy";
                            }
                            if (HelperConfig.HASH_CODE_METHOD.equals(method.getName())) {
                                return listener.hashCode();
                            }
                            if (HelperConfig.EQUALS_METHOD.equals(method.getName())) {
                                return args[0] != null
                                        && Proxy.isProxyClass(args[0].getClass())
                                        && Proxy.getInvocationHandler(args[0]) == this;
                            }

                            Log.w(
                                    HelperConfig.LOG_TAG,
                                    "Unhandled method call on StudioLeakCanaryListener proxy: "
                                            + method.getName());

                            // Return a safe default value based on the return type to avoid crashes
                            // if the interface evolves (e.g. returning primitives).
                            return getDefaultValue(method.getReturnType());
                        }
                    });
        } catch (ClassNotFoundException e) {
            Log.e(
                    HelperConfig.LOG_TAG,
                    "LeakCanary not found. StudioLeakCanaryListener will not be created.",
                    e);
            return null;
        } catch (Exception e) {
            Log.e(HelperConfig.LOG_TAG, "Failed to create StudioLeakCanaryListener proxy.", e);
            return null;
        }
    }

    /**
     * Returns a safe default value for the given return type. This prevents NullPointerExceptions
     * in Kotlin (which treats many returns as non-null) if the proxied interface adds new methods
     * that our proxy doesn't explicitly handle.
     */
    private static Object getDefaultValue(Class<?> returnType) {
        if (returnType.isArray()) {
            return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
        }
        switch (returnType.getName()) {
            case "boolean":
                return false;
            case "int":
                return 0;
            case "long":
                return 0L;
            case "float":
                return 0.0f;
            case "double":
                return 0.0d;
            case "byte":
                return (byte) 0;
            case "short":
                return (short) 0;
            case "char":
                return '\u0000';
            case "java.lang.String":
                return "";
            default:
                if (returnType.isAssignableFrom(java.util.List.class)) {
                    return java.util.Collections.emptyList();
                } else if (returnType.isAssignableFrom(java.util.Map.class)) {
                    return java.util.Collections.emptyMap();
                } else if (returnType.isAssignableFrom(java.util.Set.class)) {
                    return java.util.Collections.emptySet();
                }
                return null;
        }
    }

    /**
     * Initializes the reflection cache.
     *
     * <p>This is called lazily (instead of in the constructor) to: 1. Avoid slowing down app
     * startup (the constructor runs on the main thread). 2. Perform initialization on the
     * background thread (where methods like checkRetainedObjects run).
     */
    private static void ensureReflectionInitialized() {
        if (reflectionInitializationAttempted) return;
        synchronized (StudioLeakCanaryListener.class) {
            if (reflectionInitializationAttempted) return;
            try {
                Class<?> appWatcherClass = Class.forName(HelperConfig.APP_WATCHER_CLASS);
                Field appWatcherInstanceField =
                        appWatcherClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                Object appWatcherInstance = appWatcherInstanceField.get(null);

                Method getObjectWatcherMethod =
                        appWatcherClass.getMethod(HelperConfig.GET_OBJECT_WATCHER_METHOD);
                objectWatcherInstance = getObjectWatcherMethod.invoke(appWatcherInstance);

                getRetainedObjectCountMethod =
                        objectWatcherInstance
                                .getClass()
                                .getMethod(HelperConfig.GET_RETAINED_OBJECT_COUNT_METHOD);
                clearObjectsMethod =
                        objectWatcherInstance
                                .getClass()
                                .getMethod(
                                        HelperConfig.CLEAR_OBJECTS_WATCHED_BEFORE_METHOD,
                                        long.class);

                Class<?> gcTriggerClass = Class.forName(HelperConfig.GC_TRIGGER_DEFAULT_CLASS);
                Field instanceField = gcTriggerClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                gcTriggerInstance = instanceField.get(null);
                runGcMethod = gcTriggerInstance.getClass().getMethod(HelperConfig.RUN_GC_METHOD);
            } catch (ClassNotFoundException e) {
                Log.w(
                        HelperConfig.LOG_TAG,
                        "LeakCanary classes not found. Reflection-based features will be"
                                + " disabled.");
            } catch (Throwable t) {
                Log.e(
                        HelperConfig.LOG_TAG,
                        "Failed to initialize LeakCanary reflection cache. "
                                + "LeakCanary version might be incompatible.",
                        t);
            } finally {
                reflectionInitializationAttempted = true;
            }
        }
    }

    private void runGc() {
        ensureReflectionInitialized();
        if (gcTriggerInstance == null || runGcMethod == null) return;
        try {
            runGcMethod.invoke(gcTriggerInstance);
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to run GC via reflection", t);
        }
    }

    /**
     * Called by the proxy when an object is detected as retained. This schedules a debounced check
     * to avoid running GC too frequently during rapid object allocation/deallocation.
     */
    public void onObjectRetained() {
        // Cancel any existing check.
        // If we were waiting for a debounce, this resets the timer.
        backgroundHandler.removeCallbacks(checkRunnable);

        // Schedule a new check in 100ms (Immediate feedback for new leaks)
        backgroundHandler.postDelayed(checkRunnable, DEBOUNCE_DELAY_MILLIS);
    }

    /** Checks for retained objects, runs GC, and reports the count to Studio. */
    private void checkRetainedObjects() {
        // 1. Get the count BEFORE GC
        int retainedReferenceCount = getRetainedObjectCount();

        // 2. Run GC if there are potential leaks
        if (retainedReferenceCount > 0) {
            runGc();
            // 3. Get the count AFTER GC
            retainedReferenceCount = getRetainedObjectCount();
            Log.d(HelperConfig.LOG_TAG, "Retained objects after GC: " + retainedReferenceCount);
        }

        // 4. Transmit the count to Studio via the Bridge
        // Only broadcast if the count has changed
        if (retainedReferenceCount != lastReportedCount) {
            lastReportedCount = retainedReferenceCount;
            Intent intent = new Intent(HelperConfig.OBJECT_COUNT_UPDATE_INTENT);
            intent.putExtra(HelperConfig.OBJECT_COUNT_UPDATE_EXTRA, retainedReferenceCount);
            intent.setPackage(application.getPackageName()); // Security: Keep within app
            application.sendBroadcast(intent);
        }

        // Re-check periodically if objects are still retained.
        // This ensures that if an object is eventually collected without a new
        // retention event, the count in Studio is updated to 0.
        if (retainedReferenceCount > 0) {
            scheduleRetainedObjectCheck(WAIT_FOR_OBJECT_THRESHOLD_MILLIS);
        }
    }

    private int getRetainedObjectCount() {
        ensureReflectionInitialized();
        if (objectWatcherInstance == null || getRetainedObjectCountMethod == null) return 0;
        try {
            return (int) getRetainedObjectCountMethod.invoke(objectWatcherInstance);
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to get retained object count via reflection", t);
            return 0;
        }
    }

    /** A simplified, non-debounced scheduler. Used to re-check after a delay. */
    private void scheduleRetainedObjectCheck(long delayMillis) {
        backgroundHandler.removeCallbacks(checkRunnable);
        backgroundHandler.postDelayed(checkRunnable, delayMillis);
    }

    /**
     * Called from the profiler agent (perfa.cc) via JNI after the host-side heap dump analysis is
     * complete. This clears LeakCanary's internal counters, preventing the same retained objects
     * from repeatedly triggering a heap dump.
     *
     * @param heapDumpTimestamp The timestamp (in milliseconds) of the heap dump. Objects retained
     *     before this time are considered "handled".
     */
    public static void onHeapDumpFinished(final long heapDumpTimestamp) {
        Log.d(
                HelperConfig.LOG_TAG,
                "Heap dump finished event received. Clearing objects watched before "
                        + heapDumpTimestamp
                        + " (ms)");

        ensureReflectionInitialized();
        if (objectWatcherInstance != null && clearObjectsMethod != null) {
            try {
                clearObjectsMethod.invoke(objectWatcherInstance, heapDumpTimestamp);
            } catch (Throwable t) {
                Log.e(
                        HelperConfig.LOG_TAG,
                        "Failed to clear objects watched before timestamp via reflection",
                        t);
            }
        }

        // Force an immediate update to send "0" (or remaining count) to Studio
        final StudioLeakCanaryListener currentInstance = instance;
        if (currentInstance != null) {
            currentInstance.backgroundHandler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            int count = currentInstance.getRetainedObjectCount();
                            // Update the last reported count to keep state consistent
                            currentInstance.lastReportedCount = count;
                            Intent intent = new Intent(HelperConfig.OBJECT_COUNT_UPDATE_INTENT);
                            intent.putExtra(HelperConfig.OBJECT_COUNT_UPDATE_EXTRA, count);
                            intent.setPackage(
                                    currentInstance.application
                                            .getPackageName()); // Security: Keep within app
                            currentInstance.application.sendBroadcast(intent);
                        }
                    });
        }
    }

    /** Forces an immediate check for retained objects. Useful for debugging or manual triggers. */
    public static void triggerImmediateCheck() {
        final StudioLeakCanaryListener currentInstance = instance;
        if (currentInstance != null) {
            currentInstance.backgroundHandler.post(currentInstance.checkRunnable);
        }
    }

    /** Called when Studio starts listening. We should send the current count immediately. */
    public static void sendCurrentCount() {
        final StudioLeakCanaryListener currentInstance = instance;
        if (currentInstance != null) {
            currentInstance.backgroundHandler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            int count = currentInstance.getRetainedObjectCount();
                            // Sync the last reported count to ensure future updates are correct
                            currentInstance.lastReportedCount = count;
                            Intent updateIntent =
                                    new Intent(HelperConfig.OBJECT_COUNT_UPDATE_INTENT);
                            updateIntent.putExtra(HelperConfig.OBJECT_COUNT_UPDATE_EXTRA, count);
                            updateIntent.setPackage(currentInstance.application.getPackageName());
                            currentInstance.application.sendBroadcast(updateIntent);
                        }
                    });
        }
    }
}
