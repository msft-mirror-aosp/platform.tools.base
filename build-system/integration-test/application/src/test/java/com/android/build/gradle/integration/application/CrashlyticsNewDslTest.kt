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

package com.android.build.gradle.integration.application

import com.android.SdkConstants
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import com.android.utils.FileUtils
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * When [BooleanOption.USE_NEW_DSL] is deleted, also delete [CrashlyticsTest] and rename this one
 * to `CrashlyticsTest`.
 */
class CrashlyticsNewDslTest {

    @get:Rule
    val project = GradleRule.configure().from {
        androidApplication {
            applyPlugin(PluginType.Custom("com.google.gms.google-services", "4.4.4"))
            applyPlugin(PluginType.Custom("com.google.firebase.crashlytics", "3.0.6"))
            android {
                defaultConfig {
                    minSdk = 24
                }
            }
            dependencies {
                implementation("com.google.firebase:firebase-crashlytics:20.0.3")
                implementation("com.google.firebase:firebase-analytics:23.0.0")
            }
        }
            .files {
                add("google-services.json", GOOGLE_SERVICES_JSON_CONTENTS)
            }
    }

    @Test
    fun assembleDebug() {
        project.build.executor
            .withArgument("-Duser.home=${customUserHome()}")
            .run("assembleDebug")
    }

    @Test
    fun assembleRelease() {
        project.build.executor
            .withArgument("-Duser.home=${customUserHome()}")
            .run("assembleRelease")
    }

    private fun customUserHome(): File =
        project.build.directory.resolve("user-home").also {
            FileUtils.mkdirs(it.toFile())
            if (SdkConstants.currentPlatform() == SdkConstants.PLATFORM_DARWIN) {
                FileUtils.mkdirs(it.resolve("Library/Caches").toFile())
            }
        }.toFile()

    companion object {
        val GOOGLE_SERVICES_JSON_CONTENTS = """
            {
              "project_info": {
                "project_number": "314159265358",
                "firebase_url": "https://crashlytics-test.firebaseio.com",
                "project_id": "crashlytics-test",
                "storage_bucket": "crashlytics-test.appspot.com"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:314159265358:android:0000000000000000",
                    "android_client_info": {
                      "package_name": "pkg.name.app"
                    }
                  },
                  "oauth_client": [
                    {
                      "client_id": "314159265358-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com",
                      "client_type": 3
                    }
                  ],
                  "api_key": [
                    {
                      "current_key": "AIzaXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
                    }
                  ],
                  "services": {
                    "analytics_service": {
                      "status": 1
                    },
                    "appinvite_service": {
                      "status": 1,
                      "other_platform_oauth_client": []
                    },
                    "ads_service": {
                      "status": 2
                    }
                  }
                }
              ],
              "configuration_version": "1"
            }
        """.trimIndent()
    }
}
