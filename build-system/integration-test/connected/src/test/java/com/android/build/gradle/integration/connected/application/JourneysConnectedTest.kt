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

import com.android.build.api.dsl.AgpTestSuite
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.LoggingLevel
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.journeys.proto.Artifact
import com.android.tools.journeys.proto.ArtifactType
import com.android.tools.journeys.proto.CommandType
import com.android.tools.journeys.proto.Interaction
import com.android.tools.journeys.proto.JourneyRunEvent
import com.android.tools.journeys.proto.JourneyRunResult
import com.android.tools.journeys.proto.Result
import com.android.tools.journeys.proto.RunFinished
import com.android.tools.journeys.proto.RunStarted
import com.android.tools.journeys.proto.Status
import com.android.tools.journeys.proto.Step
import com.android.tools.journeys.proto.StepFinished
import com.android.tools.journeys.proto.StepStarted
import com.android.tools.journeys.proto.Turn
import com.android.tools.journeys.proto.TurnAdded
import com.google.common.io.Resources
import com.google.protobuf.Timestamp
import org.gradle.api.Project
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JourneysConnectedTest {
    companion object {

        @ClassRule
        @JvmField
        val EMULATOR = getEmulator()

        const val DEVICE_NAME = "emulator-5554"
        const val DEVICE_SERIAL = "emulator-5554"
    }

    @get:Rule
    val rule = GradleRule.configure()
        .withProfileOutput()
        .from {
            gradleProperties {
                add(BooleanOption.TEST_SUITE_SUPPORT, true)
            }
            androidApplication {
                setupProject()
            }
        }

    private val executor: GradleTaskExecutor
        get() = rule.build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .with(BooleanOption.USE_ANDROID_X, true)
            .withLoggingLevel(LoggingLevel.INFO)

    private fun AndroidProjectDefinition<ApplicationExtension>.setupProject() {
        android {
            defaultConfig {
                minSdk = 24
            }
            testOptions.suites.create("journeysTest", AgpTestSuite::class.java) {
                it.useJunitEngine.apply {
                    inputs.add(AgpTestSuiteInputParameters.TESTED_APKS)
                    includeEngines.add("journeys-test-engine")
                    enginesDependencies.add("org.junit.platform:junit-platform-engine:+")
                    enginesDependencies.add("org.junit.platform:junit-platform-launcher:+")
                }
                it.targetVariants.add("debug")
                it.targets.create("t1") {}
            }
        }
        // Add callback to add test support dependency first to ensure that the mock channel
        // provider takes precedence in the classpath.
        pluginCallbacks += FakeCrawlerServiceSetupCallback::class.java
        pluginCallbacks += JourneysEngineDepSetupCallback::class.java
        pluginCallbacks += PrintTestLogsCallback::class.java
    }

    class FakeCrawlerServiceSetupCallback : ApplicationComponentCallback {

        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.finalizeDsl { android ->
                android.testOptions.suites.getByName("journeysTest") {
                    it.useJunitEngine.apply {
                        enginesDependencies.add("com.android.tools.journeys:journeys-junit-engine-test-support:+")
                    }
                }
            }
            project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java)
                .configureEach { testTask ->
                    val path =
                        project.providers.systemProperty("roboResultsPath").orNull ?: ""
                    val shouldInduceServerError =
                        project.providers.systemProperty("shouldInduceServerError").orNull
                            ?: "false"
                    testTask.jvmArgs("-DFakeCrawlerServiceInput.roboResultsPath=$path")
                    testTask.jvmArgs("-DFakeCrawlerServiceInput.shouldInduceServerError=$shouldInduceServerError")
                }
        }
    }

    class JourneysEngineDepSetupCallback : ApplicationComponentCallback {

        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.finalizeDsl { android ->
                android.testOptions.suites.getByName("journeysTest") {
                    it.useJunitEngine.apply {
                        enginesDependencies.add("com.android.tools.journeys:journeys-junit-engine:+")
                    }
                }
            }
        }
    }

    class PrintTestLogsCallback : GenericCallback {

        override fun handleProject(project: Project) {
            project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java) {
                it.testLogging {
                    it.events("passed", "skipped", "failed")
                    it.showExceptions = true
                    it.exceptionFormat = TestExceptionFormat.FULL
                    it.showCauses = true
                    it.showStackTraces = true
                }
            }
        }
    }

    @Test
    fun `expect auth failure when using prod backend`() {
        val build = rule.build {
            androidApplication {
                pluginCallbacks -= FakeCrawlerServiceSetupCallback::class.java
            }
        }
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/simple.journey.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="My Journeys Test 1">
                    <actions>
                        <action>Action 1</action>
                    </actions>
                </journey>
            """.trimIndent()
        )
        val result =
            executor.expectFailure()
                .withEnvironmentVariables(mapOf("JOURNEYS_ENABLE_STDOUT_REPORT" to "true"))
                .run(":app:testJourneysTestT1DebugTestSuite")

        val outputDir =
            appProject.buildDir.resolve("intermediates/debug/testJourneysTestT1DebugTestSuite/results/$DEVICE_SERIAL/simple")
        assertThat(outputDir.resolve("journey_results.pb")).exists()

        assertJourneyEvents(
            result, "$DEVICE_SERIAL > simple.journey.xml", "", listOf(
                buildRunStartedEvent(
                    prompts = listOf("Action 1"),
                    metadata = mapOf("deviceId" to DEVICE_SERIAL, "deviceName" to DEVICE_NAME)
                ),
                buildRunFinishedEvent(
                    Status.ERROR,
                    "Failed to obtain credentials for establishing connection with backend. Make sure you are logged in to Android Studio before re-trying. [Reason=AUTHENTICATION_FAILED]"
                )
            )
        )
    }

    @Test
    fun `expect journey filter to select specified journeys`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/journey1.journey.xml",
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
            "src/journeysTest/journey2.journey.xml",
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
            "src/journeysTest/journey3.journey.xml",
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
                .withEnvironmentVariables(mapOf("JOURNEYS_FILTER" to "journey1.journey.xml, journey2.journey.xml"))
                .withEnvironmentVariables(mapOf("JOURNEYS_ENABLE_STDOUT_REPORT" to "true"))
                .run(":app:testJourneysTestT1DebugTestSuite")

        val journey1OutputDir =
            appProject.buildDir.resolve("intermediates/debug/testJourneysTestT1DebugTestSuite/results/$DEVICE_SERIAL/journey1")
        assertThat(journey1OutputDir.resolve("robo_results.pb")).exists()
        assertThat(journey1OutputDir.resolve("journey_results.pb")).exists()
        for (i in 0 until 4) {
            assertThat(journey1OutputDir.resolve("displayState$i.png")).exists()
        }

        val journey2OutputDir =
            appProject.buildDir.resolve("intermediates/debug/testJourneysTestT1DebugTestSuite/results/$DEVICE_SERIAL/journey2")
        assertThat(journey2OutputDir.resolve("robo_results.pb")).exists()
        assertThat(journey2OutputDir.resolve("journey_results.pb")).exists()
        for (i in 0 until 4) {
            assertThat(journey2OutputDir.resolve("displayState$i.png")).exists()
        }

        val journey3OutputDir =
            appProject.buildDir.resolve("intermediates/debug/testJourneysTestT1DebugTestSuite/results/$DEVICE_SERIAL/journey3")
        assertThat(journey3OutputDir).doesNotExist()

        result.assertOutputDoesNotContain("journey3")

        val getExpectedEventsForJourney = { outputDir: Path ->
            listOf(
                buildRunStartedEvent(
                    prompts = listOf("Action 1", "Action 2"),
                    metadata = mapOf("deviceId" to DEVICE_SERIAL, "deviceName" to DEVICE_NAME)
                ),
                buildStepStartedEvent(
                    "Action 1",
                    Timestamp.newBuilder().setSeconds(1750247037).setNanos(165000000).build()
                ),
                buildTurnAddedEvent(
                    "Perform action 1",
                    "Performing action 1",
                    "$outputDir/displayState1.png",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 75 217",
                            "$outputDir/displayState1.png",
                            Status.SUCCEEDED
                        )
                    )
                ),
                buildTurnAddedEvent(
                    "Goal Complete",
                    "The goal for action 1 is complete.",
                    "$outputDir/displayState2.png",
                    listOf()
                ),
                buildStepFinishedEvent(
                    Timestamp.newBuilder().setSeconds(1750247076).setNanos(824000000).build(),
                    Status.SUCCEEDED
                ),
                buildStepStartedEvent(
                    "Action 2",
                    Timestamp.newBuilder().setSeconds(1750247076).setNanos(824000000).build()
                ),
                buildTurnAddedEvent(
                    "Perform action 2",
                    "Performing action 2",
                    "$outputDir/displayState2.png",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 540 919",
                            "$outputDir/displayState2.png",
                            Status.SUCCEEDED
                        )
                    )
                ),
                buildTurnAddedEvent(
                    "Goal Complete",
                    "The goal for action 2 is complete.",
                    "$outputDir/displayState3.png",
                    listOf()
                ),
                buildStepFinishedEvent(
                    Timestamp.newBuilder().setSeconds(1750247096).setNanos(665000000).build(),
                    Status.SUCCEEDED
                ),
                buildRunFinishedEvent(
                    Status.SUCCEEDED
                )
            )
        }
        assertJourneyEvents(
            result,
            "$DEVICE_SERIAL > journey1.journey.xml",
            "$DEVICE_SERIAL > journey2.journey.xml",
            getExpectedEventsForJourney(journey1OutputDir)
        )
        assertJourneyEvents(
            result,
            "$DEVICE_SERIAL > journey2.journey.xml",
            "$DEVICE_SERIAL > journey1.journey.xml",
            getExpectedEventsForJourney(journey2OutputDir)
        )
    }

    @Test
    fun `expect no artifact output with stdout reporting disabled`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/journey1.journey.xml",
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
        val roboResultsPath = appProject.resolve("robo_results.textproto")
        createRoboResults("journeys/robo_results_default.textproto", roboResultsPath)
        val result =
            executor.withArgument("-DroboResultsPath=$roboResultsPath")
                .run(":app:testJourneysTestT1DebugTestSuite")

        val journey1OutputDir =
            appProject.buildDir.resolve("intermediates/debug/testJourneysTestT1DebugTestSuite/results/$DEVICE_SERIAL/journey1")
        assertThat(journey1OutputDir.resolve("robo_results.pb")).exists()
        assertThat(journey1OutputDir.resolve("journey_results.pb")).exists()
        for (i in 0 until 4) {
            assertThat(journey1OutputDir.resolve("displayState$i.png")).exists()
        }

        result.assertOutputDoesNotContain("JOURNEYS_TEST_ARTIFACT")
    }

    @Test
    fun `expect no journey to run with mismatched journey filter`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/journey1.journey.xml",
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
        // This is to check that missing .journey suffix also results in skipping of the file.
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
        val result =
            executor
                .withEnvironmentVariables(mapOf("JOURNEYS_FILTER" to "journey2.xml, journey3.journey.xml"))
                .withEnvironmentVariables(mapOf("JOURNEYS_ENABLE_STDOUT_REPORT" to "true"))
                .run(":app:testJourneysTestT1DebugTestSuite")

        result.assertOutputDoesNotContain("$DEVICE_SERIAL > journey1.journey.xml")
        result.assertOutputDoesNotContain("$DEVICE_SERIAL > journey2.xml")
    }

    @Test
    fun `expect error with malformed journey`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/malformed.journey.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="Journey 1"></journey>
            """.trimIndent()
        )
        val result =
            executor
                .expectFailure()
                .withEnvironmentVariables(mapOf("JOURNEYS_ENABLE_STDOUT_REPORT" to "true"))
                .run(":app:testJourneysTestT1DebugTestSuite")

        val outputDir =
            appProject.buildDir.resolve("intermediates/debug/testJourneysTestT1DebugTestSuite/results/$DEVICE_SERIAL/malformed")
        assertThat(outputDir.resolve("journey_results.pb")).exists()

        assertJourneyEvents(
            result, "$DEVICE_SERIAL > malformed.journey.xml", "", listOf(
                buildRunStartedEvent(
                    prompts = listOf(),
                    metadata = mapOf("deviceId" to DEVICE_SERIAL, "deviceName" to DEVICE_NAME)
                ),
                buildRunFinishedEvent(
                    Status.ERROR,
                    "The <journey> element must have exactly one <actions> element, but found 0."
                )
            )
        )
    }

    @Test
    fun `expect error with server issue`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/simple.journey.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <journey name="Journey 1">
                    <actions>
                        <action>Action 1</action>
                    </actions>
                </journey>
            """.trimIndent()
        )
        val result =
            executor
                .expectFailure()
                .withArgument("-DshouldInduceServerError=true")
                .withEnvironmentVariables(mapOf("JOURNEYS_ENABLE_STDOUT_REPORT" to "true"))
                .run(":app:testJourneysTestT1DebugTestSuite")

        val outputDir =
            appProject.buildDir.resolve("intermediates/debug/testJourneysTestT1DebugTestSuite/results/$DEVICE_SERIAL/simple")
        assertThat(outputDir.resolve("journey_results.pb")).exists()

        result.assertOutputContains("Intentionally throwing an error.")
        assertJourneyEvents(
            result, "$DEVICE_SERIAL > simple.journey.xml", "", listOf(
                buildRunStartedEvent(
                    prompts = listOf("Action 1"),
                    metadata = mapOf("deviceId" to DEVICE_SERIAL, "deviceName" to DEVICE_NAME)
                ),
                buildRunFinishedEvent(
                    Status.ERROR,
                    "An unexpected error occurred during journey execution: io.grpc.StatusRuntimeException: UNKNOWN: Application error processing RPC [Reason=UNKNOWN_FAILURE]"
                )
            )
        )
    }

    @Test
    fun `run successful journey`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/simple.journey.xml",
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
        val result = executor.withArgument("-DroboResultsPath=$roboResultsPath")
            .withEnvironmentVariables(mapOf("JOURNEYS_ENABLE_STDOUT_REPORT" to "true"))
            .run(":app:testJourneysTestT1DebugTestSuite")

        val outputDir =
            appProject.buildDir.resolve("intermediates/debug/testJourneysTestT1DebugTestSuite/results/$DEVICE_SERIAL/simple")
        assertThat(outputDir.resolve("robo_results.pb")).exists()
        assertThat(outputDir.resolve("journey_results.pb")).exists()
        for (i in 0 until 9) {
            assertThat(outputDir.resolve("displayState$i.png")).exists()
        }

        val expectedEvents = listOf(
            buildRunStartedEvent(
                prompts = listOf(
                    "Tap on the search icon and enter 'Compose' into the search bar at the top of the screen",
                    "Tap on the 'Compose' topic",
                    "Save the first post",
                    "Go to saved posts",
                    "Confirm that there is a single saved post that belongs to the 'Compose' topic"
                ),
                metadata = mapOf("deviceId" to DEVICE_SERIAL, "deviceName" to DEVICE_NAME)
            ),
            buildStepStartedEvent(
                "Tap on the search icon and enter 'Compose' into the search bar at the top of the screen",
                Timestamp.newBuilder().setSeconds(1750247037).setNanos(165000000).build()
            ),
            buildTurnAddedEvent(
                "Tap on the search icon",
                "The current goal is to tap on the search icon and enter 'Compose'. The search icon is available on the screen. So, I will tap on it.",
                "$outputDir/displayState1.png",
                listOf(
                    buildInteraction(
                        CommandType.ADB_COMMAND,
                        "ADB command: input tap 75 217",
                        "$outputDir/displayState1.png",
                        Status.SUCCEEDED
                    )
                )
            ),
            buildTurnAddedEvent(
                "Enter 'Compose' into the search field",
                "The current goal is to tap on the search icon and enter 'Compose'. The search icon has already been tapped in the previous step. Now I need to enter 'Compose' into the search field.",
                "$outputDir/displayState2.png",
                listOf(
                    buildInteraction(
                        CommandType.ADB_COMMAND,
                        "ADB command: input tap 603 247",
                        "$outputDir/displayState2.png",
                        Status.SUCCEEDED
                    ),
                    buildInteraction(
                        CommandType.ADB_COMMAND,
                        "ADB command: input text Compose",
                        "$outputDir/displayState3.png",
                        Status.SUCCEEDED
                    ),
                    buildInteraction(
                        CommandType.ADB_COMMAND,
                        "ADB command: input keyevent ENTER",
                        "$outputDir/displayState4.png",
                        Status.SUCCEEDED
                    )
                )
            ),
            buildTurnAddedEvent(
                "Goal Complete",
                "The previous steps involved tapping on the search icon and entering 'Compose' into the search field. So the goal is complete.",
                "$outputDir/displayState5.png",
                listOf()
            ),
            buildStepFinishedEvent(
                Timestamp.newBuilder().setSeconds(1750247076).setNanos(824000000).build(),
                Status.SUCCEEDED
            ),
            buildStepStartedEvent(
                "Tap on the 'Compose' topic",
                Timestamp.newBuilder().setSeconds(1750247076).setNanos(824000000).build()
            ),
            buildTurnAddedEvent(
                "Tap on the 'Compose' topic",
                "The current goal is to tap on the 'Compose' topic. The element with the text 'Compose' is tappable and is the correct element to tap.",
                "$outputDir/displayState5.png",
                listOf(
                    buildInteraction(
                        CommandType.ADB_COMMAND,
                        "ADB command: input tap 540 919",
                        "$outputDir/displayState5.png",
                        Status.SUCCEEDED
                    )
                )
            ),
            buildTurnAddedEvent(
                "Goal Complete",
                "The previous action was to tap on the 'Compose' topic, which fulfills the current goal.",
                "$outputDir/displayState6.png",
                listOf()
            ),
            buildStepFinishedEvent(
                Timestamp.newBuilder().setSeconds(1750247084).setNanos(116000000).build(),
                Status.SUCCEEDED
            ),
            buildStepStartedEvent(
                "Save the first post",
                Timestamp.newBuilder().setSeconds(1750247084).setNanos(116000000).build()
            ),
            buildTurnAddedEvent(
                "Save the first post by tapping on the bookmark icon.",
                "The current goal is to save the first post. The bookmark icon is available on the first post, so I will tap it.",
                "$outputDir/displayState6.png",
                listOf(
                    buildInteraction(
                        CommandType.ADB_COMMAND,
                        "ADB command: input tap 913 1922",
                        "$outputDir/displayState6.png",
                        Status.SUCCEEDED
                    )
                )
            ),
            buildTurnAddedEvent(
                "Goal Complete",
                "The current goal is to save the first post, and the bookmark icon is checked, indicating that the first post is saved.",
                "$outputDir/displayState7.png",
                listOf()
            ),
            buildStepFinishedEvent(
                Timestamp.newBuilder().setSeconds(1750247090).setNanos(48000000).build(),
                Status.SUCCEEDED
            ),
            buildStepStartedEvent(
                "Go to saved posts",
                Timestamp.newBuilder().setSeconds(1750247090).setNanos(48000000).build()
            ),
            buildTurnAddedEvent(
                "Tap on the 'Saved' tab to go to saved posts.",
                "The current goal is to go to saved posts. The 'Saved' tab is the most relevant action to achieve this goal.",
                "$outputDir/displayState7.png",
                listOf(
                    buildInteraction(
                        CommandType.ADB_COMMAND,
                        "ADB command: input tap 540 2232",
                        "$outputDir/displayState7.png",
                        Status.SUCCEEDED
                    )
                )
            ),
            buildTurnAddedEvent(
                "Goal Complete",
                "The previous action of tapping the 'Saved' tab successfully navigated the user to the saved posts screen, thus completing the goal.",
                "$outputDir/displayState8.png",
                listOf()
            ),
            buildStepFinishedEvent(
                Timestamp.newBuilder().setSeconds(1750247096).setNanos(665000000).build(),
                Status.SUCCEEDED
            ),
            buildStepStartedEvent(
                "Confirm that there is a single saved post that belongs to the 'Compose' topic",
                Timestamp.newBuilder().setSeconds(1750247096).setNanos(665000000).build()
            ),
            buildTurnAddedEvent(
                "Goal Complete",
                "The screen shows a single saved post, and the tags at the bottom include 'Compose', so the goal is complete.",
                "$outputDir/displayState8.png",
                listOf()
            ),
            buildStepFinishedEvent(
                Timestamp.newBuilder().setSeconds(1750247096).setNanos(665000000).build(),
                Status.SUCCEEDED
            ),
            buildRunFinishedEvent(
                Status.SUCCEEDED
            )
        )
        assertJourneyEvents(result, "$DEVICE_SERIAL > simple.journey.xml", "", expectedEvents)
    }

    @Test
    fun `run failed journey`() {
        val build = rule.build
        val appProject = build.androidApplication()
        appProject.files.add(
            "src/journeysTest/simple.journey.xml",
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
        val result = executor.expectFailure().withArgument("-DroboResultsPath=$roboResultsPath")
            .withEnvironmentVariables(mapOf("JOURNEYS_ENABLE_STDOUT_REPORT" to "true"))
            .run(":app:testJourneysTestT1DebugTestSuite")

        val outputDir =
            appProject.buildDir.resolve("intermediates/debug/testJourneysTestT1DebugTestSuite/results/$DEVICE_SERIAL/simple")
        assertThat(outputDir.resolve("robo_results.pb")).exists()
        assertThat(outputDir.resolve("journey_results.pb")).exists()
        for (i in 0 until 8) {
            assertThat(outputDir.resolve("displayState$i.png")).exists()
        }
        val expectedEvents = listOf(
            buildRunStartedEvent(
                prompts = listOf(
                    "Tap on the search icon and enter 'Compose' into the search bar at the top of the screen",
                    "Tap on the 'Compose' topic",
                    "Save the first post",
                    "Go to saved posts",
                    "Confirm that there is a single saved post that belongs to the 'Compose' topic"
                ),
                metadata = mapOf("deviceId" to DEVICE_SERIAL, "deviceName" to DEVICE_NAME)
            ),
            buildStepStartedEvent(
                "Tap on the search icon and enter 'Compose' into the search bar at the top of the screen",
                Timestamp.newBuilder().setSeconds(1750247037).setNanos(165000000).build()
            ),
            buildTurnAddedEvent(
                "Tap on the search icon",
                "The current goal is to tap on the search icon and enter 'Compose'. The search icon is available on the screen. So, I will tap on it.",
                "$outputDir/displayState1.png",
                listOf(
                    buildInteraction(
                        CommandType.CLICK,
                        "CLICK on element with ID 1",
                        "$outputDir/displayState1.png",
                        Status.SUCCEEDED
                    )
                )
            ),
            buildTurnAddedEvent(
                "Enter 'Compose' into the search field",
                "The current goal is to tap on the search icon and enter 'Compose'. The search icon has already been tapped in the previous step. Now I need to enter 'Compose' into the search field.",
                "$outputDir/displayState2.png",
                listOf(
                    buildInteraction(
                        CommandType.ENTER_TEXT,
                        "ENTER_TEXT on element with ID 2",
                        "$outputDir/displayState2.png",
                        Status.SUCCEEDED
                    ),
                    buildInteraction(
                        CommandType.ADB_COMMAND,
                        "ADB command: input keyevent ENTER",
                        "$outputDir/displayState3.png",
                        Status.SUCCEEDED
                    )
                )
            ),
            buildTurnAddedEvent(
                "Goal Complete",
                "The previous steps involved tapping on the search icon and entering 'Compose' into the search field. So the goal is complete.",
                "$outputDir/displayState4.png",
                listOf()
            ),
            buildStepFinishedEvent(
                Timestamp.newBuilder().setSeconds(1750247072).setNanos(407000000).build(),
                Status.SUCCEEDED
            ),
            buildStepStartedEvent(
                "Tap on the 'Compose' topic",
                Timestamp.newBuilder().setSeconds(1750247072).setNanos(407000000).build()
            ),
            buildTurnAddedEvent(
                "Tap on the 'Compose' topic",
                "The current goal is to tap on the 'Compose' topic. The element with the text 'Compose' is tappable and is the correct element to tap.",
                "$outputDir/displayState4.png",
                listOf(
                    buildInteraction(
                        CommandType.CLICK,
                        "CLICK on element with ID 4",
                        "$outputDir/displayState4.png",
                        Status.SUCCEEDED
                    )
                )
            ),
            buildTurnAddedEvent(
                "Goal Complete",
                "The previous action was to tap on the 'Compose' topic, which fulfills the current goal.",
                "$outputDir/displayState5.png",
                listOf()
            ),
            buildStepFinishedEvent(
                Timestamp.newBuilder().setSeconds(1750247076).setNanos(824000000).build(),
                Status.SUCCEEDED
            ),
            buildStepStartedEvent(
                "Save the first post",
                Timestamp.newBuilder().setSeconds(1750247076).setNanos(824000000).build()
            ),
            buildTurnAddedEvent(
                "Save the first post by tapping on the bookmark icon.",
                "Reasoning not available",
                "$outputDir/displayState5.png",
                listOf(
                    buildInteraction(
                        CommandType.CLICK,
                        "CLICK on element with ID 5",
                        "$outputDir/displayState5.png",
                        Status.SUCCEEDED
                    )
                )
            ),
            buildTurnAddedEvent(
                "Goal Failed",
                "The goal was to save the first post. Tapping on the bookmark icon should have saved the post, but it led to a Chrome welcome screen instead. This is unexpected and indicates a problem.",
                "$outputDir/displayState7.png",
                listOf()
            ),
            buildStepFinishedEvent(
                Timestamp.newBuilder().setSeconds(1750247096).setNanos(665000000).build(),
                Status.FAILED,
                "Prompt failed with model response: The goal was to save the first post. Tapping on the bookmark icon should have saved the post, but it led to a Chrome welcome screen instead. This is unexpected and indicates a problem."
            ),
            buildRunFinishedEvent(
                Status.FAILED,
                "Prompt failed with model response: The goal was to save the first post. Tapping on the bookmark icon should have saved the post, but it led to a Chrome welcome screen instead. This is unexpected and indicates a problem."
            )
        )
        assertJourneyEvents(result, "$DEVICE_SERIAL > simple.journey.xml", "", expectedEvents)
    }

    private fun createRoboResults(roboResultsResourceName: String, roboResultsPath: Path) {
        val roboResultsBytes = Resources.toByteArray(
            Resources.getResource(
                JourneysConnectedTest::class.java, roboResultsResourceName
            ),
        )
        Files.write(roboResultsPath, roboResultsBytes)
    }

    // ===================================================================
    // Proto Builder Helpers
    // ===================================================================

    private fun buildResult(status: Status, errorMessage: String = ""): Result =
        Result.newBuilder()
            .setStatus(status)
            .setErrorMessage(errorMessage)
            .build()

    private fun buildInteraction(
        type: CommandType,
        command: String,
        screenshotPath: String,
        status: Status,
        errorMessage: String = ""
    ): Interaction {
        val result = buildResult(status, errorMessage)
        return Interaction.newBuilder()
            .setType(type)
            .setCommand(command)
            .addArtifactsBefore(
                Artifact.newBuilder()
                    .setType(ArtifactType.SCREENSHOT)
                    .setUri(screenshotPath)
                    .build()
            )
            .setResult(result)
            .build()
    }

    private fun buildTurnAddedEvent(
        turnDescription: String,
        turnReasoning: String,
        turnScreenshotPath: String,
        turnInteractions: List<Interaction>,
    ): JourneyRunEvent {
        val turn = Turn.newBuilder()
            .setDescription(turnDescription)
            .setReasoning(turnReasoning)
            .addArtifactsBefore(
                Artifact.newBuilder()
                    .setType(ArtifactType.SCREENSHOT)
                    .setUri(turnScreenshotPath)
            )
            .addAllInteractions(turnInteractions)
            .build()
        val turnAdded = TurnAdded.newBuilder().setTurn(turn).build()
        return JourneyRunEvent.newBuilder().setTurnAdded(turnAdded).build()
    }

    private fun buildStepStartedEvent(
        promptText: String,
        startTimestamp: Timestamp
    ): JourneyRunEvent {
        val init = Step.Initialization.newBuilder()
            .setPromptText(promptText)
            .setStartTimestamp(startTimestamp)
            .build()
        val stepStarted = StepStarted.newBuilder().setInitialization(init).build()
        return JourneyRunEvent.newBuilder().setStepStarted(stepStarted).build()
    }

    private fun buildStepFinishedEvent(
        endTimestamp: Timestamp,
        status: Status,
        errorMessage: String = ""
    ): JourneyRunEvent {
        val result = buildResult(status, errorMessage)
        val completion = Step.Completion.newBuilder()
            .setEndTimestamp(endTimestamp)
            .setResult(result)
            .build()
        val stepFinished = StepFinished.newBuilder().setCompletion(completion).build()
        return JourneyRunEvent.newBuilder().setStepFinished(stepFinished).build()
    }

    private fun buildRunStartedEvent(
        prompts: List<String>,
        metadata: Map<String, String> = emptyMap()
    ): JourneyRunEvent {
        val init = JourneyRunResult.Initialization.newBuilder()
            .addAllPrompts(prompts)
            .putAllMetadata(metadata)
            // Note: We do not set the 'id' field here.
            // The default 'id' will be cleared from the actual event before comparison.
            .build()
        val runStarted = RunStarted.newBuilder().setInitialization(init).build()
        return JourneyRunEvent.newBuilder().setRunStarted(runStarted).build()
    }

    private fun buildRunFinishedEvent(
        status: Status,
        errorMessage: String = ""
    ): JourneyRunEvent {
        val result = buildResult(status, errorMessage)
        val completion = JourneyRunResult.Completion.newBuilder()
            .setResult(result)
            .build()
        val runFinished = RunFinished.newBuilder().setCompletion(completion).build()
        return JourneyRunEvent.newBuilder().setRunFinished(runFinished).build()
    }

    /**
     * Asserts that the JOURNEYS_TEST_ARTIFACT output from the test result matches the expected events.
     */
    private fun assertJourneyEvents(
        result: GradleBuildResult,
        startLinePrefix: String,
        endLinePrefix: String,
        expectedEvents: List<JourneyRunEvent>
    ) {
        val artifactLines = extractJourneyArtifactsBetween(
            result,
            startLinePrefix, endLinePrefix
        )

        val actualEvents =
            artifactLines.map { (description, encodedProto) ->
                Pair(
                    description,
                    JourneyRunEvent.parseFrom(Base64.getDecoder().decode(encodedProto))
                )
            }

        assertEquals(
            expectedEvents.size,
            actualEvents.size,
            "Number of expected events (${expectedEvents.size}) does not match number of actual events (${actualEvents.size}). The complete list of actual events: $actualEvents"
        )

        var journeyRunStartId = ""

        actualEvents.forEachIndexed { index, (description, actualEvent) ->
            val expectedEvent = expectedEvents[index]

            assertEquals(
                expectedEvent.eventPayloadCase.name,
                description,
                "Event description mismatch at index $index"
            )

            assertEquals(
                expectedEvent.eventPayloadCase,
                actualEvent.eventPayloadCase,
                "Event type mismatch at index $index"
            )

            // Capture and check the runtime journey_run_id for consistency.
            if (actualEvent.eventPayloadCase == JourneyRunEvent.EventPayloadCase.RUN_STARTED) {
                journeyRunStartId = actualEvent.journeyRunId
                assertTrue(
                    journeyRunStartId.isNotEmpty(),
                    "journeyRunStartId should not be empty"
                )
                // Check the nested ID in RunStarted.Initialization.
                assertEquals(
                    journeyRunStartId,
                    actualEvent.runStarted.initialization.id,
                    "RunStarted initialization.id does not match event journey_run_id"
                )
            } else {
                // All other events must match the captured ID.
                assertEquals(
                    journeyRunStartId,
                    actualEvent.journeyRunId,
                    "journeyRunId in ${actualEvent.eventPayloadCase} does not match the start run ID."
                )
            }

            // Clear the fields which we cannot compare:
            // - The default IDs -- we already compared ID above using the actual ID from run started event.
            // - The start and end timestamp of the journey.
            val actualEventForCompare = actualEvent.toBuilder()
                .clearJourneyRunId()
                .also {
                    if (it.hasRunStarted()) {
                        val runStartedBuilder = it.runStarted.toBuilder()
                        runStartedBuilder.initializationBuilder.clearId()
                        runStartedBuilder.initializationBuilder.clearStartTimestamp()
                        it.setRunStarted(runStartedBuilder)
                    }
                    if (it.hasRunFinished()) {
                        val runFinishedBuilder = it.runFinished.toBuilder()
                        runFinishedBuilder.completionBuilder.clearEndTimestamp()
                        it.setRunFinished(runFinishedBuilder)
                    }
                }
                .build()

            assertEquals(
                expectedEvent,
                actualEventForCompare,
                "Event payload mismatch at index $index"
            )
        }
    }

    /**
     * Scans stdout and extracts journey artifact lines that are located between two boundary lines,
     * identified by prefixes.
     *
     * The function starts scanning from the beginning of stdout. It ignores all lines until it
     * finds a line that starts with [startLinePrefix]. After that, it starts processing lines.
     *
     * For each line, it checks if the line matches against a journey artifact regex.
     * If the regex matches, the key and value of the corresponding artifact is obtained via first
     * and second match groups respectively.
     *
     * The processing stops when a line starting with [endLinePrefix] is found, or the end of
     * stdout is reached (if [endLinePrefix] is blank). The line with [endLinePrefix] is not
     * processed.
     *
     * @param result The GradleBuildResult for which the output lines will be processed.
     * @param startLinePrefix The prefix of the line that marks the beginning of the section to process.
     * @param endLinePrefix The prefix of the line that marks the end of the section to process.
     *                      If blank, the processing goes until the end of the output.
     * @return A list of pair of strings, where each pair represents (key, value) of a journey artifact.
     */
    private fun extractJourneyArtifactsBetween(
        result: GradleBuildResult,
        startLinePrefix: String,
        endLinePrefix: String
    ): List<Pair<String, String>> {
        val extractedArtifacts = mutableListOf<Pair<String, String>>()
        var foundStart = false
        var foundEnd = false
        val matchRegex =
            Regex("<JOURNEYS_TEST_ARTIFACT><DESCRIPTION>(.*)</DESCRIPTION><ENCODED_PROTO>(.*)</ENCODED_PROTO></JOURNEYS_TEST_ARTIFACT>")

        result.processOutput processLoop@{ outputLine ->
            val line = outputLine.trim()
            if (line.startsWith(startLinePrefix)) {
                foundStart = true
            }
            if (!foundStart) {
                return@processLoop
            }
            if (endLinePrefix.isNotBlank() && line.startsWith(endLinePrefix)) {
                foundEnd = true
            }
            if (foundEnd) {
                return@processLoop
            }
            val match = matchRegex.find(line)
            if (match != null && match.groupValues.size == 3) {
                extractedArtifacts.add(Pair(match.groupValues[1], match.groupValues[2]))
            }
        }
        return extractedArtifacts
    }
}
