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

package com.android.build.gradle.integration.dsl

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class DeprecatedDslTest {

    @get:Rule
    val rule = GradleRule.from { androidApplication {} }

    @Test
    fun testIsRenderscriptDebuggable() {
        val build = rule.build {
            androidApplication {
                android {
                    buildTypes {
                        named("debug") {
                            @Suppress("DEPRECATION")
                            it.isRenderscriptDebuggable = true
                        }
                    }
                }
            }
        }

        // First test for expected sync warning
        val syncIssues = build
            .modelBuilder
            .fetchModels(variantName = "debug")
            .container
            .getProject()
            .issues
            ?.syncIssues
        assertThat(syncIssues?.size).isEqualTo(1)
        assertThat(syncIssues?.firstOrNull()?.message)
            .contains("DSL element 'isRenderscriptDebuggable' is obsolete and should be removed.")

        // Then test for expected warning during build
        val result = build.executor.run("tasks")
        ScannerSubject.assertThat(result.stdout)
            .contains("DSL element 'isRenderscriptDebuggable' is obsolete and should be removed.")
    }
}
