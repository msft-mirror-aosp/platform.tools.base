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
 * <p>It switches between two modes: MODE_ON_DEVICE: LeakCanary's original listener is active, and
 * it dumps the heap locally on the device. MODE_ON_HOST: LeakCanary's default listener is replaced
 * by StudioLeakCanaryListener, which tracks object counts and delegates the actual heap dumping to
 * Android Studio.
 */
public class StudioLeakCanaryManager {

    public static final int MODE_ON_DEVICE = 0;
    public static final int MODE_ON_HOST = 1;
    private static StudioLeakCanaryManager instance;
    private int currentMode = -1; // Uninitialized state
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

        if (mode == MODE_ON_HOST) {
            setLeakCanaryDumpHeap(false);
            swapListener(true);
        } else {
            setLeakCanaryDumpHeap(true);
            swapListener(false);
        }
    }

    /** Returns the current active mode. */
    public int getCurrentMode() {
        return currentMode;
    }

    private void setLeakCanaryDumpHeap(boolean dumpHeap) {
        LeakCanaryReflectionHelper.setDumpHeapEnabled(dumpHeap);
    }

    private void swapListener(boolean useStudioListener) {
        if (useStudioListener && cachedStudioListenerProxy == null) {
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
                LeakCanaryReflectionHelper.swapOnObjectRetainedListener(
                        cachedStudioListenerProxy, useStudioListener);

        if (success) {
            StudioLeakCanaryListener.setIsEnabled(useStudioListener);
        } else {
            Log.e(HelperConfig.LOG_TAG, "Failed to swap LeakCanary listener during mode switch.");
        }
    }
}
