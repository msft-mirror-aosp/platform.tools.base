/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.testutils.TestUtils.KOTLIN_VERSION_FOR_TESTS
import org.junit.Rule
import org.junit.Test

class UpgradeKotlinGradlePluginApiTest {

    @get:Rule
    val rule = GradleRule.from {
        rootProject {
            buildscript {
                classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$KOTLIN_VERSION_FOR_TESTS") // FIXME update the version
            }
        }
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            files.add(
                "src/main/java/AppFoo.kt",
                //language=kotlin
                """
                    package com.foo.application
                    class AppFoo
                """.trimIndent()
            )
        }
    }

    /**
     * Test that users can upgrade the version of kotlin-gradle-plugin-api by adding KGP to their
     * buildscript classpath with the desired version.
     */
    @Test
    fun testUpgradingKotlinBaseApiPlugin() {
        val build = rule.build

        build.executor.run(":app:assembleDebug")

        val result = build.executor.run("buildEnvironment")
        ScannerSubject.assertThat(result.stdout)
            .contains("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$KOTLIN_VERSION_FOR_TESTS")
    }
}
