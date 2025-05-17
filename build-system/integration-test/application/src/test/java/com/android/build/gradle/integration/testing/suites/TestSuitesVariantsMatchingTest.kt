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
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.testsuites.impl.TestEngineInputProperties
import com.android.build.gradle.internal.testsuites.impl.TestEngineInputProperty
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import com.android.builder.model.v2.models.BaseTestSuiteSourceIdentity
import com.android.builder.model.v2.models.BasicTestSuite
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class TestSuitesVariantsMatchingTest {
    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            jar("com.google.truth:truth:0.44")
            jar("org.junit.platform:junit-platform-engine:1.10.1")
            jar("org.junit.platform:junit-platform-launcher:1.10.1")
            jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
            jar("com.test:toy-junit-engine:1.0")
                .addClasses(
                    ToyJunitEngineForTesting::class.java,
                    ToyTestDescriptor::class.java,
                    TestEngineInputProperties::class.java,
                    TestEngineInputProperties.Companion::class.java,
                    TestEngineInputProperty::class.java,
                    TestEngineInputProperty.Companion::class.java
                )
                .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine",
                    ToyJunitEngineForTesting::class.java.name)

        }.from {
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
                            inputs.add(
                                AgpTestSuiteInputParameters.MERGED_MANIFEST
                            )
                            includeEngines.add(
                                "[engine:toy-junit-engine-for-tests]"
                            )
                            enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                            enginesDependencies.add("com.test:toy-junit-engine:1.0")
                            enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
                        }
                        it.targetVariants += "redDebug"
                        it.hostJar {
                            dependencies.apply {
                                implementation.add("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
                                implementation.add("com.google.code.gson:gson:2.11.0")
                            }
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
        Truth.assertThat(testSuites).hasSize(1)
        val firstTestSuite: BasicTestSuite = testSuites!!.single()
        val firstTestSuiteFolders = firstTestSuite.sources.single()
        Truth.assertThat(firstTestSuiteFolders.type).isEqualTo(
            BaseTestSuiteSourceIdentity.SourceType.HOST_JAR
        )
        Truth.assertThat(firstTestSuiteFolders.folders).containsExactly(
            project.subProject(":app").resolve("src/first").toFile()
        )

        // verify the variant specific model
        val variantTestSuites = models.basicAndroidProject?.variants?.first { variant ->
            variant.name == "redDebug"
        }?.testSuiteArtifacts

        Truth.assertThat(variantTestSuites).hasSize(1)
        val variantTestSuite = variantTestSuites!!.values.single()
        Truth.assertThat(variantTestSuite.testSuiteName).isEqualTo("first")
    }
}
