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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Tests built-in Kotlin when the user applies the `com.android.built-in-kotlin` plugin. */
@RunWith(Parameterized::class)
class BuiltInKotlinPluginTest(private val useLatestKgpVersion: Boolean) {

    companion object {

        @Parameterized.Parameters(name = "useLatestKgpVersion_{0}")
        @JvmStatic
        fun parameters() = listOf(false, true)
    }

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            @Suppress("DEPRECATION")
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

            HelloWorldAndroid.setupKotlin(files)
        }
        gradleProperties {
            add(BooleanOption.BUILT_IN_KOTLIN, false)
            add(BooleanOption.USE_NEW_DSL, false)
        }
        useLatestKgpVersion = this@BuiltInKotlinPluginTest.useLatestKgpVersion
    }

    @Test
    fun `test compile Kotlin sources`() {
        val build = rule.configure().disableBrokenBuiltInKotlinOptOutChecks().disableBrokenNewDslOptOutChecks().build
        build.executor.run(":app:compileDebugKotlin")
    }

    @Test
    fun `fail when built-in Kotlin plugin is applied before kotlin-android plugin`() {
        val build = rule.build {
            androidApplication {
                @Suppress("DEPRECATION")
                applyPlugin(PluginType.KOTLIN_ANDROID)
            }
        }

        val result = build.executor.expectFailure().run(":app:compileDebugKotlin")

        result.assertErrorContains(
            "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0."
        )
    }

    @Test
    fun `fail when built-in Kotlin plugin is applied after kotlin-android plugin`() {
        val build = rule.build {
            androidApplication {
                @Suppress("DEPRECATION")
                applyPlugin(PluginType.KOTLIN_ANDROID, applyFirst = true)
            }
        }

        val result = build.executor.expectFailure().run(":app:compileDebugKotlin")

        if (useLatestKgpVersion) {
            result.assertErrorContains(
                "The 'org.jetbrains.kotlin.android' plugin in project ':app' is no longer required for Kotlin support since AGP 9.0."
            )
        } else {
            result.assertErrorContains(
                "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0."
            )
        }
    }

}
