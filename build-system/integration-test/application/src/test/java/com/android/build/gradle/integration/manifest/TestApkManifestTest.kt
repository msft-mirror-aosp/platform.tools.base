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

package com.android.build.gradle.integration.manifest

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

/**
 * Checks AndroidManifest.xml of test APK.
 */
class TestApkManifestTest {

    @get:Rule
    val project = GradleRule.from {
        androidApplication(":app") {
            android {
                defaultConfig {
                    applicationId = "com.example.app"
                    minSdk = 24
                }
            }
        }
    }

    @Test
    fun `android manifest matches expected`() {
        project.build.executor.run("assembleAndroidTest")
        project.build.androidApplication()
            .assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
                manifest().isEqualTo(
                    """
                    N: android=http://schemas.android.com/apk/res/android
                      E: manifest
                        A: http://schemas.android.com/apk/res/android:compileSdkVersion=36
                        A: http://schemas.android.com/apk/res/android:compileSdkVersionCodename="16"
                        A: package="com.example.app.test"
                        A: platformBuildVersionCode=36
                        A: platformBuildVersionName=16
                          E: uses-sdk
                            A: http://schemas.android.com/apk/res/android:minSdkVersion=24
                            A: http://schemas.android.com/apk/res/android:targetSdkVersion=24
                          E: instrumentation
                            A: http://schemas.android.com/apk/res/android:label="Tests for com.example.app"
                            A: http://schemas.android.com/apk/res/android:name="androidx.test.runner.AndroidJUnitRunner"
                            A: http://schemas.android.com/apk/res/android:targetPackage="com.example.app"
                            A: http://schemas.android.com/apk/res/android:handleProfiling=false
                            A: http://schemas.android.com/apk/res/android:functionalTest=false
                          E: application
                            A: http://schemas.android.com/apk/res/android:debuggable=true
                            A: http://schemas.android.com/apk/res/android:extractNativeLibs=false
                    """.trimIndent()
                )
            }
    }
}
