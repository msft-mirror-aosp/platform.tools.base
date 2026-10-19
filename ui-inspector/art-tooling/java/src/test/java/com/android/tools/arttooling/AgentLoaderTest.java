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

package com.android.tools.arttooling;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@RunWith(RobolectricTestRunner.class)
public final class AgentLoaderTest {

    /**
     * Attaching an agent needs API 28. Robolectric's default SDK here is older, and its
     * DexClassLoader rejects the null optimized directory that AgentLoader passes.
     */
    private static final int ATTACH_SDK = 28;

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void attachRejectsMissingAgentDex() {
        String missingPath = new File(temporaryFolder.getRoot(), "missing.jar").getPath();

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AgentLoader.attach(missingPath, "com.example.Agent", "options", 1L));

        assertThat(exception).hasMessageThat().contains(missingPath);
    }

    @Test
    public void attachRejectsDirectoryAsAgentDex() throws Exception {
        String directoryPath = temporaryFolder.newFolder("agent").getPath();

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentLoader.attach(directoryPath, "com.example.Agent", "options", 1L));
    }

    @Test
    public void rejectedAttachDoesNotPublishTheEngineHandle() {
        String missingPath = new File(temporaryFolder.getRoot(), "missing.jar").getPath();

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentLoader.attach(missingPath, "com.example.Agent", "options", 1L));

        assertThrows(IllegalStateException.class, () -> ArtTooling.findInstances(String.class));
    }

    @Test
    @Config(sdk = ATTACH_SDK)
    public void startAgentReusesTheClassLoaderForTheSamePath() throws Exception {
        String agentDex = temporaryFolder.newFile("agent.jar").getPath();
        AtomicInteger parentLookups = new AtomicInteger();
        Supplier<ClassLoader> appClassLoader = countingAppClassLoader(parentLookups);
        RecordingAgent.instances.clear();

        AgentLoader.startAgent(agentDex, RecordingAgent.class.getName(), "first", appClassLoader);
        AgentLoader.startAgent(agentDex, RecordingAgent.class.getName(), "second", appClassLoader);

        assertThat(parentLookups.get()).isEqualTo(1);
        assertThat(RecordingAgent.instances).hasSize(2);
        assertThat(RecordingAgent.instances.get(0)).isNotSameAs(RecordingAgent.instances.get(1));
        assertThat(RecordingAgent.instances.get(0).options).isEqualTo("first");
        assertThat(RecordingAgent.instances.get(1).options).isEqualTo("second");
    }

    @Test
    @Config(sdk = ATTACH_SDK)
    public void startAgentCreatesAClassLoaderForEachPath() throws Exception {
        String firstAgentDex = temporaryFolder.newFile("first.jar").getPath();
        String secondAgentDex = temporaryFolder.newFile("second.jar").getPath();
        AtomicInteger parentLookups = new AtomicInteger();
        Supplier<ClassLoader> appClassLoader = countingAppClassLoader(parentLookups);

        AgentLoader.startAgent(
                firstAgentDex, RecordingAgent.class.getName(), "options", appClassLoader);
        AgentLoader.startAgent(
                secondAgentDex, RecordingAgent.class.getName(), "options", appClassLoader);

        assertThat(parentLookups.get()).isEqualTo(2);
    }

    @Test
    @Config(sdk = ATTACH_SDK)
    public void startAgentRetriesAPathWhoseAppClassLoaderWasMissing() throws Exception {
        String agentDex = temporaryFolder.newFile("agent.jar").getPath();

        assertThrows(
                IllegalStateException.class,
                () ->
                        AgentLoader.startAgent(
                                agentDex, RecordingAgent.class.getName(), "options", () -> null));

        AtomicInteger parentLookups = new AtomicInteger();
        AgentLoader.startAgent(
                agentDex,
                RecordingAgent.class.getName(),
                "options",
                countingAppClassLoader(parentLookups));

        assertThat(parentLookups.get()).isEqualTo(1);
    }

    /**
     * Supplies the test's class loader, which can load {@link RecordingAgent}, and counts calls.
     */
    private static Supplier<ClassLoader> countingAppClassLoader(AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return AgentLoaderTest.class.getClassLoader();
        };
    }

    /** An agent that records each instance and the options it received. */
    public static final class RecordingAgent implements Agent {
        static final List<RecordingAgent> instances = new ArrayList<>();

        String options;

        public RecordingAgent() {
            instances.add(this);
        }

        @Override
        public void onAttach(String options) {
            this.options = options;
        }
    }
}
