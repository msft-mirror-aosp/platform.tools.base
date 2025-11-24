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

import android.app.Application;
import android.util.Log;

import androidx.annotation.Keep;

import java.lang.reflect.Method;

/** A helper class that provides support for the LeakCanary profiler task. */
public final class LeakCanaryPresenceChecker {
    private static final String TAG = "studio.profiler";
    private static final String LEAKCANARY_CLASS_NAME = "leakcanary.AppWatcher";

    /** Called from the profiler agent (perfa.cc) via JNI to check for LeakCanary's presence. */
    @Keep
    @SuppressWarnings("unused") // Called via JNI
    public static boolean isPresent() {
        try {
            Application application;
            try {
                // To find an app-level dependency, we must use the app's ClassLoader, as the
                // agent's JNI context cannot see it otherwise.
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Method currentApplicationMethod =
                        activityThreadClass.getMethod("currentApplication");
                application = (Application) currentApplicationMethod.invoke(null);

                if (application == null) {
                    Log.d(TAG, "LeakCanary class check: Could not get Application instance.");
                    return true;
                }
            } catch (Exception e) {
                Log.d(TAG, "LeakCanary class check: FAILED to get application.", e);
                return true;
            }

            Class.forName(LEAKCANARY_CLASS_NAME, false, application.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            // We are certain that LeakCanary is not present.
            Log.e(TAG, "LeakCanary class check: FAILED. AppWatcher class not found.");
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
