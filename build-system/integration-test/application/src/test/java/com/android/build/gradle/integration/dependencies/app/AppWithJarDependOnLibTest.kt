/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.dependencies.app

import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.builder.model.v2.ide.SyncIssue
import org.junit.Rule
import org.junit.Test

class AppWithJarDependOnLibTest : ModelComparator() {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                enableKotlin = false
            }
            dependencies {
                api(project(":jar"))
            }
        }
        androidLibrary {
            android {
                enableKotlin = false
            }
        }
        genericProject(":jar") {
            applyPlugin(PluginType.JAVA_LIBRARY)
            dependencies {
                api(project(DEFAULT_LIB_PATH))
            }
        }
    }

    @Test
    fun `test VariantDependencies model`() {
        val result = rule.build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        with(result).compareVariantDependencies(
            projectAction = { getProject(DEFAULT_APP_PATH) },
            goldenFile = "app_VariantDependencies"
        )
    }
}
