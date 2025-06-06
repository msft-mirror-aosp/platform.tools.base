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

package com.android.build.gradle.integration.application

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.project.AndroidLibraryProject
import com.android.build.gradle.integration.common.fixture.project.AndroidProject
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.tasks.GenerateTestConfig.Companion.TEST_CONFIG_FILE
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test
import java.io.File

class GenerateTestConfigTest {

    @get:Rule
    val rule = GradleRule.configure()
        .from {
            androidApplication {
                android.testOptions.unitTests.isIncludeAndroidResources = true
            }
        }

    // Regression test for b/293547829
    @Test
    fun testAbiSplitEnabledWithIncludeAndroidResource() {
        rule.build.androidApplication().reconfigure {
            android {
                splits {
                    abi {
                        isEnable = true
                        reset()
                        include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
                    }
                }
            }
        }
        rule.build.executor.run(":app:generateDebugUnitTestConfig")
    }

    @Test
    fun testAbiSplitEnabledWithIncludeAndroidResourceWithSingeAbi() {
        rule.build.androidApplication().reconfigure {
            android {
                splits {
                    abi {
                        isEnable = true
                        reset()
                        include("x86")
                    }
                }
            }
        }
        rule.build.executor.run(":app:generateDebugUnitTestConfig")
    }

    // Regression test for b/293547829
    @Test
    fun testAbiSplitDisabledWithIncludeAndroidResource() {
        rule.build.androidApplication().reconfigure {
            android {
                splits {
                    abi {
                        isEnable = false
                        reset()
                        include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
                    }
                }
            }
        }
        rule.build.executor.run(":app:generateDebugUnitTestConfig")
    }

    // Regression test for b/127986458
    @Test
    fun testAndroidManifestFromUnitTestIsMerged() {
        rule.build {
            androidApplication {
                addTestManifests()
            }
            androidLibrary {
                addTestManifests()
            }
        }
        verifyMergedManifest(DEFAULT_APP_PATH)
        verifyMergedManifest(DEFAULT_LIB_PATH)
    }

    private fun verifyMergedManifest(path: String) {
        rule.build.executor.withEnableInfoLogging(false)
            .run("$path:generateDebugUnitTestConfig")

        val project = rule.build.subProject(path) as AndroidProject
        val testConfigFile = project.intermediatesDir.resolve(
            "unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out/$TEST_CONFIG_FILE"
        )

        val mergedAndroidManifestRelativePath = if (project is AndroidLibraryProject) {
            "packaged_manifests/debugUnitTest/processDebugUnitTestManifest/AndroidManifest.xml"
        } else {
            "packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml"
        }

        val mergedAssetsRelativePath = if (project is AndroidLibraryProject) {
            "assets/debugUnitTest/mergeDebugUnitTestAssets"
        } else {
            "assets/debug/mergeDebugAssets"
        }

        // Properties.store escapes \ to \\, so this escape is necessary for Windows.
        val mergedAssetsPath = "build/intermediates/${mergedAssetsRelativePath}"
            .replace('/', File.separatorChar)
            .replace("\\", "\\\\")
        val mergedManifestPath = "build/intermediates/$mergedAndroidManifestRelativePath"
            .replace('/', File.separatorChar)
            .replace("\\", "\\\\")
        val resourceApkPath = "build/intermediates/apk_for_local_test/debugUnitTest/packageDebugUnitTestForUnitTest/apk-for-local-test.ap_"
            .replace('/', File.separatorChar)
            .replace("\\", "\\\\")

        assertThat(testConfigFile).hasContents("""
            #Generated by the Android Gradle plugin
            android_custom_package=${project.namespace}
            android_merged_assets=$mergedAssetsPath
            android_merged_manifest=$mergedManifestPath
            android_resource_apk=$resourceApkPath
        """.trimIndent())

        val mergedAndroidManifest = project.intermediatesDir.resolve(
            mergedAndroidManifestRelativePath
        )

        val expectedManifestContent = if (project is AndroidLibraryProject) {
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="pkg.name.lib.test" >

                <uses-sdk
                    android:minSdkVersion="1"
                    android:targetSdkVersion="1" />

                <instrumentation
                    android:name="android.test.InstrumentationTestRunner"
                    android:label="Tests for pkg.name.lib.test"
                    android:targetPackage="pkg.name.lib" />

                <application android:debuggable="true" >
                    <meta-data
                        android:name="meta_data_from_unit_test_debug_manifest"
                        android:value="value" />
                    <meta-data
                        android:name="meta_data_from_unit_test_manifest"
                        android:value="value" />

                    <uses-library android:name="android.test.runner" />

                    <meta-data
                        android:name="meta_data_from_debug_manifest"
                        android:value="value" />
                </application>

            </manifest>
            """.trimIndent()
        } else {
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:dist="http://schemas.android.com/apk/distribution"
                package="pkg.name.app" >

                <uses-sdk
                    android:minSdkVersion="1"
                    android:targetSdkVersion="1" />

                <application
                    android:debuggable="true"
                    android:extractNativeLibs="true" >
                    <meta-data
                        android:name="meta_data_from_debug_manifest"
                        android:value="value" />
                </application>

            </manifest>
            """.trimIndent()
        }
        assertThat(mergedAndroidManifest).hasContents(expectedManifestContent)
    }

    private fun AndroidProjectDefinition<out CommonExtension<*, *, *, *, *, *>>.addTestManifests() {
        android.testOptions.unitTests.isIncludeAndroidResources = true
        files {
            add(
                "src/debug/AndroidManifest.xml",
                //language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools">
                    <application>
                        <meta-data android:name="meta_data_from_debug_manifest" android:value="value" />
                    </application>
                </manifest>
                """.trimIndent()
            )
            add(
                "src/test/AndroidManifest.xml",
                //language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools">
                    <application>
                        <meta-data android:name="meta_data_from_unit_test_manifest" android:value="value" />
                    </application>
                </manifest>
                """.trimIndent()
            )
            add(
                "src/testDebug/AndroidManifest.xml",
                //language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools">
                    <application>
                        <meta-data android:name="meta_data_from_unit_test_debug_manifest" android:value="value" />
                    </application>
                </manifest>
                """.trimIndent()
            )
        }
    }
}
