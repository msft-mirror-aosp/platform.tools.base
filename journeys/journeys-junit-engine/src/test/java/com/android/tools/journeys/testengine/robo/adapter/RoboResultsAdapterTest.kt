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

package com.android.tools.journeys.testengine.robo.adapter

import androidx.test.tools.crawler.output.Crawl
import com.android.tools.journeys.proto.Artifact
import com.android.tools.journeys.proto.ArtifactType
import com.android.tools.journeys.proto.CommandType
import com.android.tools.journeys.proto.Interaction
import com.android.tools.journeys.proto.JourneyRunEvent
import com.android.tools.journeys.proto.Result
import com.android.tools.journeys.proto.Status
import com.android.tools.journeys.proto.Step
import com.android.tools.journeys.proto.StepFinished
import com.android.tools.journeys.proto.StepStarted
import com.android.tools.journeys.proto.Turn
import com.android.tools.journeys.proto.TurnAdded
import com.android.tools.journeys.testengine.adapter.consumer.JourneyRunEventConsumer
import com.android.tools.journeys.testengine.robo.adapter.CrawlProcessingState
import com.google.common.io.Resources
import com.google.protobuf.TextFormat
import com.google.protobuf.Timestamp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.assertEquals

class RoboResultAdapterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var tempDir: Path
    private lateinit var mockConsumer: JourneyRunEventConsumer
    private lateinit var capturedEvents: MutableList<JourneyRunEvent>

    @Before
    fun setUp() {
        tempDir = tempFolder.root.toPath()
        capturedEvents = mutableListOf()
        mockConsumer = mock<JourneyRunEventConsumer> {
            on { onEvent(org.mockito.kotlin.any()) }.then {
                capturedEvents.add(it.getArgument(0))
            }
        }
    }

    @Test
    fun `reports correctly for proto with incorrect contextual roboscripts`() {
        val crawl = loadCrawlFromResource("robo_results_incorrect_contextual.textproto")

        val prompts =
            listOf(
                "Tap on the search icon and enter \'Compose\'",
                "Tap on the \'Compose\' topic",
                "Save the first post",
                "Go to saved posts",
                "Confirm that there is a single saved post that belongs to the \'Compose\' topic",
            )
        val state = CrawlProcessingState("journeyRunId", prompts)
        val adapter = RoboResultAdapter(
            RoboResultAdapter.RoboResultAdapterConfig(
                mockConsumer,
                state,
                tempDir
            )
        )

        adapter.onCrawlReceived(crawl)

        val expectedEvents =
            listOf(
                buildStepStartedEvent(
                    "Tap on the search icon and enter \'Compose\'",
                    Timestamp.newBuilder().setSeconds(1746708001).setNanos(412000000).build()
                ),
                buildTurnAddedEvent(
                    "Tap on the search icon to open the search bar.",
                    "The goal is to tap on the search icon and enter \'Compose\'. The search icon is available on the screen, so I should tap on it.",
                    "${tempDir.resolve("displayState3.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 75 148",
                            "${tempDir.resolve("displayState3.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildTurnAddedEvent(
                    "Enter \'Compose\' into the search bar.",
                    "The goal is to tap on the search icon and enter \'Compose\'. I have already tapped on the search icon. Now I need to enter \'Compose\' into the search bar.",
                    "${tempDir.resolve("displayState4.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 603 178",
                            "${tempDir.resolve("displayState4.png")}",
                            buildResult(Status.SUCCEEDED)
                        ),
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input text Compose",
                            "${tempDir.resolve("displayState5.png")}",
                            buildResult(Status.SUCCEEDED)
                        ),
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input keyevent ENTER",
                            "${tempDir.resolve("displayState6.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildTurnAddedEvent(
                    "Goal Complete",
                    "I have already tapped on the search icon and entered \'Compose\' into the search bar. The screen now shows the search results for \'Compose\'.",
                    "${tempDir.resolve("displayState7.png")}",
                    listOf(),
                    buildResult(Status.SUCCEEDED)
                ),
                buildStepFinishedEvent(
                    Timestamp.newBuilder().setSeconds(1746708026).setNanos(39000000).build(),
                    buildResult(Status.SUCCEEDED)
                ),
                buildStepStartedEvent(
                    "Tap on the \'Compose\' topic",
                    Timestamp.newBuilder().setSeconds(1746708026).setNanos(39000000).build()
                ),
                buildTurnAddedEvent(
                    "Tap on the \'Compose\' topic.",
                    "The current goal is to tap on the \'Compose\' topic. The \'Compose\' topic is visible on the screen, so I should tap on it.",
                    "${tempDir.resolve("displayState7.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 346 824",
                            "${tempDir.resolve("displayState7.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildTurnAddedEvent(
                    "Goal Complete",
                    "The current goal is to tap on the \'Compose\' topic. I have already tapped on the \'Compose\' topic, so the goal is complete.",
                    "${tempDir.resolve("displayState8.png")}",
                    listOf(),
                    buildResult(Status.SUCCEEDED)
                ),
                buildStepFinishedEvent(
                    Timestamp.newBuilder().setSeconds(1746708030).setNanos(312000000).build(),
                    buildResult(Status.SUCCEEDED)
                ),
                buildStepStartedEvent(
                    "Save the first post",
                    Timestamp.newBuilder().setSeconds(1746708030).setNanos(312000000).build()
                ),
                buildTurnAddedEvent(
                    "Tap on the bookmark icon to save the first post.",
                    "The goal is to save the first post. The bookmark icon is the way to save the post.",
                    "${tempDir.resolve("displayState8.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 913 1955",
                            "${tempDir.resolve("displayState8.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildTurnAddedEvent(
                    "Goal Complete",
                    "The bookmark icon was tapped, and the goal is to save the first post.",
                    "${tempDir.resolve("displayState9.png")}",
                    listOf(),
                    buildResult(Status.SUCCEEDED)
                ),
                buildStepFinishedEvent(
                    Timestamp.newBuilder().setSeconds(1746708034).setNanos(536000000).build(),
                    buildResult(Status.SUCCEEDED)
                ),
                buildStepStartedEvent(
                    "Go to saved posts",
                    Timestamp.newBuilder().setSeconds(1746708034).setNanos(536000000).build()
                ),
                buildTurnAddedEvent(
                    "Tap on the 'Saved' tab to go to saved posts.",
                    "The current goal is to go to saved posts. The 'Saved' tab is visible at the bottom of the screen, so I should tap on it.",
                    "${tempDir.resolve("displayState9.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 540 2232",
                            "${tempDir.resolve("displayState9.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildTurnAddedEvent(
                    "Goal Failed",
                    "The \'Saved\' tab should have navigated to the saved posts within the app. Since a browser window opened instead, something went wrong, and the goal cannot be completed.",
                    "${tempDir.resolve("displayState11.png")}",
                    listOf(),
                    buildResult(Status.SUCCEEDED)
                ),
                buildStepFinishedEvent(
                    Timestamp.newBuilder().setSeconds(1746708049).setNanos(46000000).build(),
                    buildResult(
                        Status.FAILED,
                        "Journey terminated unexpectedly with model response: The 'Saved' tab should have navigated to the saved posts within the app. Since a browser window opened instead, something went wrong, and the goal cannot be completed."
                    )
                )
            )

        verifyEvents(expectedEvents)
    }

    @Test
    fun `reports correctly for proto with prompt evaluation limit reached`() {
        val crawl = loadCrawlFromResource("robo_results_eval_limit.textproto")

        val prompts =
            listOf(
                "Tap on the search icon and enter \'Compose\' into the search bar at the top of the screen",
                "Tap on the \'Compose\' topic",
                "Save the first post",
                "Go to saved posts",
                "Confirm that there is a single saved post that belongs to the \'Compose\' topic",
            )
        val state = CrawlProcessingState("journeyRunId", prompts)
        val adapter = RoboResultAdapter(
            RoboResultAdapter.RoboResultAdapterConfig(
                mockConsumer,
                state,
                tempDir
            )
        )

        adapter.onCrawlReceived(crawl)

        val expectedEvents =
            listOf(
                buildStepStartedEvent(
                    "Tap on the search icon and enter \'Compose\' into the search bar at the top of the screen",
                    Timestamp.newBuilder().setSeconds(1746715525).setNanos(770000000).build()
                ),
                buildTurnAddedEvent(
                    "Tap on the search icon to open the search bar.",
                    "The current goal is to tap on the search icon and enter \'Compose\' into the search bar. The search bar is not currently open, so I need to tap on the search icon first.",
                    "${tempDir.resolve("displayState1.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 75 148",
                            "${tempDir.resolve("displayState1.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildTurnAddedEvent(
                    "Enter \'Compose\' into the search bar at the top of the screen.",
                    "The current goal is to enter \'Compose\' into the search bar. The search bar is currently open and focused, so I need to enter the text.",
                    "${tempDir.resolve("displayState2.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 603 178",
                            "${tempDir.resolve("displayState2.png")}",
                            buildResult(Status.SUCCEEDED)
                        ),
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input text Compose",
                            "${tempDir.resolve("displayState3.png")}",
                            buildResult(Status.SUCCEEDED)
                        ),
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input keyevent ENTER",
                            "${tempDir.resolve("displayState4.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildTurnAddedEvent(
                    "Tap on the 'Next' button to accept the stylus input.",
                    "The current goal is to enter \'Compose\' into the search bar. The stylus input is currently active, and the text \'Compose\' has been written. I need to tap the 'Next' button to accept the stylus input and enter the text into the search bar.",
                    "${tempDir.resolve("displayState5.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 859 2211",
                            "${tempDir.resolve("displayState5.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildTurnAddedEvent(
                    "Tap on the 'Cancel' button to dismiss the stylus input.",
                    "The current goal is to enter \'Compose\' into the search bar. The stylus input is currently active, but the text is not correct. I need to tap the 'Cancel' button to dismiss the stylus input and try again.",
                    "${tempDir.resolve("displayState6.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 599 2211",
                            "${tempDir.resolve("displayState6.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildTurnAddedEvent(
                    "Enter \'Compose\' into the search bar at the top of the screen.",
                    "The current goal is to enter \'Compose\' into the search bar. The search bar is currently open and focused, but the previous attempt to enter the text was interrupted by the stylus input. I need to enter the text again.",
                    "${tempDir.resolve("displayState7.png")}",
                    listOf(
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input tap 603 178",
                            "${tempDir.resolve("displayState7.png")}",
                            buildResult(Status.SUCCEEDED)
                        ),
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input text Compose",
                            "${tempDir.resolve("displayState8.png")}",
                            buildResult(Status.SUCCEEDED)
                        ),
                        buildInteraction(
                            CommandType.ADB_COMMAND,
                            "ADB command: input keyevent ENTER",
                            "${tempDir.resolve("displayState9.png")}",
                            buildResult(Status.SUCCEEDED)
                        )
                    ),
                    buildResult(Status.SUCCEEDED)
                ),
                buildStepFinishedEvent(
                    Timestamp.newBuilder().setSeconds(1746715559).setNanos(148000000).build(),
                    buildResult(
                        Status.FAILED,
                        "Could not successfully complete the action in max allowed attempts"
                    )
                )
            )
        verifyEvents(expectedEvents)
    }

    /** Utility function to load a Crawl proto from a textproto resource file. */
    private fun loadCrawlFromResource(resourceName: String): Crawl {
        val roboResults =
            Resources.toString(Resources.getResource(resourceName), StandardCharsets.UTF_8)
        val builder = Crawl.newBuilder()
        TextFormat.merge(roboResults, builder)
        return builder.build()
    }

    private fun verifyEvents(expectedEvents: List<JourneyRunEvent>) {
        assertEquals(
            expectedEvents.size,
            capturedEvents.size,
            "Number of events mismatch. The complete list of actual events: $capturedEvents"
        )
        for (i in expectedEvents.indices) {
            assertEquals(
                expectedEvents[i],
                capturedEvents[i],
                "Event mismatch at index $i"
            )
        }
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
        result: Result
    ): Interaction {
        return Interaction.newBuilder()
            .setType(type)
            .setCommand(command)
            .addArtifactsBefore(
                Artifact.newBuilder()
                    .setType(ArtifactType.SCREENSHOT)
                    .setUri(screenshotPath)
            )
            .setResult(result)
            .build()
    }

    private fun buildTurnAddedEvent(
        turnDescription: String,
        turnReasoning: String,
        turnScreenshotPath: String,
        turnInteractions: List<Interaction>,
        result: Result
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
        return JourneyRunEvent.newBuilder()
            .setJourneyRunId("journeyRunId")
            .setTurnAdded(turnAdded)
            .build()
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
        return JourneyRunEvent.newBuilder()
            .setJourneyRunId("journeyRunId")
            .setStepStarted(stepStarted)
            .build()
    }

    private fun buildStepFinishedEvent(
        endTimestamp: Timestamp,
        result: Result
    ): JourneyRunEvent {
        val completion = Step.Completion.newBuilder()
            .setEndTimestamp(endTimestamp)
            .setResult(result)
            .build()
        val stepFinished = StepFinished.newBuilder().setCompletion(completion).build()
        return JourneyRunEvent.newBuilder()
            .setJourneyRunId("journeyRunId")
            .setStepFinished(stepFinished)
            .build()
    }
}
