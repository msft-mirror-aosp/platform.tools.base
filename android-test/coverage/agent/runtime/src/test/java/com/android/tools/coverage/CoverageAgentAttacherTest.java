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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests that {@link CoverageAgentAttacher} never lets a coverage problem escape into the test run.
 */
public class CoverageAgentAttacherTest {

    private static final String CONFIG =
            "/data/local/tmp/coverage_agent.so=com.example,com/example,/data/cov";

    private final FakeEnvironment environment = new FakeEnvironment();
    private final CoverageAgentAttacher attacher = new CoverageAgentAttacher(environment);

    @Test
    public void attachesWithParsedConfigAndDumpsAtTheEnd() throws Exception {
        environment.arguments.put("coverage-agent-config", CONFIG);

        runTests();

        assertEquals("/data/local/tmp/coverage_agent.so", environment.attachedLibrary);
        assertEquals("com.example,com/example,/data/cov", environment.attachedOptions);
        assertEquals(1, environment.dumpCount);
        assertTrue(environment.errors.isEmpty());
    }

    @Test
    public void doesNotAttachBelowApi28() throws Exception {
        environment.arguments.put("coverage-agent-config", CONFIG);
        environment.sdkInt = 27;

        runTests();

        assertNull(environment.attachedLibrary);
        assertEquals(0, environment.dumpCount);
        assertTrue(environment.warnings.get(0).contains("API 27"));
    }

    @Test
    public void attachErrorDoesNotEscape() throws Exception {
        // What a device without Debug.attachJvmtiAgent throws.
        environment.arguments.put("coverage-agent-config", CONFIG);
        environment.attachFailure = new NoSuchMethodError("attachJvmtiAgent");

        runTests();

        assertEquals(0, environment.dumpCount);
        assertEquals(1, environment.errors.size());
    }

    @Test
    public void attachExceptionDoesNotEscape() throws Exception {
        environment.arguments.put("coverage-agent-config", CONFIG);
        environment.attachFailure = new IOException("agent not found");

        runTests();

        assertEquals(0, environment.dumpCount);
        assertEquals(1, environment.errors.size());
    }

    @Test
    public void unavailableArgumentsDoNotEscape() throws Exception {
        // InstrumentationRegistry throws when no instrumentation is registered.
        environment.argumentsFailure = new IllegalStateException("No instrumentation registered!");

        runTests();

        assertNull(environment.attachedLibrary);
        assertEquals(0, environment.dumpCount);
        assertEquals(1, environment.errors.size());
    }

    @Test
    public void doesNotDumpIfTheAgentDidNotStart() throws Exception {
        // The agent loaded but never made CoverageTracker available, so dumping would throw
        // NoClassDefFoundError.
        environment.arguments.put("coverage-agent-config", CONFIG);
        environment.trackerLoaded = false;

        runTests();

        assertEquals("/data/local/tmp/coverage_agent.so", environment.attachedLibrary);
        assertEquals(0, environment.dumpCount);
    }

    @Test
    public void dumpErrorDoesNotEscape() throws Exception {
        environment.arguments.put("coverage-agent-config", CONFIG);
        environment.dumpFailure = new UnsatisfiedLinkError("dumpCoverageData");

        runTests();

        assertEquals(1, environment.dumpCount);
        assertEquals(1, environment.errors.size());
    }

    @Test
    public void doesNotAttachWhileTheOrchestratorListsTests() throws Exception {
        environment.arguments.put("coverage-agent-config", CONFIG);
        environment.arguments.put(CoverageAgentAttacher.LIST_TESTS_FOR_ORCHESTRATOR_KEY, "true");

        runTests();

        assertNull(environment.attachedLibrary);
        assertEquals(0, environment.dumpCount);
    }

    @Test
    public void doesNotAttachWithoutConfig() throws Exception {
        runTests();

        assertNull(environment.attachedLibrary);
        assertEquals(0, environment.dumpCount);
        assertFalse(environment.warnings.isEmpty());
    }

    @Test
    public void attachVirtualMachineErrorIsNotSwallowed() throws Exception {
        // Only failures that mean "no coverage" are caught. An OutOfMemoryError means the process
        // itself is broken, so it propagates.
        environment.arguments.put("coverage-agent-config", CONFIG);
        environment.attachFailure = new OutOfMemoryError("attach");

        assertThrows(OutOfMemoryError.class, () -> attacher.testRunStarted(Description.EMPTY));
    }

    @Test
    public void dumpVirtualMachineErrorIsNotSwallowed() throws Exception {
        environment.arguments.put("coverage-agent-config", CONFIG);
        environment.dumpFailure = new OutOfMemoryError("dump");
        attacher.testRunStarted(Description.EMPTY);

        assertThrows(OutOfMemoryError.class, () -> attacher.testRunFinished(new Result()));
    }

    /** Calls the listener the way the test runner does. Anything it throws fails the test. */
    private void runTests() throws Exception {
        attacher.testRunStarted(Description.EMPTY);
        attacher.testRunFinished(new Result());
    }

    private static final class FakeEnvironment implements CoverageAgentAttacher.Environment {
        int sdkInt = CoverageAgentAttacher.MIN_SDK_INT;
        final Map<String, String> arguments = new HashMap<>();
        RuntimeException argumentsFailure;
        Throwable attachFailure;
        boolean trackerLoaded = true;
        Error dumpFailure;

        String attachedLibrary;
        String attachedOptions;
        int dumpCount;
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public int sdkInt() {
            return sdkInt;
        }

        @Override
        public String argument(String key) {
            if (argumentsFailure != null) {
                throw argumentsFailure;
            }
            return arguments.get(key);
        }

        @Override
        public void attachJvmtiAgent(String library, String options) throws Exception {
            attachedLibrary = library;
            attachedOptions = options;
            if (attachFailure instanceof Error) {
                throw (Error) attachFailure;
            }
            if (attachFailure instanceof Exception) {
                throw (Exception) attachFailure;
            }
        }

        @Override
        public boolean isCoverageTrackerLoaded() {
            return trackerLoaded;
        }

        @Override
        public void dumpCoverageData() {
            dumpCount++;
            if (dumpFailure != null) {
                throw dumpFailure;
            }
        }

        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message, Throwable throwable) {
            errors.add(message);
        }
    }
}
