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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test
import java.io.File

class CustomConsumerProguardFilesInDslTest {
    @get:Rule
    val rule = GradleRule.configure()

        .from {
            kotlinMultiplatformLibrary(":lib") {
                applyPlugin(PluginType.ANDROID_KMP_LIBRARY)
                android {
                    namespace = "com.test.library"
                    compileSdk = 36
                    optimization.consumerKeepRules.publish = true
                    optimization.consumerKeepRules.files(File("proguard-rules.pro"))
                }
            }
        }


    @Test
    fun testRelativePath() {
        val project = rule.build {
            kotlinMultiplatformLibrary(":lib") {
                files.add("proguard-rules.pro", "some proguard statements")
            }
        }
        project.executor.run("clean")

        project.executor.run("assemble")

        // check the resulting aar.
        project.kotlinMultiplatformLibrary(":lib").assertAar(AarSelector.NO_BUILD_TYPE) {
            textFile("proguard.txt").isEqualTo("some proguard statements")
        }
    }
    @Test
    fun testFileDoesNotExists() {
        val project = rule.build
        project.executor.expectFailure()
            .with(BooleanOption.FAIL_ON_MISSING_PROGUARD_FILES, true)
            .run("assemble")
            .assertErrorContains("Supplied consumer proguard configuration does not exist")

    }

    @Test
    fun testFileDoesNotExistsWithFlag() {
        val project = rule.build
        project.executor
            .with(BooleanOption.FAIL_ON_MISSING_PROGUARD_FILES, false)
            .run("assemble")
            .assertOutputContains("Supplied consumer proguard configuration does not exist")
    }
}
