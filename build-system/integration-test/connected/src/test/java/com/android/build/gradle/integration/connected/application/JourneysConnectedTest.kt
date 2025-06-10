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
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.io.Resources
import org.gradle.api.Project
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class JourneysConnectedTest {
    companion object {

        @ClassRule
        @JvmField
        val EMULATOR = getEmulator()

        const val DEVICE_NAME = "emulator-5554 - 13"
        const val DEVICE_SERIAL = "emulator-5554"
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
        android {
            defaultConfig {
                minSdk = 24
            }
        }
        pluginCallbacks += MockChannelProviderCallback::class.java
    }

    class MockChannelProviderCallback : GenericCallback {

        override fun handleProject(project: Project) {
            val container = project.configurations
            val dependencies = project.dependencies
            if (container.findByName("_test-journeys-config") == null) {
                container.create("_test-journeys-config").apply {
                    isVisible = false
                    isTransitive = true
                    isCanBeConsumed = false
                    description = "A configuration to resolve mock channel provider dependencies."
                }

                dependencies.add(
                    "_test-journeys-config",
                    "com.android.tools.journeys:journeys-junit-engine-test-support:+"
                )
            }
            project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java)
                .configureEach { testTask ->
                    testTask.classpath =
                        project.configurations.getByName("_test-journeys-config") + testTask.classpath
                    val path =
                        project.providers.systemProperty("roboResultsPath").orNull ?: ""
                    testTask.jvmArgs("-DFakeCrawlerServiceInput.roboResultsPath=$path")
                }
        }
    }

    @Test
    fun `expect auth failure when using prod backend`() {
        val build = rule.build {
            androidApplication {
                pluginCallbacks -= MockChannelProviderCallback::class.java
            }
        }
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/simpleJourney.xml",
            """
                        <?xml version="1.0" encoding="utf-8"?>
                        <journey name="My Journeys Test 1">
                            <actions>
                                <action>Action 1</action>
                            </actions>
                        </journey>
                    """.trimIndent()
        )
        val result = executor.expectFailure().run(":app:validateDebugJourneysTest")
        result.assertOutputContains("initializationError")
    }

    @Test
    fun `expect journey filter to select specified journeys`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/journey1.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="Journey 1">
                    <actions>
                        <action>Action 1</action>
                        <action>Action 2</action>
                    </actions>
                </journey>
           """.trimIndent()
        )
        appProject.files.add(
            "src/journeysTest/journey2.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="Journey 2">
                    <actions>
                        <action>Action 1</action>
                        <action>Action 2</action>
                    </actions>
                </journey>
           """.trimIndent()
        )
        appProject.files.add(
            "src/journeysTest/journey3.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="Journey 3">
                    <actions>
                        <action>Action 1</action>
                        <action>Action 2</action>
                    </actions>
                </journey>
           """.trimIndent()
        )
        val roboResultsPath = appProject.resolve("robo_results.textproto")
        createRoboResults("journeys/robo_results_default.textproto", roboResultsPath)
        val result =
            executor.withArgument("-DroboResultsPath=$roboResultsPath")
                .withArgument("-PjourneysFilter=journey1.xml, journey2.xml")
                .run(":app:validateDebugJourneysTest")

        val journey1OutputDir =
            appProject.buildDir.resolve("outputs/journeysTest/debug/results/$DEVICE_SERIAL/journey1")
        assertThat(journey1OutputDir.resolve("robo_results.pb")).exists()
        for (i in 0 until 4) {
            assertThat(journey1OutputDir.resolve("action$i.png")).exists()
        }

        val journey2OutputDir =
            appProject.buildDir.resolve("outputs/journeysTest/debug/results/$DEVICE_SERIAL/journey2")
        assertThat(journey1OutputDir.resolve("robo_results.pb")).exists()
        for (i in 0 until 4) {
            assertThat(journey2OutputDir.resolve("action$i.png")).exists()
        }

        val journey3OutputDir =
            appProject.buildDir.resolve("outputs/journeysTest/debug/results/$DEVICE_SERIAL/journey3")
        assertThat(journey3OutputDir).doesNotExist()

        result.assertOutputContains("Journey 1 ($DEVICE_SERIAL) STANDARD_OUT")
        result.assertOutputContains("Journey 1 ($DEVICE_SERIAL) > Action 1 STANDARD_OUT")
        result.assertOutputContains("Journey 1 ($DEVICE_SERIAL) > Action 2 STANDARD_OUT")
        result.assertOutputContains("Journey 2 ($DEVICE_SERIAL) STANDARD_OUT")
        result.assertOutputContains("Journey 2 ($DEVICE_SERIAL) > Action 1 STANDARD_OUT")
        result.assertOutputContains("Journey 2 ($DEVICE_SERIAL) > Action 2 STANDARD_OUT")
        result.assertOutputDoesNotContain("Journey 3")
    }

    @Test
    fun `expect no journey to run with mismatched journey filter`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/journey1.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="Journey 1">
                    <actions>
                        <action>Action 1</action>
                        <action>Action 2</action>
                    </actions>
                </journey>
           """.trimIndent()
        )
        val result =
            executor
                .expectFailure()
                .withArgument("-PjourneysFilter=journey3.xml, journey4.xml")
                .run(":app:validateDebugJourneysTest")

        result.assertOutputContains("No test executed")
        result.assertOutputDoesNotContain("Journey 1")
    }

    @Test
    fun `expect error with malformed journey`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/journey1.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="Journey 1"></journey>
           """.trimIndent()
        )
        val result =
            executor
                .expectFailure()
                .run(":app:validateDebugJourneysTest")

        result.assertErrorContains("Execution failed for task ':app:validateDebugJourneysTest'")
        result.assertOutputDoesNotContain("Journey 1")
    }

    @Test
    fun `run successful journey`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/simpleJourney.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="A simple journey">
                    <actions>
                        <action>Tap on the search icon and enter 'Compose' into the search bar at the top of the screen</action>
                        <action>Tap on the 'Compose' topic</action>
                        <action>Save the first post</action>
                        <action>Go to saved posts</action>
                        <action>Confirm that there is a single saved post that belongs to the 'Compose' topic</action>
                    </actions>
                </journey>
           """.trimIndent()
        )
        val roboResultsPath = appProject.resolve("robo_results.textproto")
        createRoboResults("journeys/robo_results_successful.textproto", roboResultsPath)
        val result =
            executor.withArgument("-DroboResultsPath=$roboResultsPath")
                .run(":app:validateDebugJourneysTest")

        val outputDir =
            appProject.buildDir.resolve("outputs/journeysTest/debug/results/$DEVICE_SERIAL/simpleJourney")
        assertThat(outputDir.resolve("robo_results.pb")).exists()
        for (i in 0 until 9) {
            assertThat(outputDir.resolve("action$i.png")).exists()
        }
        result.assertOutputContainsPrefixedLinesInOrder(
            listOf(
                "A simple journey ($DEVICE_SERIAL) STANDARD_OUT",
                "[additionalTestArtifacts]deviceId=$DEVICE_SERIAL",
                "[additionalTestArtifacts]deviceDisplayName=$DEVICE_NAME",
                "A simple journey ($DEVICE_SERIAL) > Tap on the search icon and enter 'Compose' into the search bar at the top of the screen STANDARD_OUT",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action0.description=Launched package com.google.samples.apps.nowinandroid.demo.debug",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action0.durationInMillis=10179",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action0.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action0.screenshotPath=$outputDir/action0.png",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.description=Tap on the search icon",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.durationInMillis=6443",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.modelReasoning=The current goal is to tap on the search icon and enter 'Compose'. The search icon is available on the screen. So, I will tap on it.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.screenshotPath=$outputDir/action1.png",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.description=Enter 'Compose' into the search field",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.durationInMillis=2405",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.modelReasoning=The current goal is to tap on the search icon and enter 'Compose'. The search icon has already been tapped in the previous step. Now I need to enter 'Compose' into the search field.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.screenshotPath=$outputDir/action2.png",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.description=Enter 'Compose' into the search field",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.durationInMillis=1335",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.modelReasoning=The current goal is to tap on the search icon and enter 'Compose'. The search icon has already been tapped in the previous step. Now I need to enter 'Compose' into the search field.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.screenshotPath=$outputDir/action3.png",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.description=Enter 'Compose' into the search field",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.durationInMillis=1209",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.modelReasoning=The current goal is to tap on the search icon and enter 'Compose'. The search icon has already been tapped in the previous step. Now I need to enter 'Compose' into the search field.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.screenshotPath=$outputDir/action4.png",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt0.modelReasoning=The previous steps involved tapping on the search icon and entering 'Compose' into the search field. So the goal is complete.",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt0.screenshotPath=$outputDir/action5.png",
                "A simple journey ($DEVICE_SERIAL) > Tap on the 'Compose' topic STANDARD_OUT",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.description=Tap on the 'Compose' topic",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.durationInMillis=4036",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.modelReasoning=The current goal is to tap on the 'Compose' topic. The element with the text 'Compose' is tappable and is the correct element to tap.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.screenshotPath=$outputDir/action5.png",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt1.modelReasoning=The previous action was to tap on the 'Compose' topic, which fulfills the current goal.",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt1.screenshotPath=$outputDir/action6.png",
                "A simple journey ($DEVICE_SERIAL) > Save the first post STANDARD_OUT",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action6.description=Save the first post by tapping on the bookmark icon.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action6.durationInMillis=3941",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action6.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action6.modelReasoning=The current goal is to save the first post. The bookmark icon is available on the first post, so I will tap it.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action6.screenshotPath=$outputDir/action6.png",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt2.modelReasoning=The current goal is to save the first post, and the bookmark icon is checked, indicating that the first post is saved.",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt2.screenshotPath=$outputDir/action7.png",
                "A simple journey ($DEVICE_SERIAL) > Go to saved posts STANDARD_OUT",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action7.description=Tap on the 'Saved' tab to go to saved posts.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action7.durationInMillis=4903",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action7.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action7.modelReasoning=The current goal is to go to saved posts. The 'Saved' tab is the most relevant action to achieve this goal.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action7.screenshotPath=$outputDir/action7.png",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt3.modelReasoning=The previous action of tapping the 'Saved' tab successfully navigated the user to the saved posts screen, thus completing the goal.",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt3.screenshotPath=$outputDir/action8.png",
                "A simple journey ($DEVICE_SERIAL) > Confirm that there is a single saved post that belongs to the 'Compose' topic STANDARD_OUT",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt4.modelReasoning=The screen shows a single saved post, and the tags at the bottom include 'Compose', so the goal is complete.",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt4.screenshotPath=$outputDir/action8.png"
            ),
            listOf("A simple journey", "[additionalTestArtifacts]")
        )
    }

    @Test
    fun `run failed journey`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/simpleJourney.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="A simple journey">
                    <actions>
                        <action>Tap on the search icon and enter 'Compose' into the search bar at the top of the screen</action>
                        <action>Tap on the 'Compose' topic</action>
                        <action>Save the first post</action>
                        <action>Go to saved posts</action>
                        <action>Confirm that there is a single saved post that belongs to the 'Compose' topic</action>
                    </actions>
                </journey>
           """.trimIndent()
        )
        val roboResultsPath = appProject.resolve("robo_results.textproto")
        createRoboResults("journeys/robo_results_failed.textproto", roboResultsPath)
        val result =
            executor.expectFailure().withArgument("-DroboResultsPath=$roboResultsPath")
                .run(":app:validateDebugJourneysTest")

        val outputDir =
            appProject.buildDir.resolve("outputs/journeysTest/debug/results/$DEVICE_SERIAL/simpleJourney")
        assertThat(outputDir.resolve("robo_results.pb")).exists()
        for (i in 0 until 8) {
            assertThat(outputDir.resolve("action$i.png")).exists()
        }
        result.assertOutputContainsPrefixedLinesInOrder(
            listOf(
                "A simple journey ($DEVICE_SERIAL) STANDARD_OUT",
                "[additionalTestArtifacts]deviceId=$DEVICE_SERIAL",
                "[additionalTestArtifacts]deviceDisplayName=$DEVICE_NAME",
                "A simple journey ($DEVICE_SERIAL) > Tap on the search icon and enter 'Compose' into the search bar at the top of the screen STANDARD_OUT",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action0.description=Launched package com.google.samples.apps.nowinandroid.demo.debug",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action0.durationInMillis=10179",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action0.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action0.screenshotPath=$outputDir/action0.png",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.description=Tap on the search icon",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.durationInMillis=6443",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.modelReasoning=The current goal is to tap on the search icon and enter 'Compose'. The search icon is available on the screen. So, I will tap on it.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action1.screenshotPath=$outputDir/action1.png",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.description=Enter 'Compose' into the search field",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.durationInMillis=2405",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.modelReasoning=The current goal is to tap on the search icon and enter 'Compose'. The search icon has already been tapped in the previous step. Now I need to enter 'Compose' into the search field.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action2.screenshotPath=$outputDir/action2.png",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.description=Enter 'Compose' into the search field",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.durationInMillis=1335",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.modelReasoning=The current goal is to tap on the search icon and enter 'Compose'. The search icon has already been tapped in the previous step. Now I need to enter 'Compose' into the search field.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action3.screenshotPath=$outputDir/action3.png",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt0.modelReasoning=The previous steps involved tapping on the search icon and entering 'Compose' into the search field. So the goal is complete.",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt0.screenshotPath=$outputDir/action4.png",
                "A simple journey ($DEVICE_SERIAL) > Tap on the 'Compose' topic STANDARD_OUT",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.description=Tap on the 'Compose' topic",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.durationInMillis=1209",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.modelReasoning=The current goal is to tap on the 'Compose' topic. The element with the text 'Compose' is tappable and is the correct element to tap.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action4.screenshotPath=$outputDir/action4.png",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt1.modelReasoning=The previous action was to tap on the 'Compose' topic, which fulfills the current goal.",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt1.screenshotPath=$outputDir/action5.png",
                "A simple journey ($DEVICE_SERIAL) > Save the first post STANDARD_OUT",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.description=Save the first post by tapping on the bookmark icon.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.durationInMillis=4036",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.modelReasoning=The current goal is to save the first post. The bookmark icon is available on the first post, so I will tap it.",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action5.screenshotPath=$outputDir/action5.png",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt2.modelReasoning=The current goal is to save the first post. The bookmark icon is available on the first post, so I will tap it.",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt2.screenshotPath=$outputDir/action6.png",
                "A simple journey ($DEVICE_SERIAL) > Go to saved posts STANDARD_OUT",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action6.description=Waited for 2 seconds",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action6.durationInMillis=3941",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action6.result=ACTION_SUCCESS",
                "[additionalTestArtifacts]Journeys.ActionPerformed.action6.screenshotPath=$outputDir/action6.png",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt3.modelReasoning=The goal was to save the first post. Tapping on the bookmark icon should have saved the post, but it led to a Chrome welcome screen instead. This is unexpected and indicates a problem.",
                "[additionalTestArtifacts]Journeys.PromptComplete.prompt3.screenshotPath=$outputDir/action7.png",
                "A simple journey ($DEVICE_SERIAL) > Go to saved posts FAILED"
            ),
            listOf("A simple journey", "[additionalTestArtifacts]")
        )
        result.assertOutputDoesNotContain("Confirm that there is a single saved post that belongs to the 'Compose' topic")
    }

    private fun createRoboResults(roboResultsResourceName: String, roboResultsPath: Path) {
        val roboResultsBytes = Resources.toByteArray(
            Resources.getResource(
                JourneysConnectedTest::class.java, roboResultsResourceName
            ),
        )
        Files.write(roboResultsPath, roboResultsBytes)
    }
}
