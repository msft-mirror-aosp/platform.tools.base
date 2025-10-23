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

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import com.android.builder.model.v2.ide.SyncIssue
import com.android.testutils.MavenRepoGenerator
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class ProjectSubstitutionDependenciesTest: ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                runtimeOnly(MavenRepoGenerator.Library("com.example:lib:1.0"))
                implementation(MavenRepoGenerator.Library("com.example:lib2:1.0"))
            }
            pluginCallbacks += AppCallback::class.java
        }
        androidLibrary(":lib") { }
        androidLibrary(":lib2") {  }
        disableBuiltInKotlin()
    }

    class AppCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            project.configurations.all {
                it.resolutionStrategy.dependencySubstitution {
                    it.substitute(it.module("com.example:lib:1.0")).using(it.project(":lib"))
                    it.substitute(it.module("com.example:lib2:1.0")).using(it.project(":lib2"))
                }
            }
        }
    }

    @Test
    fun checkAllDependencies() {
        val result = rule.build.modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        with(result).compareVariantDependencies(
            projectAction = { getProject(":app") },
            goldenFile = "VariantDependencies"
        )
    }
}
