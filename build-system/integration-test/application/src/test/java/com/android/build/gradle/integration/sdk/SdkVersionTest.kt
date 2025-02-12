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

package com.android.build.gradle.integration.sdk

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject
import org.junit.Rule
import org.junit.Test

class SdkVersionTest {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                namespace = "com.example.app"
            }
        }
    }

    @Test
    fun testCompileSdkVersionTooLowForMinorVersion() {
        val build = rule.build
        build.androidApplication().reconfigure {
            android.compileSdk = 35
            android.compileSdkMinor = 2
        }
        val result = build.executor.expectFailure().run("app:assembleDebug")
        ScannerSubject.assertThat(result.stderr).contains(
            "Minor versions are only supported for API 36 and above."
        )
    }

    @Test
    fun testCompileSdkMinor() {
        val build = rule.build
        build.androidApplication().reconfigure {
            android.compileSdk = 36
            android.compileSdkMinor = 2
        }
        // TODO (b/356143412): remove failure once there is support for this SDK and check manifest
        val result = build.executor.expectFailure().run("app:assembleDebug")
        ScannerSubject.assertThat(result.stderr).contains(
            "Failed to find target with hash string 'android-36.2' in:"
        )
    }
}
