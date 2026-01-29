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
import com.android.build.gradle.integration.multiplatform.model.fixture.KmpModelComparator
import com.android.build.gradle.integration.multiplatform.fixture.publishLibs
import com.android.utils.FileUtils
import org.junit.Rule
import org.junit.Test
import java.io.File

class KotlinMultiplatformPublicationModelSnapshotTest: BaseModelComparator {

    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .create()

    @Test
    fun testModelsWhenLibsArePublished() {
        project.publishLibs()

        val moduleFilesComparator = KmpModelComparator(
            project = project,
            testClass = this,
            modelSnapshotTask = "publish",
            taskOutputsLocator = { projectPath ->
                val projectName = projectPath.removePrefix(":")
                listOf(
                    FileUtils.join(
                        project.projectDir,
                        "testRepo",
                        "com",
                        "example",
                        projectName,
                        "1.0",
                        "$projectName-1.0.module"
                    )
                )
            },
        )

        moduleFilesComparator.fetchAndCompareModels(
            projects = listOf(":kmpJvmOnly", ":kmpSecondLib", ":kmpLibraryPlugin", ":kmpFirstLib")
        )

        val sourceSetsComparator = KmpModelComparator(
            project = project,
            testClass = this,
            modelSnapshotTask = "dumpSourceSetDependencies",
            taskOutputsLocator = { projectPath ->
                FileUtils.join(
                    project.getSubproject(projectPath).buildDir,
                    "ide",
                    "dependencies",
                    "json"
                ).listFiles()!!.toList()
            },
        )

        sourceSetsComparator.fetchAndCompareModels(
            projects = listOf(":kmpFirstLib")
        )

        val pomFilesComparator = KmpModelComparator(
            project = project,
            testClass = this,
            modelSnapshotTask = "publish",
            taskOutputsLocator = { projectPath ->
                val projectName = projectPath.removePrefix(":")
                val matchingPoms = mutableListOf<File>()
                FileUtils.join(
                    project.projectDir,
                    "testRepo",
                    "com",
                    "example"
                ).listFiles()?.forEach { childFile ->
                    if (childFile.isDirectory && childFile.name.startsWith(projectName)) {
                        childFile.walkTopDown()
                            .filter { it.isFile && it.extension.equals("pom", ignoreCase = true) }
                            .toCollection(matchingPoms)
                    }
                }
                matchingPoms
            },
        )

        pomFilesComparator.fetchAndComparePomModels(
            projects = listOf(":kmpJvmOnly", ":kmpSecondLib", ":kmpLibraryPlugin", ":kmpFirstLib")
        )
    }
}
