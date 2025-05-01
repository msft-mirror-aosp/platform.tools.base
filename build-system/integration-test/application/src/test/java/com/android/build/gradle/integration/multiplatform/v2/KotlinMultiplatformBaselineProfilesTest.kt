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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.SdkConstants
import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.utils.FileUtils
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformBaselineProfilesTest {
    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .create()

    @Before
    fun setUp() {
        FileUtils.writeToFile(
            project.getSubproject("kmpFirstLib").file("src/androidMain/baselineProfiles/baseline-prof.txt"),
            """
                Lcom/example/kmpfirstlib/*;
            """.trimIndent()
        )
    }

    @Test
    fun testKmpBaselineProfileProcessingTaskExecuted() {
        val result = project.executor().run(":kmpFirstLib:assemble")
        Truth.assertThat(result.didWorkTasks).containsAtLeastElementsIn(
            listOf(
                ":kmpFirstLib:prepareAndroidMainArtProfile",
            )
        )
    }

    @Test
    fun testLibraryAarContents() {
        project.executor().run(":kmpFirstLib:assemble")

        project.getSubproject("kmpFirstLib").assertAar(AarSelector.NO_BUILD_TYPE) {
            textFile(SdkConstants.FN_ART_PROFILE).contains("Lcom/example/kmpfirstlib/*;")
        }
    }
}
