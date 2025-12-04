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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.ModelBuilderV2
import com.android.build.gradle.integration.common.fixture.ModelContainerV2
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ApkSubject.getBadging
import com.android.build.gradle.integration.common.utils.getSingleOutputFile
import com.android.build.gradle.integration.common.utils.getVariantByName
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Paths

/**
 * Tests for @{applicationId} placeholder presence in library manifest files. Such placeholders
 * should be left intact until the library is merged into a consuming application with a known
 * application Id.
 */
class ApplicationIdInLibsTest {
    @get:Rule
    val rule = GradleRule.configure().from {
        androidApplication {
            android {
                defaultConfig {
                    applicationId = "com.example.manifest_merger_example"
                }
                flavorDimensions += "foo"
                productFlavors {
                    create("flavor") {
                        it.applicationId = "com.example.manifest_merger_example.flavor"
                    }
                }
            }
            dependencies {
                api(project(":lib"))
            }
        }
        androidLibrary {
            files.update("src/main/AndroidManifest.xml").replaceWith(
                // language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                      android:versionCode="1"
                      android:versionName="1.0">
                    <permission
                        android:name="${'$'}{applicationId}.permission.C2D_MESSAGE"
                        android:protectionLevel="signature"/>
                    <uses-permission android:name="${'$'}{applicationId}.permission.C2D_MESSAGE"/>
                </manifest>
                """.trimIndent()
            )
        }
    }

    private val executor: GradleTaskExecutor
        get() = rule.build.executor

    private val modelV2: ModelBuilderV2
        get() = rule.build.modelBuilder

    @Test
    fun testLibPlaceholderSubstitutionInFinalApk() {
        val permissionName = "'com.example.manifest_merger_example.flavor.permission.C2D_MESSAGE'"

        executor.run("clean", "app:assembleDebug")
        val outputModels = modelV2.fetchModels("debug", null).container
        assertTrue(
            isPermissionPresent(
                outputModels,
                permissionName
            )
        )

        val newAppId = "com.example.manifest_merger_example.change"
        val newPermissionName = "'$newAppId.permission.C2D_MESSAGE'"

        rule.build.androidApplication().reconfigure {
            android {
                productFlavors.named("flavor") {
                    it.applicationId = newAppId
                }
            }
        }

        executor.run("clean", "app:assembleDebug")
        val newOutputModels = modelV2.fetchModels("debug", null).container
        assertFalse(
            isPermissionPresent(
                newOutputModels,
                permissionName
            )
        )
        assertTrue(
            isPermissionPresent(
                newOutputModels,
                newPermissionName
            )
        )
    }

    private fun isPermissionPresent(
        modelContainer: ModelContainerV2, permission: String
    ): Boolean {
        assertThat(modelContainer.infoMaps[":"]).containsKey(":app")

        val projectModel = modelContainer.getProject(":app", ":").androidProject!!

        val variantBuildOutputs = projectModel.variants
        assertThat(variantBuildOutputs).hasSize(2)

        // select the debug variant
        val debugBuildOutput = projectModel.getVariantByName("flavorDebug")
        val apk = Paths.get(debugBuildOutput.getSingleOutputFile())
        val apkBadging = getBadging(apk)

        return apkBadging.any { line -> line.contains("uses-permission: name=$permission") }
    }
}