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

package com.android.tools.ui.inspector.service;

import android.os.Looper;
import android.util.Log;

import dalvik.system.DexClassLoader;

import java.lang.reflect.Method;

/**
 * Service loaded into the app process by the native agent.
 * Responsible for loading the inspector payload and invoking its entry point.
 * This service is expected to start the inspector and return immediately.
 */
public class InspectorService {

    public static final int RESULT_OK = 0;
    public static final int RESULT_ERROR = 1;
    public static final int RESULT_EXCEPTION = -1;

    private static final String TAG = "studio.InspectorService";
    private static final String INSPECTOR_LAUNCHER_CLASS_NAME =
            "com.android.tools.ui.inspector.payload.InspectorLauncher";
    private static final String START_METHOD_NAME = "start";

    // TODO: Once we implement the Protobuf protocol for communication, we should adopt a strategy
    // similar to AppInspectionService to report bootstrap failures back to the host in a structured
    // way rather than relying on log scraping or simple return codes.
    public static int initialize(String payloadJarPath, String pid) {
        try {
            if (pid == null || pid.isEmpty()) {
                Log.e(TAG, "PID is required for initialization");
                return RESULT_ERROR;
            }

            ClassLoader appClassLoader = getAppClassLoader();
            if (appClassLoader == null) {
                Log.e(TAG, "Could not find app ClassLoader");
                return RESULT_ERROR;
            }

            DexClassLoader dexClassLoader = new DexClassLoader(
                    payloadJarPath,
                    null,
                    null,
                    appClassLoader
            );

            Class<?> launcherClass = Class.forName(
                    INSPECTOR_LAUNCHER_CLASS_NAME,
                    true,
                    dexClassLoader
            );

            Method startMethod = launcherClass.getMethod(START_METHOD_NAME, String.class);

            startMethod.invoke(null, pid);

            return RESULT_OK;
        } catch (Throwable e) {
            Log.e(TAG, "Error in InspectorService initialization", e);
            return RESULT_EXCEPTION;
        }
    }

    private static ClassLoader getAppClassLoader() {
        // TODO: Use JVMTI to find Application instance as the primary strategy once we have art tooling,
        // similar to what AppInspectionService does. The Looper strategy should be the fallback.
        Looper looper = Looper.getMainLooper();
        if (looper != null && looper.getThread() != null) {
            return looper.getThread().getContextClassLoader();
        }
        return null;
    }
}
