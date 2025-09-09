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
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HasTestSuites
import com.android.build.api.variant.TestSuite
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.internal.testsuites.HasTestSuitesBuilder
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class AgpApplicationTestSuitesDeclarationTest
{
    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            jar("com.google.truth:truth:0.44")
        }
        .from {
            rootProject {
                buildscript {
                    classpath("com.google.truth:truth:0.44")
                }
            }
            gradleProperties {
                add(BooleanOption.TEST_SUITE_SUPPORT, true)
            }
            androidApplication {
                pluginCallbacks += MyAppCallback::class.java
                android {
                    testOptions.suites.create("first", AgpTestSuite::class.java) {
                        it.useJunitEngine.inputs += AgpTestSuiteInputParameters.MERGED_MANIFEST
                        it.targetVariants.add("debug")
                        it.targets.apply {
                            create("t1") { }
                        }
                    }
                }
                dependencies {
                    implementation("com.google.truth:truth:0.44")
                }
            }
        }

    @Test
    fun testApplicationTestSuites() {
        rule.build.executor.run(":app:tasks")
    }
}

class MyAppCallback: ApplicationComponentCallback {
    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        androidComponents.finalizeDsl { applicationExtension ->
            Truth.assertThat(applicationExtension.testOptions.suites.getByName("first").useJunitEngine.inputs)
                .containsExactly(
                    AgpTestSuiteInputParameters.MERGED_MANIFEST
                )
        }

        androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) { variantBuilder ->
            val testSuitesBuilder = variantBuilder as HasTestSuitesBuilder
            val listOfTestSuites = testSuitesBuilder.suites.values.joinToString { it.name }
            if (testSuitesBuilder.suites.size != 1) {
                throw RuntimeException("Expected 1 testSuites Tests, got $listOfTestSuites")
            }
            val testSuiteBuilder = testSuitesBuilder.suites["first"]
                ?: throw RuntimeException("Cannot find first test suite in test suites : " +
                        testSuitesBuilder.suites.keys.joinToString(", ")
                )

            val inputs = testSuiteBuilder.junitEngineSpec.inputs
            Truth.assertThat(inputs).containsExactly(
                AgpTestSuiteInputParameters.MERGED_MANIFEST
            )
            inputs.add(
                AgpTestSuiteInputParameters.TESTED_APKS
            )

        }
        androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
            val testSuites = variant as HasTestSuites
            val firstTestSuite = testSuites.suites["first"]
                ?: throw RuntimeException(
                    "Cannot find first test suite in test suites : " +
                            testSuites.suites.keys.joinToString(", ")
                )
            Truth.assertThat(firstTestSuite).isInstanceOf(TestSuite::class.java)
            Truth.assertThat(firstTestSuite.junitEngineSpec.inputs).containsExactly(
                AgpTestSuiteInputParameters.MERGED_MANIFEST,
                AgpTestSuiteInputParameters.TESTED_APKS
            )
        }

        androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
            val testSuites = variant as HasTestSuites
            Truth.assertThat(testSuites.suites).isEmpty()
        }
    }
}

