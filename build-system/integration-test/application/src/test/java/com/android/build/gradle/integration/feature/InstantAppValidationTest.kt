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

package com.android.build.gradle.integration.feature

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryInternalArtifactType.MERGED_MANIFEST
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class InstantAppValidationTest {

    @get:Rule
    val project = GradleRule.from {
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

            android {
                namespace = "com.example.baseModule"
                dynamicFeatures += listOf(DEFAULT_FEATURE_PATH)
                defaultConfig {
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    minSdk = 19
                }
            }
        }
        androidFeature(DEFAULT_FEATURE_PATH) {
            dependencies {
                implementation(project(":app"))
            }
        }
            .files.update("src/main/AndroidManifest.xml").replaceWith(
                //language=xml
                """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:dist="http://schemas.android.com/apk/distribution">
                             <dist:module dist:instant="true" />
                        </manifest>
                    """.trimIndent()
            )

    }

    @Test
    fun testInstantAppWarning() {
        val build = project.build
        val result = build.executor.run(":app:assemble")
        result.assertOutputContains("is declared as Instant App")
        result.assertOutputContains("Instant Apps support will be removed by Google Play in December 2025. ")
    }

    @Test
    fun testNoInstantAppWarning() {
        val build = project.build
        build.androidFeature().files.update("src/main/AndroidManifest.xml").replaceWith(
            //language=xml
            """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:dist="http://schemas.android.com/apk/distribution">
                           <dist:module dist:onDemand="true" dist:title="ABC">
                                  <dist:fusing dist:include="true" />
                           </dist:module>
                        </manifest>
                    """.trimIndent()
        )
        val result = build.executor.run(":app:assemble")
        result.assertOutputDoesNotContain("is declared as Instant App")
        result.assertOutputDoesNotContain("Instant Apps support will be removed by Google Play in December 2025. ")
        val feature = build.androidFeature()
        val mergedManifestFile = feature.resolve(MERGED_MANIFEST)
            .resolve("debug/processDebugMainManifest/AndroidManifest.xml")
            .toFile()
        Truth.assertThat(mergedManifestFile.readText()).contains("dist:title=\"ABC\"")
    }
}
