/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.multiplatform.model

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.model.BaseModelComparator
import com.android.utils.FileUtils
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformMetadataSnapshotTest: BaseModelComparator {

    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .disableBuiltInKotlin()
        .create()

    @Test
    fun testKotlinToolingMetadataOutput() {
        KmpModelComparator(
            project = project,
            testClass = this,
            modelSnapshotTask = "buildKotlinToolingMetadata",
            taskOutputsLocator = { projectPath ->
                FileUtils.join(
                    project.getSubproject(projectPath).buildDir,
                    "kotlinToolingMetadata",
                ).listFiles()!!.toList()
            },
        ).fetchAndCompareModels(listOf(":kmpFirstLib", ":kmpSecondLib"))
    }

    @Test
    fun testKotlinProjectStructureMetadataOutput() {
        KmpModelComparator(
            project = project,
            testClass = this,
            modelSnapshotTask = "generateProjectStructureMetadata",
            taskOutputsLocator = { projectPath ->
                FileUtils.join(
                    project.getSubproject(projectPath).buildDir,
                    "kotlinProjectStructureMetadata",
                ).listFiles()!!.toList()
            },
        ).fetchAndCompareModels(listOf(":kmpFirstLib", ":kmpSecondLib"))
    }
}
