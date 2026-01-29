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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AppAndLibNoBuildConfigTest {

    @get:Rule
    val rule = GradleRule.configure()
        .from {
            androidLibrary(":lib") {
                android {
                    namespace = "com.android.tests.testprojecttest.lib"
                    buildFeatures {
                        buildConfig = false
                    }
                    enableKotlin = false
                }
                files.add("src/main/java/com/android/tests/testprojecttest/lib/LibActivity.java",
                    """
                    package com.android.tests.testprojecttest.lib;
                    import android.app.Activity;
                    public class LibActivity extends Activity {}
                    """.trimIndent())
            }
            androidApplication(":app") {
                android {
                    namespace = "com.android.tests.testprojecttest.app"
                    buildFeatures {
                        buildConfig = false
                    }
                    enableKotlin = false
                }
                dependencies {
                    implementation(project(":lib"))
                }
            }
        }

    @Test
    fun `ensure buildConfig is not in the APK`() {
        rule.build.executor.run("app:assembleDebug")

        rule.build.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
            classes().containsExactly("com/android/tests/testprojecttest/app/R",
                                      "com/android/tests/testprojecttest/lib/LibActivity",
                                      "com/android/tests/testprojecttest/lib/R")
        }
    }

    @Test
    fun `ensure buildConfig is not in the AAR`() {
        rule.build.executor.run("lib:assembleDebug")

        rule.build.androidLibrary(":lib").assertAar(AarSelector.DEBUG) {
            // check this does not include the BuildConfig class
            classes().containsExactly("com/android/tests/testprojecttest/lib/LibActivity")
        }
    }

    @Test
    fun `ensure defaultConfig-buildConfigField fails`() {
        rule.build.androidApplication(":app").reconfigure {
            android {
                defaultConfig {
                    buildConfigField("boolean", "foo", "\"true\"")
                }
            }
        }

        rule.build.executor.expectFailure().run("app:assembleDebug").assertErrorContains(
            """
                defaultConfig contains custom BuildConfig fields, but the feature is disabled.
                To enable the feature, add the following to your module-level build.gradle:
                `android.buildFeatures.buildConfig = true`
            """.trimIndent()
        )
    }

    @Test
    fun `ensure buildtypes-buildConfigField fails`() {
        rule.build.androidApplication(":app").reconfigure {
            android {
                buildTypes {
                    named("debug") {
                        it.buildConfigField("boolean", "foo", "\"true\"")
                    }
                }
            }
        }

        rule.build.executor.expectFailure().run("app:assembleDebug").assertErrorContains(
            """
                Build Type 'debug' contains custom BuildConfig fields, but the feature is disabled.
                To enable the feature, add the following to your module-level build.gradle:
                `android.buildFeatures.buildConfig = true`
            """.trimIndent()
        )
    }

    @Test
    fun `ensure no buildConfig won't break dexing when no other java source file exists`() {
        rule.build.androidApplication(":app").reconfigure {
            android {
                // app has no sources by default in this setup, so no need to exclude
                compileOptions {
                    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
                    targetCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
                }
            }
        }

        rule.build.executor.run("app:assembleDebug")
    }
}
