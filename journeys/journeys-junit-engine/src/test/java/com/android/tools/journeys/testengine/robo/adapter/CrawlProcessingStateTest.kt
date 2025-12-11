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

import androidx.test.tools.crawler.output.Action
import androidx.test.tools.crawler.output.ActionDetails
import androidx.test.tools.crawler.output.AdbCommandAction
import androidx.test.tools.crawler.output.BackAction
import androidx.test.tools.crawler.output.DisplayState
import androidx.test.tools.crawler.output.HomeAction
import androidx.test.tools.crawler.output.LaunchAction
import androidx.test.tools.crawler.output.ModelDetails
import androidx.test.tools.crawler.output.Point
import androidx.test.tools.crawler.output.PointTapAction
import androidx.test.tools.crawler.output.RoboScriptDetails
import androidx.test.tools.crawler.output.ScreenElement
import androidx.test.tools.crawler.output.ScreenState
import androidx.test.tools.crawler.output.TargetAction
import androidx.test.tools.crawler.output.TargetAction.TargetActionType
import androidx.test.tools.crawler.output.TwoPointGestureAction
import androidx.test.tools.crawler.output.WaitAction
import com.android.tools.journeys.proto.ArtifactType
import com.android.tools.journeys.proto.Result
import com.android.tools.journeys.proto.Status
import com.android.tools.journeys.testengine.robo.platform.RoboConfigConstants
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.protobuf.Duration
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class CrawlProcessingStateTest {

    private lateinit var state: CrawlProcessingState

    @Before
    fun setUp() {
        state = CrawlProcessingState("journey-1", listOf("prompt-1", "prompt-2"))
    }

    @Test
    fun testGetJourneyRunId_returnsCorrectId() {
        assertThat(state.getJourneyRunId()).isEqualTo("journey-1")
    }

    @Test
    fun testGetPromptText_returnsCorrectPromptForIndex() {
        assertThat(state.getPromptText(0)).isEqualTo("prompt-1")
        assertThat(state.getPromptText(1)).isEqualTo("prompt-2")
    }

    @Test
    fun testSetJourneyResult_updatesJourneyResult() {
        assertThat(state.journeyResult.status).isEqualTo(Status.SUCCEEDED)

        val result = Result.newBuilder().setStatus(Status.FAILED).setErrorMessage("Error").build()
        state.setJourneyResult(result)

        assertThat(state.journeyResult).isEqualTo(result)
    }

    @Test
    fun testAddScreenshot_savesScreenshotToFile() {
        val tempDir = createTempDirectory()
        val displayState =
            DisplayState.newBuilder()
                .setDisplayStateId(1)
                .setScreenshot(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
                .build()

        state.addScreenshot(displayState, tempDir)

        val expectedPath = tempDir.resolve("displayState1.png")
        assertThat(Files.exists(expectedPath)).isTrue()
        assertThat(Files.readAllBytes(expectedPath)).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun testInitNewTurn_initializesTurnWithModelDetails() {
        val tempDir = createTempDirectory()
        val displayState =
            DisplayState.newBuilder()
                .setDisplayStateId(1)
                .setScreenshot(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
                .build()
        state.addScreenshot(displayState, tempDir)

        val modelDetails = ModelDetails.newBuilder().setId(1).setDescription("Test Turn").build()
        state.initNewTurn(1, modelDetails)

        assertThat(state.currentModelDetailsId).isEqualTo(1)
        val turn = state.finalizeCurrentTurn()
        assertThat(turn.description).isEqualTo("Test Turn")
        assertThat(turn.reasoning).isEqualTo(RoboConfigConstants.DEFAULT_MODEL_REASONING)
        assertThat(turn.artifactsBeforeCount).isEqualTo(1)
        assertThat(turn.getArtifactsBefore(0).type).isEqualTo(ArtifactType.SCREENSHOT)
        assertThat(turn.getArtifactsBefore(0).uri).endsWith("displayState1.png")
    }

    @Test
    fun testAddInteractionToCurrentTurn_addsInteractionToTurn() {
        val tempDir = createTempDirectory()
        val displayState =
            DisplayState.newBuilder()
                .setDisplayStateId(1)
                .setScreenshot(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
                .build()
        state.addScreenshot(displayState, tempDir)

        val modelDetails = ModelDetails.newBuilder().setId(1).build()
        state.initNewTurn(1, modelDetails)

        val action = Action.newBuilder()
            .setDisplayStateId(1)
            .setExecutionResult(Action.ExecutionResult.ACTION_SUCCESS)
            .setDetails(
                ActionDetails.newBuilder()
                    .setLaunchAction(LaunchAction.newBuilder().setAppPackageName("com.example.app"))
            )
            .build()
        state.addInteractionToCurrentTurn(action)

        val turn = state.finalizeCurrentTurn()
        assertThat(turn.interactionsCount).isEqualTo(1)
        val interaction = turn.getInteractions(0)
        assertThat(interaction.command).isEqualTo("Launched package com.example.app")
    }

    @Test
    fun testMakeActionString_returnsCorrectString() {
        val tempDir = createTempDirectory()
        val displayState =
            DisplayState.newBuilder()
                .setDisplayStateId(1)
                .setScreenshot(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
                .build()
        state.addScreenshot(displayState, tempDir)

        val modelDetails = ModelDetails.newBuilder().setId(1).build()
        state.initNewTurn(1, modelDetails)

        val testCases = mapOf(
            Action.newBuilder()
                .setDisplayStateId(1)
                .setDetails(
                    ActionDetails.newBuilder()
                        .setWaitAction(
                            WaitAction.newBuilder()
                                .setWaitDuration(Duration.newBuilder().setSeconds(2))
                        )
                )
                .build() to "Waited for 2 seconds",
            Action.newBuilder()
                .setDisplayStateId(1)
                .setDetails(
                    ActionDetails.newBuilder()
                        .setBackAction(BackAction.getDefaultInstance())
                )
                .build() to "Pressed back",
            Action.newBuilder()
                .setDisplayStateId(1)
                .setDetails(
                    ActionDetails.newBuilder()
                        .setHomeAction(HomeAction.getDefaultInstance())
                )
                .build() to "Pressed home",
            Action.newBuilder()
                .setDisplayStateId(1)
                .setDetails(
                    ActionDetails.newBuilder()
                        .setPointTapAction(
                            PointTapAction.newBuilder()
                                .setCoordinate(
                                    Point.newBuilder()
                                        .setXCoordinate(100)
                                        .setYCoordinate(200)
                                )
                        )
                )
                .build() to "Point tap at (100, 200)",
            Action.newBuilder()
                .setDisplayStateId(1)
                .setDetails(
                    ActionDetails.newBuilder()
                        .setAdbCommandAction(
                            AdbCommandAction.newBuilder()
                                .setCommand("test command")
                        )
                )
                .build() to "ADB command: test command",
            Action.newBuilder()
                .setDisplayStateId(1)
                .setDetails(
                    ActionDetails.newBuilder()
                        .setTwoPointGestureAction(
                            TwoPointGestureAction.newBuilder()
                                .setFirstPointStart(
                                    Point.newBuilder()
                                        .setXCoordinate(1)
                                        .setYCoordinate(2)
                                )
                                .setFirstPointEnd(
                                    Point.newBuilder()
                                        .setXCoordinate(3)
                                        .setYCoordinate(4)
                                )
                                .setSecondPointStart(
                                    Point.newBuilder()
                                        .setXCoordinate(5)
                                        .setYCoordinate(6)
                                )
                                .setSecondPointEnd(
                                    Point.newBuilder()
                                        .setXCoordinate(7)
                                        .setYCoordinate(8)
                                )
                        )
                )
                .build() to "Two point gesture from ((1, 2), (3, 4)) to ((5, 6), (7, 8))"
        )

        testCases.forEach { (action, expectedString) ->
            state.addInteractionToCurrentTurn(action)
            val turn = state.finalizeCurrentTurn()
            assertThat(turn.getInteractions(0).command).isEqualTo(expectedString)
            state.initNewTurn(1, modelDetails)
        }
    }

    @Test
    fun testMakeActionString_forClickTargetAction_returnsCorrectString() {
        val tempDir = createTempDirectory()
        val displayState =
            DisplayState.newBuilder()
                .setDisplayStateId(1)
                .setScreenshot(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
                .build()
        state.addScreenshot(displayState, tempDir)

        val modelDetails = ModelDetails.newBuilder().setId(1).build()
        state.initNewTurn(1, modelDetails)

        val screenElement = ScreenElement.newBuilder()
            .setScreenElementId("element-id")
            .setAndroidClassName("android.widget.Button")
            .setContentDescription("Click Me")
            .setResourceName("my_button")
            .build()
        val screenState = ScreenState.newBuilder()
            .setScreenStateId(1)
            .addRootElements(screenElement)
            .build()
        state.addScreenState(screenState)

        val targetAction = TargetAction.newBuilder()
            .setActionType(TargetActionType.CLICK)
            .setScreenElementId("element-id")
            .build()
        val action = Action.newBuilder()
            .setScreenStateId(1)
            .setDisplayStateId(1)
            .setDetails(
                ActionDetails.newBuilder()
                    .setTargetAction(targetAction)
            )
            .build()

        state.addInteractionToCurrentTurn(action)
        val turn = state.finalizeCurrentTurn()
        val interaction = turn.getInteractions(0)

        assertThat(interaction.command).isEqualTo("CLICK on Click Me Button (id: my_button)")
    }

    @Test
    fun testMakeActionString_forClickTargetAction_returnsCorrectString_whenScreenElementMissing() {
        val tempDir = createTempDirectory()
        val displayState =
            DisplayState.newBuilder()
                .setDisplayStateId(1)
                .setScreenshot(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
                .build()
        state.addScreenshot(displayState, tempDir)

        val modelDetails = ModelDetails.newBuilder().setId(1).build()
        state.initNewTurn(1, modelDetails)

        val screenState = ScreenState.newBuilder()
            .setScreenStateId(1)
            .build()
        state.addScreenState(screenState)

        val targetAction = TargetAction.newBuilder()
            .setActionType(TargetActionType.CLICK)
            .setScreenElementId("element-id")
            .build()
        val action = Action.newBuilder()
            .setScreenStateId(1)
            .setDisplayStateId(1)
            .setDetails(
                ActionDetails.newBuilder()
                    .setTargetAction(targetAction)
            )
            .build()

        state.addInteractionToCurrentTurn(action)
        val turn = state.finalizeCurrentTurn()
        val interaction = turn.getInteractions(0)

        assertThat(interaction.command).isEqualTo("CLICK")
    }

    @Test
    fun testMakeActionString_forEnterTextTargetAction_returnsCorrectString() {
        val tempDir = createTempDirectory()
        val displayState =
            DisplayState.newBuilder()
                .setDisplayStateId(1)
                .setScreenshot(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
                .build()
        state.addScreenshot(displayState, tempDir)

        val modelDetails = ModelDetails.newBuilder().setId(1).build()
        state.initNewTurn(1, modelDetails)

        val screenElement = ScreenElement.newBuilder()
            .setScreenElementId("element-id-2")
            .setAndroidClassName("android.widget.EditText")
            .setTextProperties(ScreenElement.TextProperties.newBuilder().setText("Some Text"))
            .setResourceName("my_edit_text")
            .build()
        val screenState = ScreenState.newBuilder()
            .setScreenStateId(1)
            .addRootElements(screenElement)
            .build()
        state.addScreenState(screenState)

        val targetAction = TargetAction.newBuilder()
            .setActionType(TargetActionType.ENTER_TEXT)
            .setScreenElementId("element-id-2")
            .setEnterTextString("Hello World")
            .build()

        val action = Action.newBuilder()
            .setScreenStateId(1)
            .setDisplayStateId(1)
            .setDetails(
                ActionDetails.newBuilder()
                    .setTargetAction(targetAction)
            )
            .build()

        state.addInteractionToCurrentTurn(action)
        val turn = state.finalizeCurrentTurn()
        val interaction = turn.getInteractions(0)

        assertThat(interaction.command)
            .isEqualTo("ENTER_TEXT \"Hello World\" in Some Text EditText (id: my_edit_text)")
    }

    @Test
    fun testUpdateCurrentRoboScript_updatesScriptAndIndex() {
        assertThat(state.currentRoboScript).isNull()
        val roboScript = RoboScriptDetails.newBuilder().setActionIndex(5).build()
        state.updateCurrentRoboScript(roboScript)
        assertThat(state.currentRoboScript).isEqualTo(roboScript)
        assertThat(state.currentRoboScriptIndex).isEqualTo(5)
    }

    @Test
    fun testUpdateLastCompletedRoboScriptIndex_updatesIndex() {
        assertThat(state.lastCompletedRoboScriptIndex).isEqualTo(-1)
        state.updateLastCompletedRoboScriptIndex(3)
        assertThat(state.lastCompletedRoboScriptIndex).isEqualTo(3)
    }
}
