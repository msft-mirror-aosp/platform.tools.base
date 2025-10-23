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

import com.android.build.gradle.integration.common.fixture.ModelContainerV2
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import com.android.builder.model.v2.ide.SyncIssue
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class HelloWorldDynamicFeatureModelTest : ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                dynamicFeatures += listOf(DEFAULT_FEATURE_PATH)
            }
        }
        androidFeature {
            dependencies {
                implementation(project(DEFAULT_APP_PATH))
            }
        }
        disableBuiltInKotlin()
    }

    @Test
    fun `test models`() {
        val result = rule.build.modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        val appModelAction: ModelContainerV2.() -> ModelContainerV2.ModelInfo =
            { getProject(DEFAULT_APP_PATH) }

        with(result).compareAndroidProject(
            projectAction = appModelAction,
            goldenFile = "_app_AndroidProject"
        )
        with(result).compareVariantDependencies(
            projectAction = appModelAction,
            goldenFile = "_app_VariantDependencies"
        )

        val featureModelAction:  ModelContainerV2.() -> ModelContainerV2.ModelInfo =
            { getProject(DEFAULT_FEATURE_PATH) }

        with(result).compareAndroidProject(
            projectAction = featureModelAction,
            goldenFile = "_feature_AndroidProject"
        )
        with(result).compareVariantDependencies(
            projectAction = featureModelAction,
            goldenFile = "_feature_" +
                    "VariantDependencies"
        )
    }
}

/**
 * Similar to [HelloWorldDynamicFeatureModelTest], but with an app -> lib dependency
 */
class HelloWorldWithLibDynamicFeatureModelTest : ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                dynamicFeatures += listOf(DEFAULT_FEATURE_PATH)
            }
            dependencies {
                implementation(project(DEFAULT_LIB_PATH))
            }
        }
        androidFeature {
            dependencies {
                implementation(project(DEFAULT_APP_PATH))
            }
        }
        androidLibrary { }
        disableBuiltInKotlin()
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
            goldenFile = "_app_VariantDependencies"
        )

        val featureModelAction:  ModelContainerV2.() -> ModelContainerV2.ModelInfo =
            { getProject(DEFAULT_FEATURE_PATH) }

        with(result).compareVariantDependencies(
            projectAction = featureModelAction,
            goldenFile = "_feature_VariantDependencies"
        )
    }
}

class CompileSdkViaSettingsInDynamicFeatureModelTest {
    @get:Rule
    val rule = GradleRule.from {
        settings {
            applyPlugin(PluginType.ANDROID_SETTINGS)
            android {
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
            }
        }
        androidFeature(createMinimumProject = false) {
            android {
                namespace = "com.example.feature"
            }
            files.setupMinimumManifest()
        }
    }

    @Test
    fun `test compileTarget`() {
        val result = rule.build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        val androidDsl = result.container.getProject().androidDsl
            ?: throw RuntimeException("Failed to get AndroidDsl Model")
        Truth
            .assertWithMessage("compile target hash")
            .that(androidDsl.compileTarget)
            .isEqualTo("android-$DEFAULT_COMPILE_SDK_VERSION")
    }
}

class MinSdkViaSettingsInDynamicFeatureModelTest {
    @get:Rule
    val rule = GradleRule.from {
        settings {
            applyPlugin(PluginType.ANDROID_SETTINGS)
            android {
                minSdk = 23
            }
        }
        androidFeature { }
    }

    @Test
    fun `test minSdkVersion`() {
        val result = rule.build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        val androidDsl = result.container.getProject().androidDsl
            ?: throw RuntimeException("Failed to get AndroidDsl Model")

        Truth
            .assertWithMessage("minSdkVersion")
            .that(androidDsl.defaultConfig?.minSdkVersion)
            .isNotNull()

        Truth
            .assertWithMessage("minSdkVersion.apiLevel")
            .that(androidDsl.defaultConfig?.minSdkVersion?.apiLevel)
            .isEqualTo(23)

        Truth
            .assertWithMessage("minSdkVersion.codename")
            .that(androidDsl.defaultConfig?.minSdkVersion?.codename)
            .isNull()
    }
}
