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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformKspTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withGradleOptions {
            withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        }.from {
            androidKotlinMultiplatformLibrary(":shared", createMinimumProject = false) {
                applyPlugin(PluginType.KSP)
                androidLibrary {
                    namespace = "com.shared.android"
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION

                    withDeviceTest {}
                    withHostTest {}
                }
                dependencies {
                    add("kspAndroid", "com.google.dagger:hilt-compiler:2.40.1")
                    add("kspAndroidDeviceTest", "com.google.dagger:hilt-compiler:2.40.1")
                    add("kspAndroidHostTest", "com.google.dagger:hilt-compiler:2.40.1")
                }
            }
        }

    @Test
    fun testRunningKsp() {
        val build = rule.build
        build.executor.run(":shared:kspAndroidMain")
        build.executor.run(":shared:kspAndroidDeviceTest")
        build.executor.run(":shared:kspAndroidHostTest")
    }
}
