/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.model

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.ModelContainerV2
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.builder.model.v2.ide.SyncIssue
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar
import org.junit.Rule
import org.junit.Test

class CustomConfigPublishingModelTest : ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                implementation(project(DEFAULT_LIB_PATH, configuration = "custom"))
            }
        }
        androidLibrary {
            componentCallback = LibCallback::class.java

        }
    }

    class LibCallback: LibraryComponentCallback {
        override fun handleComponents(
            project: Project,
            androidComponents: LibraryAndroidComponentsExtension
        ) {
            project.configurations.maybeCreate("custom")

            val customTask = project.tasks.register("customJar", Jar::class.java) {
                it.archiveBaseName.set("custom")
            }

            project.artifacts {
                it.add("custom", customTask)
            }
        }
    }


    @Test
    fun `test models`() {
        val result = rule.build.modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        val appModelAction: ModelContainerV2.() -> ModelContainerV2.ModelInfo =
            { getProject(DEFAULT_APP_PATH) }

        with(result).compareVariantDependencies(
            projectAction = appModelAction,
            goldenFile = "_VariantDependencies"
        )
    }
}
