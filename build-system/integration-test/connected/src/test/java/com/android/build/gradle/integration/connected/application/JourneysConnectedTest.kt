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

package com.android.build.gradle.integration.connected.application

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.LoggingLevel
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.junit.ClassRule
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class JourneysConnectedTest {
    companion object {
        @ClassRule
        @JvmField
        val EMULATOR = getEmulator()
    }

    @get:Rule
    val rule = GradleRule.configure()
        .withProfileOutput()
        .from {
            androidApplication {
                setupProject()
            }
        }

    private val executor: GradleTaskExecutor
        get() = rule.build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .with(BooleanOption.USE_ANDROID_X, true)
            .withLoggingLevel(LoggingLevel.LIFECYCLE)

    private fun AndroidProjectDefinition<ApplicationExtension>.setupProject() {
        applyPlugin(
            PluginType.Custom(
                id = "com.android.tools.journeys",
                version = "+",
                artifact = "com.android.tools.journeys:journeys-gradle-plugin",
                hasMarker = false,
            )
        )
        pluginCallbacks += PrintTestStatusCallback::class.java
        files {
            add(
                "src/journeysTest/myJourneysTest1.xml",
                //language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="My Journeys Test 1">
                    <actions>
                        <action>My Action 1</action>
                        <action>My Action 2</action>
                        <action>My Action 3</action>
                    </actions>
                </journey>
                """.trimIndent()
            )
            add(
                "src/journeysTest/myJourneysTest2.xml",
                //language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="My Journeys Test 2">
                    <actions>
                        <action>My Action 1</action>
                        <action>My Action 2</action>
                        <action>My Action 3</action>
                    </actions>
                </journey>
                """.trimIndent()
            )
        }
    }

    class PrintTestStatusCallback: GenericCallback {
        override fun handleProject(project: Project) {
            // Add listener to log test status for debugging.
            project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java) { testTask ->
                testTask.addTestListener(object: TestListener {
                    override fun beforeSuite(suite: TestDescriptor) {
                        println("Starting test suite: ${suite.name}")
                    }

                    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                        println("Finished test suite: ${suite.name} with result: ${result.resultType}")
                        result.exception?.printStackTrace()
                    }

                    override fun beforeTest(testDescriptor: TestDescriptor) {
                        println("Starting test: ${testDescriptor.name}")
                    }

                    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                        println("Finished test: ${testDescriptor.name} with result: ${result.resultType}")
                        result.exception?.printStackTrace()
                    }
                })
            }
        }
    }

    class DryRunCallback: GenericCallback {
        override fun handleProject(project: Project) {
            // Set --test-dry-run flag.
            project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java) { testTask ->
                testTask.dryRun.set(true)
            }
        }
    }

    // TODO(b/408183626): Re-enable once crawler app is available on maven for testing.
    @Ignore("408183626")
    @Test
    fun runJourneysTest() {
        val result = executor.run(":app:validateDebugJourneysTest")
        result.assertOutputContains("Finished test: My Journeys Test 1 with result: SUCCESS")
        result.assertOutputContains("Finished test: My Journeys Test 2 with result: SUCCESS")
    }

    @Test
    fun dryRunJourneysTest() {
        rule.build {
            androidApplication {
                pluginCallbacks += DryRunCallback::class.java
            }
        }
        val result = executor.run(":app:validateDebugJourneysTest")
        result.assertOutputContains("""
            Starting test suite: myJourneysTest1
            Starting test: My Action 1
            Finished test: My Action 1 with result: SKIPPED
            Starting test: My Action 2
            Finished test: My Action 2 with result: SKIPPED
            Starting test: My Action 3
            Finished test: My Action 3 with result: SKIPPED
            Finished test suite: myJourneysTest1 with result: SUCCESS
        """.trimIndent())
        result.assertOutputContains("""
            Starting test suite: myJourneysTest2
            Starting test: My Action 1
            Finished test: My Action 1 with result: SKIPPED
            Starting test: My Action 2
            Finished test: My Action 2 with result: SKIPPED
            Starting test: My Action 3
            Finished test: My Action 3 with result: SKIPPED
            Finished test suite: myJourneysTest2 with result: SUCCESS
        """.trimIndent())
    }
}
