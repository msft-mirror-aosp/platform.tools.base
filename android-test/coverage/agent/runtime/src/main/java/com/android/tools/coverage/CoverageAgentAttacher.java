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

import android.os.Build;
import android.os.Debug;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

/**
 * A JUnit {@link RunListener} that attempts to attach the native JVMTI agent using the internal
 * Android Java API (android.os.Debug.attachJvmtiAgent).
 *
 * <p>This provides a resilient, internal attachment path for standard instrumentation runs.
 *
 * <p>Coverage is optional: if the agent can't be attached, or its data can't be dumped, the problem
 * is logged and the tests run and report their results without coverage.
 */
public class CoverageAgentAttacher extends RunListener {

    private static final String TAG = "studio.coverage";
    private static final String CONFIG_KEY = "coverage-agent-config";

    /** Set by the test orchestrator on the run that only lists the tests. */
    static final String LIST_TESTS_FOR_ORCHESTRATOR_KEY = "listTestsForOrchestrator";

    /** {@code Debug.attachJvmtiAgent} was added in Android 9 (API 28). */
    static final int MIN_SDK_INT = 28;

    private static final String TRACKER_CLASS_NAME = "com.android.tools.coverage.CoverageTracker";

    /** The Android calls the attacher makes, replaced in tests. */
    interface Environment {
        int sdkInt();

        /** Returns the instrumentation argument {@code key}, or null if it isn't set. */
        String argument(String key);

        void attachJvmtiAgent(String library, String options) throws Exception;

        /** Whether the agent made {@code CoverageTracker} available to the app. */
        boolean isCoverageTrackerLoaded();

        void dumpCoverageData();

        void info(String message);

        void warn(String message);

        void error(String message, Throwable throwable);
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
        try {
            attached = attachAgent();
        } catch (Exception | LinkageError e) {
            // Not only exceptions: without the API the call fails with NoSuchMethodError, and a
            // missing class or native method with another LinkageError. Anything a listener throws
            // here would stop the test run. Errors like OutOfMemoryError still propagate.
            environment.error("Java API attachment failed. Tests run without coverage.", e);
            attached = false;
        }
        super.testRunStarted(description);
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        if (attached) {
            try {
                environment.info("Test run finished. Triggering explicit coverage dump.");
                environment.dumpCoverageData();
            } catch (Exception | LinkageError e) {
                environment.error("Coverage dump failed.", e);
            }
        }
        super.testRunFinished(result);
    }

    private boolean attachAgent() throws Exception {
        int sdkInt = environment.sdkInt();
        if (sdkInt < MIN_SDK_INT) {
            environment.warn(
                    "On-device coverage needs Android 9 (API "
                            + MIN_SDK_INT
                            + ") or higher, but this device runs API "
                            + sdkInt
                            + ". Tests run without coverage.");
            return false;
        }

        // The orchestrator's listing run doesn't run any test, so there is nothing to measure.
        if (Boolean.parseBoolean(environment.argument(LIST_TESTS_FOR_ORCHESTRATOR_KEY))) {
            return false;
        }

        // 1. Get Instrumentation Arguments
        // 2. Extract Config (Format: "/path/to/agent.so=options")
        String config = environment.argument(CONFIG_KEY);

        if (config == null || config.isEmpty()) {
            environment.warn("No attachment config found in arguments. Skipping Java API path.");
            return false;
        }

        String agentPath = config.contains("=") ? config.substring(0, config.indexOf("=")) : config;
        String options = config.contains("=") ? config.substring(config.indexOf("=") + 1) : "";

        // 3. Call Debug.attachJvmtiAgent(library, options, classLoader)
        environment.info("Attempting attachment via Java API: " + agentPath);
        environment.attachJvmtiAgent(agentPath, options);

        // The agent can load but fail to start, for example if it can't set up its runtime. Then
        // CoverageTracker doesn't exist, and dumping at the end of the run would throw.
        if (!environment.isCoverageTrackerLoaded()) {
            environment.warn(
                    "The coverage agent attached but did not start. Tests run without coverage.");
            return false;
        }

        // 4. Output the Success Signal for host-side verification.
        environment.info("Successfully attached via Java API.");
        return true;
    }

    /** The real Android calls. */
    private static final class AndroidEnvironment implements Environment {
        @Override
        public int sdkInt() {
            return Build.VERSION.SDK_INT;
        }

        @Override
        public String argument(String key) {
            return InstrumentationRegistry.getArguments().getString(key);
        }

        @Override
        public void attachJvmtiAgent(String library, String options) throws Exception {
            Debug.attachJvmtiAgent(library, options, null);
        }

        @Override
        public boolean isCoverageTrackerLoaded() {
            try {
                // The agent adds CoverageTracker to the boot class path.
                Class.forName(TRACKER_CLASS_NAME, false, null);
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
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
        public void error(String message, Throwable throwable) {
            Log.e(TAG, message, throwable);
        }
    }
}
