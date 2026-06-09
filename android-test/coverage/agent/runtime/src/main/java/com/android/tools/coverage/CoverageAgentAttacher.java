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

package com.android.tools.coverage;

import android.os.Bundle;
import android.os.Debug;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

/**
 * A JUnit {@link RunListener} that attempts to attach the native JVMTI agent using the
 * internal Android Java API (android.os.Debug.attachJvmtiAgent).
 *
 * <p>This provides a resilient, internal attachment path for standard instrumentation runs.
 */
public class CoverageAgentAttacher extends RunListener {

    private static final String TAG = "studio.coverage";
    private static final String CONFIG_KEY = "coverage-agent-config";

    @Override
    public void testRunStarted(Description description) throws Exception {
        attachAgent();
        super.testRunStarted(description);
    }

    private void attachAgent() {
        try {
            // 1. Get Instrumentation Arguments
            Bundle bundle = InstrumentationRegistry.getArguments();

            // 2. Extract Config (Format: "/path/to/agent.so=options")
            String config = bundle.getString(CONFIG_KEY);

            if (config == null || config.isEmpty()) {
                Log.w(TAG, "No attachment config found in arguments. Skipping Java API path.");
                return;
            }

            String agentPath = config.contains("=") ? config.substring(0, config.indexOf("=")) : config;
            String options = config.contains("=") ? config.substring(config.indexOf("=") + 1) : "";

            // 3. Call Debug.attachJvmtiAgent(library, options, classLoader)
            Log.i(TAG, "Attempting attachment via Java API: " + agentPath);
            Debug.attachJvmtiAgent(agentPath, options, null);

            // 4. Output the Success Signal for host-side verification.
            Log.i(TAG, "Successfully attached via Java API.");

        } catch (Exception e) {
            Log.e(TAG, "Java API attachment failed: " + e.getMessage());
        }
    }
}
