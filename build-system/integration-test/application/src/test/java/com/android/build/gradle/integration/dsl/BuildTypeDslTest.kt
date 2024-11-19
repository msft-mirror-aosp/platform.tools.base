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

package com.android.build.gradle.integration.dsl

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.getOutputDir
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class BuildTypeDslTest {

    private val app = MinimalSubProject.app("com.example.baseModule")

    @get:Rule
    val project = GradleTestProject.builder().fromTestApp(app).create()

    // Regression test for b/307784512
    @Test
    fun testBuildTypeInitWithPostProcessingBlock() {
        val proguardFile = project.file("proguard-rules.pro")
        proguardFile.createNewFile()
        proguardFile.writeText("-dontwarn com.google.apps.SuppressViolation")
        TestFileUtils.appendToFile(
            project.buildFile,
            """
                android {
                    buildTypes {
                        release {
                            postprocessing {
                                removeUnusedCode true
                                optimizeCode true
                                obfuscate true
                                removeUnusedResources true
                                proguardFiles file("proguard-rules.pro")
                            }
                        }
                        secondRelease {
                            initWith(release)
                        }
                    }
                }
            """.trimIndent()
        )
        project.execute("assembleRelease", "assembleSecondRelease")
        val releaseConfigurationFile = project.getOutputFile(
            "mapping",
            "release",
            "configuration.txt"
        )
        Truth.assertThat(releaseConfigurationFile.readText()).contains("SuppressViolation")
        val secondReleaseConfigurationFile = project.getOutputFile(
            "mapping",
            "secondRelease",
            "configuration.txt"
        )
        Truth.assertThat(secondReleaseConfigurationFile.readText()).contains("SuppressViolation")
    }

    // Regression test for b/379125947
    @Test
    fun testBuildTypeInitWithShrinkResources() {
        TestFileUtils.appendToFile(
            project.buildFile,
            """
                android {
                    buildTypes {
                        release {
                            shrinkResources true
                            minifyEnabled true
                        }
                        secondRelease {
                            initWith(release)
                        }
                    }
                }
            """.trimIndent()
        )
        project.executor().run("assembleRelease", "assembleSecondRelease")
        Truth.assertThat(
            InternalArtifactType.SHRUNK_RESOURCES_PROTO_FORMAT.getOutputDir(project.buildDir)
                .resolve("secondRelease").exists()
        ).isTrue()
    }
}
