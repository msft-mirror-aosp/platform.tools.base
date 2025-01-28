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

package com.android.build.gradle.integration.dsl

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

/**
 * Regression for b/260899876
 *
 * (lack of settings.android accessor in KTS)
 */
class AndroidSettingsPluginExtension {

    @get:Rule
    val rule = GradleRule.from {
        settings {
            applyPlugin(PluginType.ANDROID_SETTINGS)
            android {
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
                minSdk = 23
                execution {
                }
            }
        }

        buildFileType = BuildFileType.KTS
    }

    @Test
    fun testConfigures() {
        val result = rule.build.executor.run("tasks")
        Truth.assertThat(result.failureMessage).isNull()
    }
}
