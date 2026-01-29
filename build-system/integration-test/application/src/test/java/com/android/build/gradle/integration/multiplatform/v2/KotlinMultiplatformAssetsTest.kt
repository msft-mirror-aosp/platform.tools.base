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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.readText

class KotlinMultiplatformAssetsTest {
    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .create()

    @Before
    fun setUp() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                kotlin.androidLibrary {
                    androidResources {
                        enable = true
                    }
                }
            """.trimIndent()
        )

        FileUtils.writeToFile(
            project.getSubproject("kmpFirstLib").file("src/androidMain/assets/something.json"),
            """
                {
                  "id": 123,
                  "name": "Example Item",
                  "value": 42.5
                }
            """.trimIndent()
        )
    }

    @Test
    fun testKmpLibraryAssetPackageTasksNotExecutedWhenResourcesDisabled() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                kotlin.android {
                    androidResources {
                        enable = false
                    }
                }
            """.trimIndent()
        )

        val result = executor().run(":kmpFirstLib:assemble")
        Truth.assertThat(result.didWorkTasks).doesNotContain(
            listOf(
                ":kmpFirstLib:mergeAndroidMainAssets"
            )
        )
    }

    @Test
    fun testKmpLibraryAssetPackageTasksExecuted() {
        val result = executor().run(":kmpFirstLib:assemble")
        Truth.assertThat(result.didWorkTasks).containsAtLeastElementsIn(
            listOf(
                ":kmpFirstLib:mergeAndroidMainAssets"
            )
        )

        project.getSubproject("kmpFirstLib").assertAar(AarSelector.NO_BUILD_TYPE) {
            assets().resourceAsText("something.json").isEqualTo(
                """
                   {
                     "id": 123,
                     "name": "Example Item",
                     "value": 42.5
                   }
                """.trimIndent()
            )
        }
    }

    @Test
    fun testKmpLibraryAssetPackageTasksExecuted_enabledInLegacyWay() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                kotlin.android {
                    androidResources {
                        enable = false
                    }
                    experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true
                }
            """.trimIndent()
        )

        val result = executor().run(":kmpFirstLib:assemble")
        Truth.assertThat(result.didWorkTasks).containsAtLeastElementsIn(
            listOf(
                ":kmpFirstLib:mergeAndroidMainAssets"
            )
        )

        project.getSubproject("kmpFirstLib").assertAar(AarSelector.NO_BUILD_TYPE) {
            assets().resourceAsText("something.json").isEqualTo(
                """
                   {
                     "id": 123,
                     "name": "Example Item",
                     "value": 42.5
                   }
                """.trimIndent()
            )
        }
    }

    @Test
    fun testAppConsumingKmpLibrary() {
        executor().run(":app:assembleDebug")

        project.getSubproject("app").getApk(GradleTestProject.ApkType.DEBUG).use { apk ->
            Truth.assertThat(apk.getEntry("assets/something.json").readText()).isEqualTo(
                """
                   {
                     "id": 123,
                     "name": "Example Item",
                     "value": 42.5
                   }
                """.trimIndent()
            )
        }
    }


    private fun executor() = project.executor().withFailOnWarning(false) // b/455891987
}
