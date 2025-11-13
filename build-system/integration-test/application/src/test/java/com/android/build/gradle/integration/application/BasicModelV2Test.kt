/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.builder.model.v2.ide.SyncIssue
import com.android.builder.model.v2.models.ModelBuilderParameter
import org.junit.Rule
import org.junit.Test

class BasicModelV2Test: ModelComparator() {
    @get:Rule
    val rule = GradleRule.fromProject(BasicSpec())

    @Test
    fun `test models`() {
        val result = rule
            .build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        with(result).compareBasicAndroidProject(
            projectAction = { getProject(":app") },
            goldenFile = "basicAndroidProject"
        )
        with(result).compareAndroidProject(
            projectAction = { getProject(":app") },
            goldenFile = "testProject"
        )
        with(result).compareAndroidDsl(
            projectAction = { getProject(":app") },
            goldenFile = "AndroidDsl"
        )
        with(result).compareVariantDependencies(
            projectAction = { getProject(":app") },
            goldenFile = "testDep"
        )
    }

    @Test
    fun `test models no java runtime classpath`() {
        val result = rule
            .build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(
                variantName = "debug",
                parameterMutator = buildOnlyTestRuntimeClasspaths(
                    buildUnitTestsRuntime = false,
                    buildScreenshotTestsRuntime = false
                )
            )

        with(result).compareBasicAndroidProject(
            projectAction = { getProject(":app") },
            goldenFile = "basicAndroidProject"
        )
        with(result).compareAndroidProject(
            projectAction = { getProject(":app") },
            goldenFile = "testProject"
        )
        with(result).compareAndroidDsl(
            projectAction = { getProject(":app") },
            goldenFile = "AndroidDsl"
        )
        with(result).compareVariantDependencies(
            projectAction = { getProject(":app") },
            goldenFile = "testDepAndroidRuntime"
        )
    }

    @Test
    fun `test models no main runtime classpath, but screenshot and unit tests are present`() {
        val result = rule
            .build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(
                variantName = "debug",
                parameterMutator = buildOnlyTestRuntimeClasspaths(
                    buildUnitTestsRuntime = true,
                    buildScreenshotTestsRuntime = true
                )
            )

        with(result).compareBasicAndroidProject(
            projectAction = { getProject(":app") },
            goldenFile = "basicAndroidProject"
        )
        with(result).compareAndroidProject(
            projectAction = { getProject(":app") },
            goldenFile = "testProject"
        )
        with(result).compareAndroidDsl(
            projectAction = { getProject(":app") },
            goldenFile = "AndroidDsl"
        )
        with(result).compareVariantDependencies(
            projectAction = { getProject(":app") },
            goldenFile = "testDepAndroidRuntimeWithScreenShotAndUnit"
        )
    }
}

fun buildOnlyTestRuntimeClasspaths(
    buildUnitTestsRuntime: Boolean,
    buildScreenshotTestsRuntime: Boolean
): (ModelBuilderParameter) -> Unit = {
    it.dontBuildRuntimeClasspath = true
    it.dontBuildUnitTestRuntimeClasspath = !buildUnitTestsRuntime
    it.dontBuildScreenshotTestRuntimeClasspath = !buildScreenshotTestsRuntime
    it.dontBuildAndroidTestRuntimeClasspath = false
    it.dontBuildTestFixtureRuntimeClasspath = true
    it.dontBuildHostTestRuntimeClasspath = mapOf(
        "UnitTest" to it.dontBuildUnitTestRuntimeClasspath,
        "ScreenshotTest" to it.dontBuildScreenshotTestRuntimeClasspath
    )
    it.additionalArtifactsInModel = true
}
