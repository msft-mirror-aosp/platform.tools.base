/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.declarative

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class GradleDeclarativeTest {
    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("gradleDeclarative")
        .create()

    @Test
    fun testLibraryAssembles() {
        TestFileUtils.searchAndReplace(project.settingsFile, "dcl_plugin_version", com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION)
        project.executor().run(":lib:assemble")
        val debugAar = project.getSubproject("lib").getOutputFile("aar", "lib-debug.aar")
        val releaseAar = project.getSubproject("lib").getOutputFile("aar", "lib-release.aar")

        assertThat(debugAar.exists()).isTrue()
        assertThat(releaseAar.exists()).isTrue()
    }
}
