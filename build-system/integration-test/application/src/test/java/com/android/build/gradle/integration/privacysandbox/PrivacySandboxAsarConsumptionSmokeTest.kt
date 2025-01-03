/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.privacysandbox

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.LibraryType
import com.android.testutils.MavenRepoGenerator
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class PrivacySandboxAsarConsumptionSmokeTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            library(MavenRepoGenerator.Library(
                "com.example:externalasar:1",
                "asar",
                byteArrayOf(),
            ))
        }.from {
            androidApplication {
                android {
                    namespace = "com.example.privacysandboxsdk.consumer"
                    defaultConfig.minSdk = 14
                    privacySandbox {
                        enable = false
                    }
                }
                dependencies {
                    implementation("com.example:externalasar:1")
                }
            }
            gradleProperties {
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }

    @Test
    fun testDependencyWithoutSupportEnabled() {
        val build = rule.build

        val result = build.executor.expectFailure().run(":app:assembleDebug")
        assertThat(result.stderr).contains("Dependency com.example:externalasar:1 is an Android Privacy Sandbox SDK library")

        val models = build.modelBuilder.fetchModels(variantName = "debug")
        val variantDependencies = models.container.getProject(":app").variantDependencies ?: error("Expected variant dependencies model to build")
        val compileDependencies = variantDependencies.mainArtifact.compileDependencies
        assertThat(compileDependencies).hasSize(1)
        val library = variantDependencies.libraries[compileDependencies.single().key] ?: error("Inconsistent model: failed to load compile library")
        assertThat(library.type).isEqualTo(LibraryType.NO_ARTIFACT_FILE)
        assertThat(library.libraryInfo?.name).isEqualTo("externalasar")
    }
}
