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

package com.android.build.gradle.integration.multiplatform.model

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.model.BaseModelComparator
import com.android.build.gradle.options.BooleanOption
import com.android.utils.FileUtils
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformAndroidTargetIsolationModeSnapshotTest: BaseModelComparator {

    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .addGradleProperties("${BooleanOption.R8_PROGUARD_ANDROID_TXT_DISALLOWED.propertyName}=true")
        .disableBuiltInKotlin()
        .create()

    @Test
    fun testModels() {
        KmpModelComparator(
            project = project,
            testClass = this,
            modelSnapshotTask = "dumpAndroidTarget",
            taskOutputsLocator = { projectPath ->
                FileUtils.join(
                    project.getSubproject(projectPath).buildDir,
                    "ide",
                    "targets"
                ).listFiles()!!.toList()
            },
            configCacheMode = BaseGradleExecutor.ConfigurationCaching.PROJECT_ISOLATION
        ).fetchAndCompareModels(listOf(":kmpFirstLib", ":kmpSecondLib"))
    }
}
