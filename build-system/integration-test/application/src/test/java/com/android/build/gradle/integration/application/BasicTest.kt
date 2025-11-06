/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.category.SmokeTests
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.builder
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Assemble tests for basic.
 */
@Category(SmokeTests::class)
class BasicTest {

    @get:Rule
    val project: GradleTestProject = builder()
        .fromTestProject("basic")
        .create()

    @Test
    fun weDontFailOnLicenceDotTxtWhenPackagingDependencies() {
        project.execute("assembleAndroidTest")
    }

    @Test
    @Throws(Exception::class)
    fun testRenderscriptDidNotRun() {
        // First enable renderscript, then execute renderscript task and check if it was skipped
        TestFileUtils.appendToFile(
            project.buildFile, "android.buildFeatures.renderScript = true"
        )
        val result: GradleBuildResult = project.execute("compileDebugRenderscript")
        Truth.assertThat(result.getTask(":compileDebugRenderscript").executionState.toString())
            .isEqualTo("SKIPPED")
    }
}
