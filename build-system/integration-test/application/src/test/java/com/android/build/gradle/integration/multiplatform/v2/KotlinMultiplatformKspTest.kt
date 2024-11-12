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
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformKspTest {

    private val sharedLib = MinimalSubProject.kotlinMultiplatformAndroid("com.shared.android")

    @get:Rule
    val project: GradleTestProject =
        GradleTestProject.builder()
            .fromTestApp(
                MultiModuleTestProject.builder().subproject(":shared", sharedLib).build()
            )
            .withKotlinGradlePlugin(true)
            .withKspGradlePlugin(true)
            .create()

    @Before
    fun before() {
        TestFileUtils.appendToFile(
            project.getSubproject("shared").buildFile,
            """
                apply plugin: "com.google.devtools.ksp"

                kotlin {
                    androidLibrary {
                        withDeviceTest {}
                        withHostTest {}
                    }
                }

                dependencies {
                    add("kspAndroid", "com.google.dagger:hilt-compiler:2.40.1")
                    add("kspAndroidDeviceTest", "com.google.dagger:hilt-compiler:2.40.1")
                    add("kspAndroidHostTest", "com.google.dagger:hilt-compiler:2.40.1")
                }
            """.trimIndent()
        )
    }

    @Test
    fun testRunningKsp() {
        getExecutor().run(":shared:kspAndroidMain")
        getExecutor().run(":shared:kspAndroidDeviceTest")
        getExecutor().run(":shared:kspAndroidHostTest")
    }

    private fun getExecutor() =
        project.executor().withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
}
