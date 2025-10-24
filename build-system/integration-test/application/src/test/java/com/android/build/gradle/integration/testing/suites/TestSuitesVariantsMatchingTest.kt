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

package com.android.build.gradle.integration.testing.suites

import com.android.build.api.dsl.AgpTestSuite
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.BasicTestSuiteArtifact
import com.android.builder.model.v2.ide.SyncIssue
import com.android.builder.model.v2.models.BasicTestSuite
import com.android.builder.model.v2.models.SourceType
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import kotlin.collections.forEach

class TestSuitesVariantsMatchingTest {
    @get:Rule
    val rule = GradleRule.configure()
        .from {
            gradleProperties {
                add(BooleanOption.TEST_SUITE_SUPPORT, true)
            }
            androidApplication {
                android {
                    namespace = "com.example.test"
                    buildTypes {
                        create("staging") { }
                    }
                    flavorDimensions += "color"
                    productFlavors {
                        create("red") { it.dimension = "color" }
                        create("blue") { it.dimension = "color" }
                    }
                    testOptions.suites.create("first", AgpTestSuite::class.java) {
                        it.useJunitEngine.apply {
                            includeEngines.add(
                                "[engine:toy-junit-engine-for-tests]"
                            )
                        }
                        it.assets { }
                        it.targetVariants.add("redDebug")
                        it.targetVariants.add("blueDebug")
                        it.targets.apply {
                            create("t1") { }
                        }
                        it.targets.apply {
                            create("t2") { }
                        }
                    }
                    testOptions.suites.create("second", AgpTestSuite::class.java) {
                        it.useJunitEngine.apply {
                            includeEngines.add(
                                "[engine:toy-junit-engine-for-tests]"
                            )
                        }
                        it.hostJar { }
                        it.targetVariants.add("redDebug")
                        it.targetVariants.add("blueDebug")
                        it.targetVariants.add("redStaging")
                        it.targets.apply {
                            create("c1") { }
                            create("c2") { testSuiteTarget ->
                                testSuiteTarget.targetDevices.add("device1")
                            }
                            create("c3") { testSuiteTarget ->
                                testSuiteTarget.targetDevices.add("device2")
                            }
                        }
                    }
                    testOptions.managedDevices {
                        localDevices.create("device1") {
                            it.device = "Pixel 2"
                        }
                        localDevices.create("device2") {
                            it.device = "Pixel 2"
                        }
                    }
                }
                dependencies {
                    implementation("com.google.truth:truth:0.44")
                }
            }
        }

    @Test
    fun testVariantMatchingTestSuite() {
        val project = rule.build
        val result = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
        Truth.assertThat(result).isNotNull()
        val models = result.container.getProject(":app")

        // verify the test suite model
        val testSuites = models.basicAndroidProject?.testSuites
        Truth.assertThat(testSuites).hasSize(2)
        val firstTestSuite = findTestSuite(testSuites!!, "first")
        firstTestSuite.targetsByVariant
            .map { variantTarget ->
                Truth.assertThat(variantTarget.targets.map { it.name })
                    .containsExactly("t1", "t2")
                Truth.assertThat(variantTarget.targetedVariant)
                    .isAnyOf("redDebug", "blueDebug")
            }
        Truth.assertThat(firstTestSuite
            .targetsByVariant.map { variantTarget -> variantTarget.targets.map { it.testTaskName }}.flatten()
        ).containsExactly(
            "testFirstT1RedDebugTestSuite",
            "testFirstT1BlueDebugTestSuite",
            "testFirstT2RedDebugTestSuite",
            "testFirstT2BlueDebugTestSuite"
        )
        val firstTestSuiteFolders = firstTestSuite.assets.single()
        Truth.assertThat(firstTestSuiteFolders.type).isEqualTo(
            SourceType.ASSETS
        )
        Truth.assertThat(firstTestSuiteFolders.directories).containsExactly(
            project.subProject(":app").resolve("src/first").toFile()
        )

        val secondTestSuite = findTestSuite(testSuites, "second")
        secondTestSuite.targetsByVariant.map { variantTarget ->
            Truth.assertThat(variantTarget.targets.map { it.name })
                .containsExactly("c1", "c2", "c3")
            Truth.assertThat(variantTarget.targetedVariant)
                .isAnyOf("redDebug", "blueDebug", "redStaging")
        }

        val secondTestSuiteFolders = secondTestSuite.hostJars.single()
        Truth.assertThat(secondTestSuiteFolders.type).isEqualTo(
            SourceType.HOST_JAR
        )
        Truth.assertThat(secondTestSuiteFolders.kotlin).containsExactly(
            project.subProject(":app").resolve("src/second").toFile()
        )

        Truth.assertThat(secondTestSuite
            .targetsByVariant.map { variantTarget -> variantTarget.targets.map { it.testTaskName } }.flatten()
        )
            .containsExactly(
                "testSecondC1BlueDebugTestSuite",
                "testSecondC1RedDebugTestSuite",
                "testSecondC1RedStagingTestSuite",
                "testSecondC2Device1BlueDebugTestSuite",
                "testSecondC2Device1RedDebugTestSuite",
                "testSecondC2Device1RedStagingTestSuite",
                "testSecondC3Device2BlueDebugTestSuite",
                "testSecondC3Device2RedDebugTestSuite",
                "testSecondC3Device2RedStagingTestSuite",
            )

        Truth.assertThat(models.basicAndroidProject?.variants).hasSize(6)

        models.basicAndroidProject?.variants?.forEach { variant ->
            when (variant.name) {
                "redDebug" -> {
                    val testSuites = variant.testSuiteArtifacts
                    Truth.assertThat(testSuites).hasSize(2)
                    Truth.assertThat(testSuites.map { it.value.testSuiteName})
                        .containsExactly("first", "second")
                    val firstTestSuite = findTestSuite(testSuites.values, "first")
                    Truth.assertThat(firstTestSuite.testSuiteName).isEqualTo("first")

                    Truth.assertThat(testSuites["first"]).isEqualTo(firstTestSuite)
                }
                "blueDebug" -> {
                    val testSuites = variant.testSuiteArtifacts
                    Truth.assertThat(testSuites).hasSize(2)
                    Truth.assertThat(testSuites.map { it.value.testSuiteName})
                        .containsExactly("first", "second")


                    val firstTestSuite = findTestSuite(testSuites.values, "first")
                    Truth.assertThat(firstTestSuite.testSuiteName).isEqualTo("first")

                    Truth.assertThat(testSuites["first"]).isEqualTo(firstTestSuite)
                }

                "redStaging" -> {
                    val testSuites = variant.testSuiteArtifacts
                    Truth.assertThat(testSuites).hasSize(1)
                    Truth.assertThat(testSuites.map { it.value.testSuiteName})
                        .containsExactly("second")


                    val secondTestSuite = findTestSuite(testSuites.values, "second")
                    Truth.assertThat(secondTestSuite.testSuiteName).isEqualTo("second")

                    Truth.assertThat(testSuites["second"]).isEqualTo(secondTestSuite)
                }
                else -> {
                    Truth.assertThat(variant.testSuiteArtifacts).isEmpty()
                }
            }
        }
    }

    private fun findTestSuite(testSuites: Collection<BasicTestSuite>, name: String)=
        testSuites.first { it.name == name }

    private fun findTestSuite(testSuites: Collection<BasicTestSuiteArtifact>, name: String) =
        testSuites.first { it.testSuiteName == name }

}
