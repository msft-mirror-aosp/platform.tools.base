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
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
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

    private val executor: GradleTaskExecutor
        get() = rule.build.executor.withEnableInfoLogging(false)

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
        executor.run(":app:generateDebugUnitTestConfig")
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
        executor.run(":app:generateDebugUnitTestConfig")
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
        executor.run(":app:generateDebugUnitTestConfig")
    }

    // Regression test for b/127986458
    @Test
    fun testAndroidManifestFromUnitTestIsMergedForAppModule() {
        rule.build {
            androidApplication {
                addTestManifests()
            }
        }
        verifyMergedManifest(DEFAULT_APP_PATH)
    }

    // Regression test for b/127986458
    @Test
    fun testAndroidManifestFromUnitTestIsMergedForLibraryModule() {
        rule.build {
            androidLibrary {
                addTestManifests()
            }
        }
        verifyMergedManifest(DEFAULT_LIB_PATH)
    }

    // Regression test for b/436878535
    @Test
    fun `overrideLibrary from app manifest should be respected`() {
        rule.build {
            androidApplication {
                android.defaultConfig.minSdk = 28
                android.testOptions.unitTests.isIncludeAndroidResources = true
                dependencies {
                    implementation(project(":lib"))
                }
                files {
                    update("src/main/AndroidManifest.xml").replaceWith(
                        //language=xml
                        """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                            xmlns:tools="http://schemas.android.com/tools">
                            <uses-sdk tools:overrideLibrary="pkg.name.lib" />
                        </manifest>
                        """.trimIndent()
                    )
                }
            }
            androidLibrary {
                // Library's min sdk level is higher than app's manifest.
                // This should cause the manifest merger failure, however the app's manifest has an overrideLibrary,
                // so this incompatibility should be ignored.
                android.defaultConfig.minSdk = 31
            }
        }

        executor.run(":app:generateDebugUnitTestConfig")
    }

    // Regression test for b/436878535
    @Test
    fun `manifest merger should fail when min sdk version is smaller than the library's min sdk without override`() {
        rule.build {
            androidApplication {
                android.defaultConfig.minSdk = 28
                android.testOptions.unitTests.isIncludeAndroidResources = true
                dependencies {
                    implementation(project(":lib"))
                }
            }
            androidLibrary {
                // Library's min sdk level is higher than app's manifest.
                // This should cause the manifest merger failure.
                android.defaultConfig.minSdk = 31
            }
        }

        val result = executor.expectFailure().run(":app:generateDebugUnitTestConfig")

        result.assertErrorContains(
            "uses-sdk:minSdkVersion 28 cannot be smaller than version 31 declared in library [:lib]")
    }

    private fun verifyMergedManifest(path: String) {
        val result = executor.run("$path:generateDebugUnitTestConfig")

        result.assertOutputDoesNotContain(
            "Setting the namespace via the package attribute in the source AndroidManifest.xml is no longer supported")

        val project = rule.build.subProject(path) as AndroidProject
        val testConfigFile = project.intermediatesDir.resolve(
            "unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out/$TEST_CONFIG_FILE"
        )

        val mergedAndroidManifestRelativePath =
            "packaged_manifests/debugUnitTest/processDebugUnitTestManifest/AndroidManifest.xml"

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
            //language=xml
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="pkg.name.lib.test" >

                <uses-sdk
                    android:minSdkVersion="1"
                    android:targetSdkVersion="1" />

                <instrumentation
                    android:name="androidx.test.runner.AndroidJUnitRunner"
                    android:label="Tests for pkg.name.lib.test"
                    android:targetPackage="pkg.name.lib" />

                <application android:debuggable="true" >
                    <meta-data
                        android:name="meta_data_from_unit_test_debug_manifest"
                        android:value="value" />
                    <meta-data
                        android:name="meta_data_value_override"
                        android:value="value_from_test_debug" />
                    <meta-data
                        android:name="meta_data_from_unit_test_manifest"
                        android:value="value" />
                    <meta-data
                        android:name="meta_data_from_debug_manifest"
                        android:value="value" />
                </application>

            </manifest>
            """.trimIndent()
        } else {
            //language=xml
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="pkg.name.app" >

                <uses-sdk
                    android:minSdkVersion="1"
                    android:targetSdkVersion="1" />

                <application android:debuggable="true" >
                    <meta-data
                        android:name="meta_data_from_unit_test_debug_manifest"
                        android:value="value" />
                    <meta-data
                        android:name="meta_data_value_override"
                        android:value="value_from_test_debug" />
                    <meta-data
                        android:name="meta_data_from_unit_test_manifest"
                        android:value="value" />
                    <meta-data
                        android:name="meta_data_from_debug_manifest"
                        android:value="value" />
                </application>

            </manifest>
            """.trimIndent()
        }
        assertThat(mergedAndroidManifest).hasContents(expectedManifestContent)
    }

    private fun AndroidProjectDefinition<out CommonExtension>.addTestManifests() {
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
                        <meta-data android:name="meta_data_value_override" android:value="value_from_debug" />
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
                        <meta-data android:name="meta_data_value_override" android:value="value_from_test" tools:node="replace" />
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
                        <meta-data android:name="meta_data_value_override" android:value="value_from_test_debug" tools:node="replace" />
                    </application>
                </manifest>
                """.trimIndent()
            )
        }
    }
}
