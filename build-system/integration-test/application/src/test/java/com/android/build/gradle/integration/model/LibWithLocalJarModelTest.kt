/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.ModelBuilderV2
import com.android.build.gradle.integration.common.fixture.ModelContainerV2
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.builder.model.v2.ide.SyncIssue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LibWithLocalJarModelTest : ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                implementation(project(DEFAULT_LIB_PATH))
            }
        }
        androidLibrary {
            dependencies {
                implementation(localJar("foo.jar") { setEmptyClasses("com/example/MainClass") })
            }
        }
    }

    private lateinit var result: ModelBuilderV2.FetchResult<ModelContainerV2>

    @Before
    fun setup() {
        result = rule.build.modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")
    }

    @Test
    fun `test app dependency model`() {
        with(result).compareVariantDependencies(
            projectAction = { getProject(DEFAULT_APP_PATH) },
            goldenFile = "app"
        )
    }

    @Test
    fun `test lib dependency model`() {
        with(result).compareVariantDependencies(
            projectAction = { getProject(DEFAULT_LIB_PATH) },
            goldenFile = "lib"
        )
    }
}
