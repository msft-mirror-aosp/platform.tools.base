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
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.builder.model.v2.ide.SyncIssue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HelloWorldCompositeModelTest: ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                implementation("com.composite-build:lib:1.2")
            }
        }
        includedBuild("other-build") {
            includedBuild("nested-build") {
                androidLibrary(":anotherLib") {
                    group = "com.nested-build"
                    version = "1.3"
                }
            }
            androidLibrary(":lib") {
                group = "com.composite-build"
                version = "1.2"
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
    fun `test includedBuild BasicAndroidProject`() {
        with(result).compareBasicAndroidProject(
            projectAction = { getProject(":lib", ":other-build") },
            goldenFile = "BasicAndroidProject"
        )
    }

    @Test
    fun `test nested includedBuild BasicAndroidProject`() {
        with(result).compareBasicAndroidProject(
            projectAction = { getProject(":anotherLib", ":other-build:nested-build") },
            goldenFile = "BasicAndroidProject2"
        )
    }

    @Test
    fun `test includedBuild AndroidProject`() {
        with(result).compareAndroidProject(
            projectAction = { getProject(":lib", ":other-build") },
            goldenFile = "AndroidProject"
        )
    }

    @Test
    fun `test VariantDependencies`() {
        with(result).compareVariantDependencies(
            projectAction = { getProject(":app") },
            goldenFile = "VariantDependencies"
        )
    }
}

class CompositeBuildWithSameNameTest: ModelComparator() {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                implementation("com.androidlib:lib:1.0")
                implementation("com.javalib:lib:1.0")
            }
        }
        includedBuild("includedBuild1") {
            androidLibrary(":lib") {
                group = "com.androidlib"
                version = "1.0"
            }
        }
        includedBuild("includedBuild2") {
            genericProject(":lib") {
                applyPlugin(PluginType.JAVA_LIBRARY)
                group = "com.javalib"
                version = "1.0"
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
    fun `test VariantDependencies`() {
        with(result).compareVariantDependencies(
            projectAction = { getProject(":app") },
            goldenFile = "VariantDependencies"
        )
    }
}
