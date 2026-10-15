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

import android.os.Debug;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.runner.Description;
import org.junit.runner.Result;
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

    /** The Android calls the attacher makes, replaced in tests. */
    interface Environment {
        /** Returns the instrumentation argument {@code key}, or null if it isn't set. */
        String argument(String key);

        void attachJvmtiAgent(String library, String options) throws Exception;

        void dumpCoverageData();

        void info(String message);

        void warn(String message);

        void error(String message);
    }

    private final Environment environment;
    private boolean attached = false;

    public CoverageAgentAttacher() {
        this(new AndroidEnvironment());
    }

    CoverageAgentAttacher(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        attached = attachAgent();
        super.testRunStarted(description);
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        if (attached) {
            environment.info("Test run finished. Triggering explicit coverage dump.");
            environment.dumpCoverageData();
        }
        super.testRunFinished(result);
    }

    private boolean attachAgent() {
        try {
            // 1. Get Instrumentation Arguments
            // 2. Extract Config (Format: "/path/to/agent.so=options")
            String config = environment.argument(CONFIG_KEY);

            if (config == null || config.isEmpty()) {
                environment.warn(
                        "No attachment config found in arguments. Skipping Java API path.");
                return false;
            }

            String agentPath =
                    config.contains("=") ? config.substring(0, config.indexOf("=")) : config;
            String options = config.contains("=") ? config.substring(config.indexOf("=") + 1) : "";

            // 3. Call Debug.attachJvmtiAgent(library, options, classLoader)
            environment.info("Attempting attachment via Java API: " + agentPath);
            environment.attachJvmtiAgent(agentPath, options);

            // 4. Output the Success Signal for host-side verification.
            environment.info("Successfully attached via Java API.");
            return true;

        } catch (Exception e) {
            environment.error("Java API attachment failed: " + e.getMessage());
            return false;
        }
    }

    /** The real Android calls. */
    private static final class AndroidEnvironment implements Environment {
        @Override
        public String argument(String key) {
            return InstrumentationRegistry.getArguments().getString(key);
        }

        @Override
        public void attachJvmtiAgent(String library, String options) throws Exception {
            Debug.attachJvmtiAgent(library, options, null);
        }

        @Override
        public void dumpCoverageData() {
            CoverageTracker.dumpCoverageData();
        }

        @Override
        public void info(String message) {
            Log.i(TAG, message);
        }

        @Override
        public void warn(String message) {
            Log.w(TAG, message);
        }

        @Override
        public void error(String message) {
            Log.e(TAG, message);
        }
    }
}
