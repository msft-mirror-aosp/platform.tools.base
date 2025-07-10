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
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.internal.testsuites.HasTestSuites
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class TestTaskCustomConfigurationBlock {
    @get:Rule
    val rule = GradleRule.configure()
        .from {
            gradleProperties {
                add(BooleanOption.TEST_SUITE_SUPPORT, true)
            }
            androidApplication {
                android {
                    namespace = "com.example.test"
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
                        it.hostJar { }
                        it.targetVariants.add("redDebug")
                        it.targetVariants.add("blueDebug")
                        it.targets.apply {
                            create("t1") { }
                        }
                        it.targets.apply {
                            create("t2") { }
                        }
                    }
                }
                dependencies {
                    implementation("com.google.truth:truth:0.44")
                }
                pluginCallbacks.add(TestTaskCustomConfigurationBlockAppCallback::class.java)
            }
        }

    @Test
    fun testConfigurationBlockExecutes() {
        val result = rule.build
            .executor
            .expectFailure() // no test present.
            .run("app:testFirstT1BlueDebugTestSuite")
        result.assertOutputContains("Task testFirstT1BlueDebugTestSuite configured with")
        result.assertOutputContains("Task testFirstT1BlueDebugTestSuite configured from variant block")
        result.assertOutputDoesNotContain("Task testFirstT1RedDebugTestSuite configured with")
        result.assertOutputDoesNotContain("Task testFirstT2BlueDebugTestSuite configured with")
        result.assertOutputDoesNotContain("Task testFirstT2RedDebugTestSuite configured with")
    }
}

class TestTaskCustomConfigurationBlockAppCallback: ApplicationComponentCallback {
    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        // Register a configuration block at the DSL level.
        androidComponents.finalizeDsl { android ->
            android.testOptions.suites.getByName("first")
                .configureTestTasks { context ->
                    println("Task $name configured with $context")
                }
        }

        // Register another configuration block at the variant level
        androidComponents.onVariants(selector = androidComponents.selector().withName("blueDebug")) { variant ->
            variant as HasTestSuites
            val suite = variant.suites["first"]
                ?: throw RuntimeException("Cannot retrieve the test suite 'first'")
            suite.configureTestTasks { context ->
                println("Task $name configured from variant block")
                if (context.suiteName != suite.name) {
                    throw RuntimeException(
                        "Incorrect suiteName. " +
                                "Expected '${suite.name}', Got '${context.suiteName} "
                    )
                }
            }
        }
    }
}
