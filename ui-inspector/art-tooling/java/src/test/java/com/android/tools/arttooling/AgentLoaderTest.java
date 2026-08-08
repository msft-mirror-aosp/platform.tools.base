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

import java.io.File;

@RunWith(RobolectricTestRunner.class)
public final class AgentLoaderTest {

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
}
