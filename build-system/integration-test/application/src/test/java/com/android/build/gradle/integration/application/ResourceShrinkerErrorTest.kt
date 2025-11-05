/*
 * Copyright (C) 2020 The Android Open Source Project
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
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

class ResourceShrinkerErrorTest {

    @get:Rule
    val rule = GradleRule.from {
        gradleProperties {
            add(BooleanOption.USE_NON_FINAL_RES_IDS, false)
            add(BooleanOption.R8_OPTIMIZED_RESOURCE_SHRINKING, true)
        }
        androidApplication {
            android {
                defaultConfig.minSdk = 24
                buildTypes {
                    named("release") {
                        it.isMinifyEnabled = true
                        it.isShrinkResources = true
                    }
                }
            }
        }
    }

    @Test
    fun `check error`() {
        val result = rule.build.executor
            .expectFailure()
            .run(":app:assembleRelease")
        result.assertErrorContains("Optimized resource shrinking requires non-final IDs.\n" +
                "Suggestion: opt back in to non-final resource IDs by setting " +
                "android.nonFinalResIds=true in gradle.properties.\n" +
                "Alternative: temporarily opt out of optimized resource shrinking until you're " +
                "ready to migrate by setting android.r8.optimizedResourceShrinking=false")
    }
}
