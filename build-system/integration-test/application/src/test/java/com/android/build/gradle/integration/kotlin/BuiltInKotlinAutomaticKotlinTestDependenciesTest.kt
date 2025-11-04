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
import com.android.testutils.TestUtils.BUILT_IN_KOTLIN_VERSION
import org.junit.Rule
import org.junit.Test

/** Tests that built-in Kotlin adds dependencies of `kotlin-test` automatically. */
class BuiltInKotlinAutomaticKotlinTestDependenciesTest {

    @get:Rule
    val rule = GradleRule.from { }

    /** Regression test for b/443080559. */
    @Test
    fun `test kotlin-test-junit is added automatically`() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    testImplementation("org.jetbrains.kotlin:kotlin-test:$BUILT_IN_KOTLIN_VERSION")
                }
            }
        }

        val result = build.executor.run(":app:dependencies", "--configuration", "debugUnitTestCompileClasspath")
        result.assertOutputContains("--- org.jetbrains.kotlin:kotlin-test:$BUILT_IN_KOTLIN_VERSION")
        result.assertOutputContains("--- org.jetbrains.kotlin:kotlin-test-junit:$BUILT_IN_KOTLIN_VERSION")
    }

}
