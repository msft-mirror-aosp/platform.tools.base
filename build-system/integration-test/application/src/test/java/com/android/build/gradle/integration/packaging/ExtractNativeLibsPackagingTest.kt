/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GeneratesApk
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import org.gradle.api.JavaVersion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.zip.ZipEntry.DEFLATED
import java.util.zip.ZipEntry.STORED

/**
 * sourceManifestvalue and expectedMergedManifestValue refer to the value of
 * android:extractNativeLibs in the source and merged manifests, respectively. If null, no such
 * attribute is written or expected in the manifests.
 *
 * useLegacyPackaging refers to the value of PackagingOptions.jniLibs.useLegacyPackaging specified
 * via the DSL. If null, no such value is specified.
 */
@RunWith(FilterableParameterized::class)
class ExtractNativeLibsPackagingTest(
    private val sourceManifestValue: Boolean?,
    private val minSdk: Int,
    compileSdk: Int,
    private val useLegacyPackaging: Boolean?,
    private val expectedMergedManifestValue: Boolean?,
    private val expectedCompression: Int,
    private val customJavaVersion: JavaVersion?
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "extractNativeLibs_{0}_minSdk_{1}_compileSdk_{2}_useLegacyPackaging_{3}_expectedMergedManifestValue_{4}_expectedCompression_{5}_customJavaVersion_{6}")
        fun parameters() = listOf(
            arrayOf(true, 22, DEFAULT_COMPILE_SDK_VERSION, true, true, DEFLATED, null),
            arrayOf(true, 22, DEFAULT_COMPILE_SDK_VERSION, false, true, DEFLATED, null),
            arrayOf(true, 22, DEFAULT_COMPILE_SDK_VERSION, null, true, DEFLATED, null),
            arrayOf(true, 23, DEFAULT_COMPILE_SDK_VERSION, true, true, DEFLATED, null),
            arrayOf(true, 23, DEFAULT_COMPILE_SDK_VERSION, false, true, DEFLATED, null),
            arrayOf(true, 23, DEFAULT_COMPILE_SDK_VERSION, null, true, DEFLATED, null),
            arrayOf(false, 22, DEFAULT_COMPILE_SDK_VERSION, true, false, STORED, null),
            arrayOf(false, 22, DEFAULT_COMPILE_SDK_VERSION, false, false, STORED, null),
            arrayOf(false, 22, DEFAULT_COMPILE_SDK_VERSION, null, false, STORED, null),
            arrayOf(false, 23, DEFAULT_COMPILE_SDK_VERSION, true, false, STORED, null),
            arrayOf(false, 23, DEFAULT_COMPILE_SDK_VERSION, false, false, STORED, null),
            arrayOf(false, 23, DEFAULT_COMPILE_SDK_VERSION, null, false, STORED, null),
            arrayOf(null, 22, DEFAULT_COMPILE_SDK_VERSION, true, true, DEFLATED, null),
            arrayOf(null, 22, DEFAULT_COMPILE_SDK_VERSION, false, false, STORED, null),
            arrayOf(null, 22, DEFAULT_COMPILE_SDK_VERSION, null, true, DEFLATED, null),
            arrayOf(null, 23, DEFAULT_COMPILE_SDK_VERSION, true, true, DEFLATED, null),
            arrayOf(null, 23, DEFAULT_COMPILE_SDK_VERSION, false, false, STORED, null),
            arrayOf(null, 23, DEFAULT_COMPILE_SDK_VERSION, null, false, STORED, null),
            // test case with older compile SDK that doesn't recognize android:extractNativeLibs.
            arrayOf(null, 22, 21, null, null, DEFLATED, JavaVersion.VERSION_1_8)
        )
    }

    private val extractNativeLibsAttribute = when (sourceManifestValue) {
        true -> "android:extractNativeLibs=\"true\""
        false -> "android:extractNativeLibs=\"false\""
        null -> ""
    }

    @get:Rule
    val rule = GradleRule.from {
        androidApplication(createMinimumProject = false) {
            android {
                namespace = "com.example"
                this.compileSdk = compileSdk
                defaultConfig {
                    minSdk = this@ExtractNativeLibsPackagingTest.minSdk
                }
                packaging {
                    jniLibs {
                        // if the value is null we want to use AGP's default.
                        this@ExtractNativeLibsPackagingTest.useLegacyPackaging?.let {
                            useLegacyPackaging = it
                        }
                    }
                }
                if (customJavaVersion != null) {
                    compileOptions {
                        sourceCompatibility = customJavaVersion
                        targetCompatibility = customJavaVersion
                    }
                }
            }
            files {
                add(
                    "src/main/AndroidManifest.xml",
                    //language=XML
                    """
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                            <application $extractNativeLibsAttribute/>
                        </manifest>""".trimIndent()
                )
                add(
                    "src/androidTest/AndroidManifest.xml",
                    //language=XML
                    """
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                            <application $extractNativeLibsAttribute/>
                        </manifest>""".trimIndent()
                )
                add("src/main/jniLibs/x86/fake.so", "foo".repeat(100))
                add("src/androidTest/jniLibs/x86/fake.so", "foo".repeat(100))
            }
        }
        androidTest(createMinimumProject = false) {
            android {
                namespace = "com.example"
                this.compileSdk = compileSdk
                defaultConfig {
                    minSdk = this@ExtractNativeLibsPackagingTest.minSdk
                }

                targetProjectPath = ":app"

                packaging {
                    jniLibs {
                        // if the value is null we want to use AGP's default.
                        this@ExtractNativeLibsPackagingTest.useLegacyPackaging?.let {
                            useLegacyPackaging = it
                        }
                    }
                }

                if (customJavaVersion != null) {
                    compileOptions {
                        sourceCompatibility = customJavaVersion
                        targetCompatibility = customJavaVersion
                    }
                }
            }
            files {
                add(
                    "src/main/AndroidManifest.xml",
                    //language=XML
                    """
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                            <application $extractNativeLibsAttribute/>
                        </manifest>""".trimIndent()
                )
                add("src/main/jniLibs/x86/fake.so", "foo".repeat(100))
            }
        }
    }

    @Test
    fun testNativeLibPackagedCorrectly_app() {
        val build = rule.build
        checkNativeLibPackagedCorrectly(
            build,
            build.androidApplication(),
            ":app:assembleDebug",
            ApkSelector.DEBUG
        )
    }

    @Test
    fun testNativeLibPackagedCorrectly_androidTest() {
        val build = rule.build
        checkNativeLibPackagedCorrectly(
            build,
            build.androidApplication(),
            ":app:assembleAndroidTest",
            ApkSelector.ANDROIDTEST_DEBUG
        )
    }

    @Test
    fun testNativeLibPackagedCorrectly_testModule() {
        val build = rule.build
        checkNativeLibPackagedCorrectly(
            build,
            build.androidTest(),
            ":test:assembleDebug",
            ApkSelector.DEBUG
        )
    }

    private fun checkNativeLibPackagedCorrectly(
        build: GradleBuild,
        project: GeneratesApk,
        task: String,
        apkSelector: ApkSelector
    ) {
        val result = build.executor.run(task)
        result.stdout.use {
            val resolvedUseLegacyPackaging: Boolean = useLegacyPackaging ?: (minSdk < 23)
            when {
                resolvedUseLegacyPackaging && expectedCompression == STORED -> {
                    assertThat(it).contains(
                        "PackagingOptions.jniLibs.useLegacyPackaging should be set to false"
                    )
                }
                !resolvedUseLegacyPackaging && expectedCompression == DEFLATED -> {
                    assertThat(it).contains(
                        "PackagingOptions.jniLibs.useLegacyPackaging should be set to true"
                    )
                }
                else -> assertThat(it).doesNotContain("PackagingOptions.jniLibs.useLegacyPackaging")
            }
        }
        result.stdout.use {
            if (sourceManifestValue != null) {
                assertThat(it).contains("android:extractNativeLibs should not be specified")
            } else {
                assertThat(it).doesNotContain("android:extractNativeLibs should not be specified")
            }
        }

        project.assertApk(apkSelector) {
            // check merged manifest
            manifest().apply {
                when (expectedMergedManifestValue) {
                    null -> {
                        doesNotContain("http://schemas.android.com/apk/res/android:extractNativeLibs")
                    }
                    else -> {
                        contains("http://schemas.android.com/apk/res/android:extractNativeLibs=$expectedMergedManifestValue")
                    }
                }
            }

            // check compression
            zipEntry("lib/x86/fake.so").hasCompressionMethod(expectedCompression)
        }
    }
}
