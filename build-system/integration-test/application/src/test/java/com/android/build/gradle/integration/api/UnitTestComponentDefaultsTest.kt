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

package com.android.build.gradle.integration.api

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder.Companion.UNIT_TEST_TYPE
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.AndroidProject.ARTIFACT_UNIT_TEST
import com.android.builder.model.v2.ide.BasicArtifact
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/**
 * Test enabling and disabling device tests through the variant builder APIs.
 */
class UnitTestComponentDefaultsTest {

    @get:Rule
    val appWithNewUnitTestBehavior = GradleRule.from {
        androidApplication {
            android {
                buildTypes {
                    create("qa") {
                        it.isDebuggable = true
                    }
                }
                testBuildType = "qa"
                flavorDimensions += "color"
                productFlavors {
                    create("defaults") {
                        it.dimension = "color"
                    }
                    create("override") {
                        it.dimension = "color"
                    }
                }
            }
            pluginCallbacks += CustomizeUnitTestEnabling::class.java
        }
        gradleProperties {
            add(BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE, true)
        }
    }

    class CustomizeUnitTestEnabling : ApplicationComponentCallback {

        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.beforeVariants(
                androidComponents.selector().withName("overrideDebug")
            ) { variantBuilder ->
                variantBuilder.hostTests[UNIT_TEST_TYPE]?.enable = true
            }
            androidComponents.beforeVariants(
                androidComponents.selector().withName("overrideQa")
            ) { variantBuilder ->
                variantBuilder.hostTests[UNIT_TEST_TYPE]?.enable = false
            }
            androidComponents.beforeVariants(
                androidComponents.selector().withName("overrideRelease")
            ) { variantBuilder ->
                variantBuilder.enable = false
            }
        }
    }

    @Test
    fun checkUnitTestsAreEnabledOrDisabledBasedOnTheBuildType() {
        val project = appWithNewUnitTestBehavior.build
        val result = project.modelBuilder.with(BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE, true).fetchModels()
        assertThat(result).isNotNull()
        val models = result.container.getProject(":app")
        val basicAndroidProject = checkNotNull(models.basicAndroidProject)
        fun unitTestArtifactsForVariant(variant: String): BasicArtifact? =
            basicAndroidProject.variants.single {it.name == variant}.hostTestArtifacts[ARTIFACT_UNIT_TEST]

        assertThat(basicAndroidProject.variants.map { it.name })
            .named("All variants (note overrideRelease is disabled completely, so it not present in this list)")
            .containsExactly( "defaultsDebug", "overrideDebug", "defaultsQa", "overrideQa", "defaultsRelease")
        assertThat(unitTestArtifactsForVariant("defaultsDebug"))
            .named("The unit test component for defaultsDebug is disabled as the tested build type is 'qa' and ${BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE.propertyName}=true")
            .isNull()
        assertThat(unitTestArtifactsForVariant("overrideDebug"))
            .named("The unit test component for overrideDebug are enabled by the custom AGP variant API callback")
            .isNotNull()
        assertThat(unitTestArtifactsForVariant("defaultsQa"))
            .named("The unit test component for defaultsQa are enabled by default as the test build type is 'qa' and ${BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE.propertyName}=true")
            .isNotNull()
        assertThat(unitTestArtifactsForVariant("overrideQa"))
            .named("The unit test component for overrideQa are disabled by the custom AGP variant API callback")
            .isNull()
        assertThat(unitTestArtifactsForVariant("defaultsRelease"))
            .named("The unit test component for defaultsRelease is disabled as the tested build type is 'qa' and ${BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE.propertyName}=true")
            .isNull()
    }
}
