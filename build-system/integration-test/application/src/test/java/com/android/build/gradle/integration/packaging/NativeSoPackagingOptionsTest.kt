/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.build.gradle.integration.packaging

import com.android.build.api.variant.JniLibsPackaging
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import org.junit.Rule
import org.junit.Test
import java.util.zip.ZipEntry

/**
 * Integration tests for [JniLibsPackaging]
 */
class NativeSoPackagingOptionsTest {

    val LIB_CONTENT_FOO = "foo".toByteArray()
    val LIB_CONTENT_BAR = "bar".toByteArray()

    private val app =
        MinimalSubProject.app("com.example.app")
            .appendToBuild(
                """
                    android {
                        packagingOptions {
                            jniLibs {
                                // test various supported pattern formats
                                excludes += '**/dslExclude1.so'
                                excludes += 'lib/*/dslExclude2.so'
                                excludes += '/lib/*/dslExclude3.so'
                                pickFirsts += '**/dslPickFirst.so'
                                useLegacyPackaging = true
                            }
                        }
                    }
                    androidComponents {
                        onVariants(selector().withName('debug'), {
                            packaging.jniLibs.excludes.add('**/debugExclude.so')
                        })
                        onVariants(selector().withName('release'), {
                            packaging.jniLibs.excludes.add('**/releaseExclude.so')
                            packaging.jniLibs.useLegacyPackaging.set(false)
                        })
                        onVariants(selector().all(), {
                            packaging.jniLibs.pickFirsts.add('**/variantPickFirst.so')
                            androidTest?.packaging?.jniLibs?.excludes?.add('**/testExclude.so')
                        })
                    }
                    """.trimIndent()
            ).withFile("src/main/jniLibs/x86/appKeep.so", LIB_CONTENT_FOO)
            .withFile("src/main/jniLibs/x86/dslExclude1.so", LIB_CONTENT_FOO)
            .withFile("src/main/jniLibs/x86/dslExclude2.so", LIB_CONTENT_FOO)
            .withFile("src/main/jniLibs/x86/dslExclude3.so", LIB_CONTENT_FOO)
            .withFile("src/main/jniLibs/x86/dslPickFirst.so", LIB_CONTENT_FOO)
            .withFile("src/main/jniLibs/x86_64/dslPickFirst.so", LIB_CONTENT_FOO)
            .withFile("src/main/jniLibs/x86/debugExclude.so", LIB_CONTENT_FOO)
            .withFile("src/main/jniLibs/x86/releaseExclude.so", LIB_CONTENT_FOO)
            .withFile("src/main/jniLibs/x86/variantPickFirst.so", LIB_CONTENT_FOO)
            .withFile("src/main/jniLibs/x86_64/variantPickFirst.so", LIB_CONTENT_FOO)
            .withFile("src/androidTest/jniLibs/x86/testKeep.so", LIB_CONTENT_FOO)
            .withFile("src/androidTest/jniLibs/x86/testExclude.so", LIB_CONTENT_FOO)

    private val lib =
        MinimalSubProject.lib("com.example.lib")
            .appendToBuild(
                """
                    android {
                        packagingOptions {
                            jniLibs {
                                // test various supported pattern formats
                                excludes += '**/dslExclude1.so'
                                excludes += 'lib/*/dslExclude2.so'
                                excludes += '/lib/*/dslExclude3.so'
                            }
                        }
                    }
                    androidComponents {
                        onVariants(selector().all(), {
                            packaging.jniLibs.excludes.add('**/libExclude.so')
                            androidTest?.packaging?.jniLibs?.excludes?.add('**/testExclude.so')
                        })
                    }
                    """.trimIndent()
            ).withFile("src/main/jniLibs/x86/libKeep.so", LIB_CONTENT_BAR)
            .withFile("src/main/jniLibs/x86/dslExclude1.so", LIB_CONTENT_BAR)
            .withFile("src/main/jniLibs/x86/dslExclude2.so", LIB_CONTENT_BAR)
            .withFile("src/main/jniLibs/x86/dslExclude3.so", LIB_CONTENT_BAR)
            .withFile("src/main/jniLibs/x86/dslPickFirst.so", LIB_CONTENT_BAR)
            .withFile("src/main/jniLibs/x86_64/dslPickFirst.so", LIB_CONTENT_BAR)
            .withFile("src/main/jniLibs/x86/libExclude.so", LIB_CONTENT_BAR)
            .withFile("src/main/jniLibs/x86/variantPickFirst.so", LIB_CONTENT_BAR)
            .withFile("src/main/jniLibs/x86_64/variantPickFirst.so", LIB_CONTENT_BAR)
            .withFile("src/androidTest/jniLibs/x86/testKeep.so", LIB_CONTENT_BAR)
            .withFile("src/androidTest/jniLibs/x86/testExclude.so", LIB_CONTENT_BAR)

    private val multiModuleTestProject =
        MultiModuleTestProject.builder()
            .subproject(":lib", lib)
            .subproject(":app", app)
            .dependency(app, lib)
            .build()

    @get:Rule
    val project =
        GradleTestProject
            .builder()
            .fromTestApp(multiModuleTestProject)
            .create()

    @Test
    fun testPackagingOptions() {
        val appSubProject = project.getSubproject(":app")
        val libSubProject = project.getSubproject(":lib")
        appSubProject.execute("assemble", "assembleDebugAndroidTest")
        libSubProject.execute("assembleDebug", "assembleDebugAndroidTest")

        appSubProject.assertApk(ApkSelector.DEBUG) {
            jniLibs {
                abi("x86") {
                    // before checking content, ensure we only have that's expected, and nothing else
                    containsExactly(
                        "appKeep.so",
                        "libKeep.so",
                        "dslPickFirst.so",
                        "releaseExclude.so",
                        "variantPickFirst.so"
                    )
                    bytesOf("appKeep.so").isEqualTo(LIB_CONTENT_FOO)
                    bytesOf("dslPickFirst.so").isEqualTo(LIB_CONTENT_FOO)
                    bytesOf("releaseExclude.so").isEqualTo(LIB_CONTENT_FOO)
                    bytesOf("variantPickFirst.so").isEqualTo(LIB_CONTENT_FOO)
                }
                abi("x86_64") {
                    // before checking content, ensure we only have that's expected, and nothing else
                    containsExactly("dslPickFirst.so", "variantPickFirst.so")
                    bytesOf("dslPickFirst.so").isEqualTo(LIB_CONTENT_FOO)
                    bytesOf("variantPickFirst.so").isEqualTo(LIB_CONTENT_FOO)
                }
            }

            // check correct compression and manifest from useLegacyPackaging
            zipEntry("lib/x86/appKeep.so").hasCompressionMethod(ZipEntry.DEFLATED)

            manifestAsNodes()
                .node("manifest")
                .node("application")
                .attributes()
                .contains("http://schemas.android.com/apk/res/android:extractNativeLibs=true")

        }

        appSubProject.assertApk(ApkSelector.RELEASE) {
            jniLibs {
                abi("x86") {
                    // before checking content, ensure we only have that's expected, and nothing else
                    containsExactly(
                        "appKeep.so",
                        "libKeep.so",
                        "dslPickFirst.so",
                        "debugExclude.so",
                        "variantPickFirst.so"
                    )
                    bytesOf("appKeep.so").isEqualTo(LIB_CONTENT_FOO)
                    bytesOf("dslPickFirst.so").isEqualTo(LIB_CONTENT_FOO)
                    bytesOf("debugExclude.so").isEqualTo(LIB_CONTENT_FOO)
                    bytesOf("variantPickFirst.so").isEqualTo(LIB_CONTENT_FOO)
                }
                abi("x86_64") {
                    // before checking content, ensure we only have that's expected, and nothing else
                    containsExactly("dslPickFirst.so", "variantPickFirst.so")
                    bytesOf("dslPickFirst.so").isEqualTo(LIB_CONTENT_FOO)
                    bytesOf("variantPickFirst.so").isEqualTo(LIB_CONTENT_FOO)
                }
            }

            // check correct compression and manifest from useLegacyPackaging
            zipEntry("lib/x86/appKeep.so").hasCompressionMethod(ZipEntry.STORED)

            manifestAsNodes()
                .node("manifest")
                .node("application")
                .attributes()
                .contains("http://schemas.android.com/apk/res/android:extractNativeLibs=false")
        }

        appSubProject.assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
            jniLibs().abi("x86") {
                // before checking content, ensure we only have that's expected, and nothing else
                containsExactly("testKeep.so",)
                bytesOf("testKeep.so").isEqualTo(LIB_CONTENT_FOO)
            }
        }

        libSubProject.assertAar(AarSelector.DEBUG) {
            jniLibs().containsExactly(
                "x86/libKeep.so",
                "x86_64/variantPickFirst.so",
                "x86_64/dslPickFirst.so",
                "x86/variantPickFirst.so",
                "x86/dslPickFirst.so"
            )
        }

        libSubProject.assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
            jniLibs().abi("x86") {
                containsExactly("testKeep.so", "dslPickFirst.so", "libKeep.so", "variantPickFirst.so")
                bytesOf("testKeep.so").isEqualTo(LIB_CONTENT_BAR)
            }
        }
    }
}
