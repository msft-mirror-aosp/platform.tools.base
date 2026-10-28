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
import android.content.Context;
import android.util.Log;

/**
 * Manages the mode of LeakCanary integration within the app.
 *
 * <p>MODE_ON_DEVICE: LeakCanary works as usual and dumps the heap on the device. Our listener runs
 * next to LeakCanary's and only sends the retained object count to Studio. MODE_ON_HOST: our
 * listener replaces LeakCanary's, sends the count, and Android Studio does the heap dump.
 */
public class StudioLeakCanaryManager {

    public static final int MODE_ON_DEVICE = 0;
    public static final int MODE_ON_HOST = 1;
    private static StudioLeakCanaryManager instance;
    // Volatile: also read from the listener's background thread.
    private volatile int currentMode = -1; // Uninitialized state
    private Application application;
    private Object cachedStudioListenerProxy;

    private StudioLeakCanaryManager() {}

    /** Returns the singleton instance of the manager. */
    public static synchronized StudioLeakCanaryManager getInstance() {
        if (instance == null) {
            instance = new StudioLeakCanaryManager();
        }
        return instance;
    }

    /**
     * Initializes the manager with the application context and sets the default mode. This is
     * typically called on app startup by {@link HelperInitializer}.
     */
    public void initialize(Context context) {
        if (context != null && context.getApplicationContext() instanceof Application) {
            this.application = (Application) context.getApplicationContext();
        }
        // Initialize to ON_HOST by default so it stays silent unless requested otherwise.
        setMode(MODE_ON_HOST);
    }

    /**
     * Changes the current LeakCanary tracking mode.
     *
     * @param mode Either {@code MODE_ON_DEVICE} or {@code MODE_ON_HOST}.
     */
    public synchronized void setMode(int mode) {
        if (this.currentMode == mode) {
            return;
        }

        Log.d(
                HelperConfig.LOG_TAG,
                "Switching LeakCanary Mode to: "
                        + (mode == MODE_ON_HOST ? "ON_HOST" : "ON_DEVICE"));
        this.currentMode = mode;

        // In ON_HOST, Studio dumps the heap, so LeakCanary must not.
        setLeakCanaryDumpHeap(mode != MODE_ON_HOST);
        setUpListeners(mode == MODE_ON_HOST);
    }

    /** Returns the current active mode. */
    public int getCurrentMode() {
        return currentMode;
    }

    private void setLeakCanaryDumpHeap(boolean dumpHeap) {
        LeakCanaryReflectionHelper.setDumpHeapEnabled(dumpHeap);
    }

    /**
     * Adds our listener in both modes, so the retained count is sent to Studio in both. Only
     * ON_HOST removes LeakCanary's own listener.
     */
    private void setUpListeners(boolean replaceLeakCanaryListener) {
        if (cachedStudioListenerProxy == null) {
            if (application != null) {
                cachedStudioListenerProxy = StudioLeakCanaryListener.createInstance(application);
                if (cachedStudioListenerProxy == null) {
                    Log.e(
                            HelperConfig.LOG_TAG,
                            "Failed to create StudioLeakCanaryListener proxy. Mode switch may be"
                                    + " incomplete.");
                }
            } else {
                Log.e(
                        HelperConfig.LOG_TAG,
                        "Application context is null. Cannot create StudioLeakCanaryListener.");
            }
        }

        boolean success =
                LeakCanaryReflectionHelper.setOnObjectRetainedListeners(
                        cachedStudioListenerProxy, replaceLeakCanaryListener);

        if (success) {
            StudioLeakCanaryListener.setIsEnabled(cachedStudioListenerProxy != null);
        } else {
            Log.e(HelperConfig.LOG_TAG, "Failed to set LeakCanary listeners during mode switch.");
        }
    }
}
