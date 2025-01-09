/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.internal.tasks.AarMetadataReader
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.tasks.FusedLibraryMergeArtifactTask
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** Tests for [FusedLibraryMergeArtifactTask] */
internal class FusedLibraryMergeArtifactTaskTest {

    @get:Rule
    val rule = GradleRule.configure().from {
        // Library dependency at depth 1 with no dependencies.
        androidLibrary(":androidLib1") {
            android {
                namespace = "com.example.androidLib1"
                defaultConfig {
                    minSdk = 12
                    renderscriptTargetApi = 18
                    renderscriptSupportModeEnabled = true
                    aarMetadata {
                        minCompileSdk = 12
                        minAgpVersion = "3.0.0"
                        minCompileSdkExtension = 2
                    }
                }
                buildFeatures {
                    renderScript = true
                }
            }
            files {
                add("src/main/assets/android_lib_one_asset.txt", "androidLib1")
                add("src/main/assets/subdir/android_lib_one_asset_in_subdir.txt", "androidLib1")
                add("src/main/resources/my_java_resource.txt", "androidLib1")
                add(
                    "src/main/rs/com/example/androidLib1/ip.rsh",
                    """
                        #pragma version(1)
                        #pragma rs java_package_name(com.android.rs.image2)
                    """.trimIndent()
                )
                add(
                    "src/main/rs/com/example/androidLib1/copy.rs",
                    """
                        #include "ip.rsh"

                        uchar4 __attribute__((kernel)) root(uchar4 v_in) {
                            return v_in;
                        }
                    """.trimIndent()
                )
            }
            }
        // Library dependency at depth 0 with a dependency on androidLib1.
        androidLibrary(":androidLib2") {
            android {
                namespace = "com.example.androidLib2"
                defaultConfig.minSdk = 19
            }
            dependencies {
                implementation(project(":androidLib1"))
            }
            files.add("src/main/assets/android_lib_two_asset.txt", "androidLib2")
        }
        // Library dependency at depth 0 with no dependencies
        androidLibrary(":androidLib3") {
            android {
                namespace = "com.example.androidLib3"
                defaultConfig {
                    minSdk = 18
                    aarMetadata {
                        minCompileSdk = 18
                        minAgpVersion = "4.0.1"
                    }
                }
            }
            dependencies {
                implementation(project(":androidLib1"))
            }
        }
        fusedLibrary(":fusedLib1") {
            androidFusedLibrary {
                namespace = "com.example.fusedLib1"
                minSdk = 19
            }
            dependencies {
                include(project(":androidLib3"))
                include(project(":androidLib2"))
                include(project(":androidLib1"))
            }
        }
        gradleProperties {
            add(BooleanOption.FUSED_LIBRARY_SUPPORT, true)
        }
    }

    @Test
    fun testAarMetadataMerging() {
        val build = rule.build
        build.executor.run(":fusedLib1:assemble")

        val fusedLib1 = build.fusedLibrary(":fusedLib1")
        fusedLib1.withAar(AarSelector.NO_BUILD_TYPE) {
            val metadataContents =
                getEntryAsFile("META-INF/com/android/build/gradle/aar-metadata.properties")
            val aarMetadataReader = AarMetadataReader(metadataContents.toFile())
            // Value constant from AGP
            assertThat(aarMetadataReader.aarFormatVersion).isEqualTo("1.0")
            // Value constant from AGP
            assertThat(aarMetadataReader.aarMetadataVersion).isEqualTo("1.0")
            // Value from androidLib3
            assertThat(aarMetadataReader.minAgpVersion).isEqualTo("4.0.1")
            // Value from androidLib3
            assertThat(aarMetadataReader.minCompileSdk).isEqualTo("18")
            // Value from androidLib1
            assertThat(aarMetadataReader.minCompileSdkExtension).isEqualTo("2")
        }

        fusedLib1.reconfigure {
            androidFusedLibrary {
                aarMetadata.minAgpVersion = "8.4-alpha02"
                aarMetadata.minCompileSdk = 9
            }
        }
        build.executor.run(":fusedLib1:assemble")

        fusedLib1.withAar(AarSelector.NO_BUILD_TYPE) {
            val metadataContents =
                getEntryAsFile("META-INF/com/android/build/gradle/aar-metadata.properties")
            val aarMetadataReader = AarMetadataReader(metadataContents.toFile())
            // Value constant from AGP
            assertThat(aarMetadataReader.aarFormatVersion).isEqualTo("1.0")
            // Value constant from AGP
            assertThat(aarMetadataReader.aarMetadataVersion).isEqualTo("1.0")
            // Value from aarMetadata DSL
            assertThat(aarMetadataReader.minAgpVersion).isEqualTo("8.4-alpha02")
            // Value from aarMetadata DSL
            assertThat(aarMetadataReader.minCompileSdk).isEqualTo("9")
            // Default value
            assertThat(aarMetadataReader.minCompileSdkExtension).isEqualTo("0")
        }

    }

    @Test
    fun testAssetsMergingWithDuplicateAssets() {
        val build = rule.build
        val fusedLib1 = build.fusedLibrary(":fusedLib1")
        val androidLib3 = build.androidLibrary(":androidLib3")

        // Adds duplicate asset file in androidLib3, which should override the asset in androidLib1,
        // as androidLib3 is declared as a dependency in fusedLib1 before androidLib1 transitively
        // through androidLib2.
        androidLib3.files.add("src/main/assets/android_lib_one_asset.txt", "androidLib3")

        build.executor.run(":fusedLib1:assemble")

        fusedLib1.assertAar(AarSelector.NO_BUILD_TYPE) {
            containsFileWithContent("assets/android_lib_one_asset.txt", "androidLib3")
            contains(listOf(
                "assets/android_lib_one_asset.txt",
                "assets/android_lib_two_asset.txt",
                "assets/subdir/android_lib_one_asset_in_subdir.txt"
            ))
        }
    }

    @Test
    fun testRenderscriptCreatedJniCopiesToFusedLibrary() {
       val build = rule.build
        val fusedLib1 = build.fusedLibrary(":fusedLib1")
        build.executor.run(":fusedLib1:assemble")

        fusedLib1.assertAar(AarSelector.NO_BUILD_TYPE) {
            contains(listOf(
                "jni/armeabi-v7a/librsjni_androidx.so",
                "jni/armeabi-v7a/libRSSupport.so",
                "jni/armeabi-v7a/librsjni.so",
                "jni/armeabi-v7a/librs.copy.so",
                "jni/x86_64/librsjni_androidx.so",
                "jni/x86_64/libRSSupport.so",
                "jni/x86_64/librsjni.so",
                "jni/arm64-v8a/librsjni_androidx.so",
                "jni/arm64-v8a/libRSSupport.so",
                "jni/arm64-v8a/librsjni.so",
                "jni/x86/librsjni_androidx.so",
                "jni/x86/libRSSupport.so",
                "jni/x86/librsjni.so",
                "jni/x86/librs.copy.so"
            ))
        }
    }

    @Test
    fun testJavaResourcesMerge() {
        val build = rule.build
        val fusedLib1 = build.fusedLibrary(":fusedLib1")
        build.executor.run(":fusedLib1:assemble")

        fusedLib1.assertAar(AarSelector.NO_BUILD_TYPE) {
            containsJavaResource("my_java_resource.txt")
        }

        val androidLib2 = build.androidLibrary(":androidLib2")
        androidLib2.files.add("src/main/resources/my_java_resource.txt", "content")

        val result = build.executor.expectFailure().run(":fusedLib1:assemble")
        result.stderr.use { out ->
            assertThat(out).contains("2 files found with path 'my_java_resource.txt'")
        }
    }
}
