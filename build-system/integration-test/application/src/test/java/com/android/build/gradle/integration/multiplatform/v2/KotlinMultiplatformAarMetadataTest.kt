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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidKotlinMultiplatformLibraryComponentCallback
import com.android.build.gradle.integration.common.output.AarMetadataSubject
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.tasks.AarMetadataTask
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformAarMetadataTest {
    @get:Rule
    val rule = GradleRule.from {
        androidKotlinMultiplatformLibrary(":shared") { }
    }

    @Test
    fun testBasic() {
        rule.build.executor.run(":shared:assembleAndroidMain")
        rule.build.kotlinMultiplatformLibrary(":shared").assertAar(AarSelector.NO_BUILD_TYPE) {
            aarMetadata {
                formatVersion().isEqualTo("1.0")
                metadataVersion().isEqualTo("1.0")
                // todo: fix this in follow up CL as part of the same ticket - this is wrong
                // it should default to the compileSdk
                minCompileSdk().isEqualTo("1")
                minAgpVersion().isEqualTo("1.0.0")
                minCompileSdkExtension().isEqualTo("0")
                coreLibraryDesugaringEnabled().isEqualTo("false")
                desugarJdkLibId().isNull()
            }
        }
    }

    @Test
    fun testDsl() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":shared") {
                android {
                    aarMetadata.minCompileSdk = 27
                    aarMetadata.minAgpVersion = "3.0.0"
                    aarMetadata.minCompileSdkExtension = 2
                }
            }
        }

        build.executor.run(":shared:assembleAndroidMain")
        build.kotlinMultiplatformLibrary(":shared").assertAar(AarSelector.NO_BUILD_TYPE) {
            aarMetadata {
                minCompileSdk().isEqualTo("27")
                minAgpVersion().isEqualTo("3.0.0")
                minCompileSdkExtension().isEqualTo("2")
                coreLibraryDesugaringEnabled().isEqualTo("false")
                desugarJdkLibId().isNull()
            }
        }
    }

    @Test
    fun testCompileSdkPreview() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":shared") {
                android {
                    compileSdkPreview = "Tiramisu"
                }
            }
        }

        build.executor.run(":shared:writeAndroidMainAarMetadata")

        val aarMetadataFile = build.kotlinMultiplatformLibrary(":shared")
            .resolve(InternalArtifactType.AAR_METADATA)
            .resolve("androidMain/writeAndroidMainAarMetadata/${AarMetadataTask.AAR_METADATA_FILE_NAME}")

        AarMetadataSubject.assertThat(aarMetadataFile) {
            forceCompileSdkPreview().isEqualTo("Tiramisu")
        }
    }

    @Test
    fun testVariantApi() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":shared") {
                android {
                    aarMetadata.minCompileSdk = 26
                    aarMetadata.minAgpVersion = "2.0.0"
                    aarMetadata.minCompileSdkExtension = 1
                }
                pluginCallbacks += Callback::class.java
            }
        }

        build.executor.run(":shared:assembleAndroidMain")
        build.kotlinMultiplatformLibrary(":shared").assertAar(AarSelector.NO_BUILD_TYPE) {
            aarMetadata {
                minCompileSdk().isEqualTo("27")
                minAgpVersion().isEqualTo("3.0.0")
                minCompileSdkExtension().isEqualTo("2")
                coreLibraryDesugaringEnabled().isEqualTo("false")
                desugarJdkLibId().isNull()
            }
        }
    }

    class Callback: AndroidKotlinMultiplatformLibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            extension: KotlinMultiplatformAndroidComponentsExtension
        ) {
            extension.apply {
                onVariants(selector().all()) {
                    it.aarMetadata.minCompileSdk.set(27)
                    it.aarMetadata.minAgpVersion.set("3.0.0")
                    it.aarMetadata.minCompileSdkExtension.set(2)
                }
            }
        }
    }


}
