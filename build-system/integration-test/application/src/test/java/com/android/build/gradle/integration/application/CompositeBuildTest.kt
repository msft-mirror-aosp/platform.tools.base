/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.testutils.TestUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Integration test for composite build.  */
class CompositeBuildTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication(":app") {
            HelloWorldAndroid.setupJava(files)
            android {
                buildTypes {
                    named("debug") {
                        it.isTestCoverageEnabled = true
                    }
                }
            }
            dependencies {
                api("com.example:lib:1.0")
                api("com.example:androidLib1:1.0")
                api("com.example:androidLib2:1.0")
            }
        }
        includedBuild("lib") {
            rootProject {
                group = "com.example"
                version = "1.0"
                plugins.add(PluginType.JAVA_LIBRARY)
                files.add("gradle.properties",
                    """
                        org.gradle.java.installations.paths=${TestUtils.getJava17Jdk().toString().replace("\\", "/")}
                    """.trimIndent())
            }
        }
        includedBuild("androidLib") {
            androidLibrary(":androidLib1") {
                group = "com.example"
                version = "1.0"
            }

            androidLibrary(":androidLib2") {
                group = "com.example"
                version = "1.0"
            }
        }
    }

    @Before
    fun setUp() {
    }

    @Test
    fun assembleDebug() {
        val build = rule.build
        build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .run(":app:assembleDebug")

        build.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
            exists()
        }
    }

    @Test
    fun assembleDebugWithConfigureOnDemand() {
        val build = rule.build

        build.executor.withArgument("--configure-on-demand").run(":app:assembleDebug")

        build.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
            exists()
        }
    }

    /**
     * Regression test for b/327670497
     */
    @Test
    fun lintDebug() {
        rule.build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .run(":app:lintDebug")
    }
}
