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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

/**
 * Regression tests for b/372264148
 */
class UseInlineScopesNumbersTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication { applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN) }
        androidLibrary { applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN) }
        androidTest {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                targetProjectPath = DEFAULT_APP_PATH
            }
        }
    }

    @Test
    fun testFlagAddedForDebuggableApks() {
        val build = rule.build

        val result = build.executor.withArgument("--info").run("tasks")

        // Check that the flag is added for all kotlin compile tasks for debuggable APKs
        ScannerSubject.assertThat(result.stdout).contains(getLog(":app:compileDebugKotlin"))
        ScannerSubject.assertThat(result.stdout)
            .contains(getLog(":app:compileDebugAndroidTestKotlin"))
        ScannerSubject.assertThat(result.stdout)
            .contains(getLog(":lib:compileDebugAndroidTestKotlin"))
        ScannerSubject.assertThat(result.stdout).contains(getLog(":test:compileDebugKotlin"))

        // Check that the flag is not added for other kotlin compile tasks
        ScannerSubject.assertThat(result.stdout).doesNotContain(getLog(":app:compileReleaseKotlin"))
        ScannerSubject.assertThat(result.stdout).doesNotContain(getLog(":lib:compileDebugKotlin"))
    }

    @Test
    fun testOptOut() {
        val build = rule.build

        val result =
            build.executor
                .with(BooleanOption.DISABLE_INLINE_SCOPES_NUMBERS, true)
                .withArgument("--info")
                .run("tasks")

        ScannerSubject.assertThat(result.stdout).doesNotContain("-Xuse-inline-scopes-numbers")
    }
}

private fun getLog(taskPath: String) =
    "Adding -Xuse-inline-scopes-numbers Kotlin compiler flag for task $taskPath"

