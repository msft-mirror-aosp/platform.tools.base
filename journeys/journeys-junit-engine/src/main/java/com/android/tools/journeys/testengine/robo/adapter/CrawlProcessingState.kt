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
import androidx.test.tools.crawler.output.DisplayState
import androidx.test.tools.crawler.output.ModelDetails
import androidx.test.tools.crawler.output.Point
import androidx.test.tools.crawler.output.RoboScriptDetails
import androidx.test.tools.crawler.output.ScreenElement
import androidx.test.tools.crawler.output.ScreenState
import androidx.test.tools.crawler.output.TargetAction
import androidx.test.tools.crawler.output.TargetAction.TargetActionType
import com.android.tools.journeys.proto.Artifact
import com.android.tools.journeys.proto.ArtifactType
import com.android.tools.journeys.proto.CommandType
import com.android.tools.journeys.proto.Interaction
import com.android.tools.journeys.proto.Result
import com.android.tools.journeys.proto.Status
import com.android.tools.journeys.proto.Turn
import com.android.tools.journeys.testengine.robo.platform.RoboConfigConstants
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds the mutable state associated with processing Robo crawl results for a single journey
 * file execution, to produce JourneyRunEvent.
 *
 * @param journeyRunId A unique identifier for the journey this state is associated with.
 * @param prompts The ordered list of prompts in the journey being executed.
 */
class CrawlProcessingState(private val journeyRunId: String, private val prompts: List<String>) {

    /** Stores the screenshot path corresponding to a display state ID. */
    private val displayStateToScreenshotPath = mutableMapOf<Int, String>()

    /** Stores a map of screen state ID to screen state. This is useful for obtaining
    a human-readable name for screen elements reported in an interaction. */
    private val screenStatesMap = mutableMapOf<Int, ScreenState>()

    /** Tracks the index of the last completed roboScript prompt. -1 indicates none finished. */
    var lastCompletedRoboScriptIndex = -1
        private set

    /** Tracks the current roboScript. */
    var currentRoboScript: RoboScriptDetails? = null
        private set

    /** Tracks the current turn. */
    private var turnBuilder: Turn.Builder = Turn.newBuilder()

    /** Tracks the model details ID of the last modelDetail added. */
    var currentModelDetailsId = -1
        private set

    /**
     * Tracks the final journey result. Set to success by default and updated by the adapter if
     * any prompt fails.
     */
    var journeyResult = Result.newBuilder().setStatus(Status.SUCCEEDED).build()
        private set

    fun getJourneyRunId(): String {
        return journeyRunId
    }

    /**
     * Obtains a prompt at a given index.
     *
     * @param index The index of the prompt to extract.
     * @return The extracted prompt.
     */
    fun getPromptText(index: Int): String {
        return prompts[index]
    }

    /**
     * Sets the journey result to the specified result.
     */
    fun setJourneyResult(result: Result) {
        journeyResult = result
    }

    /**
     * Stores the screenshot data associated with a display state ID and saves the path to
     * the local map.
     *
     * @param displayState The [DisplayState] containing the ID and screenshot data.
     * @param resultsDir The directory where the screenshot file should be saved.
     */
    fun addScreenshot(displayState: DisplayState, resultsDir: Path) {
        val id = displayState.displayStateId
        if (displayStateToScreenshotPath.containsKey(id)) {
            System.err.println("Received screenshot for display state $id more than once.")
        }
        val targetFilePath =
            resultsDir.resolve("displayState$id.png")
        try {
            Files.write(targetFilePath, displayState.screenshot.toByteArray())
        } catch (e: IOException) {
            System.err.println("Failed to write screenshot for display state $id to $targetFilePath: ${e.message}")
        }
        displayStateToScreenshotPath[id] = targetFilePath.toString()
    }

    /**
     * Adds a screen state to the map.
     *
     * @param screenState The [ScreenState] to add.
     */
    fun addScreenState(screenState: ScreenState) {
        screenStatesMap[screenState.screenStateId] = screenState
    }

    /**
     * Initializes a new turn in the processing state.
     *
     * @param displayStateId The ID of the display state at the beginning of the turn.
     * @param modelDetails Details about the model action corresponding with this turn.
     */
    fun initNewTurn(displayStateId: Int, modelDetails: ModelDetails) {
        turnBuilder = Turn.newBuilder().apply {
            addArtifactsBefore(
                Artifact.newBuilder()
                    .setType(ArtifactType.SCREENSHOT)
                    .setUri(displayStateToScreenshotPath[displayStateId])
            )
            description = modelDetails.description
            reasoning =
                modelDetails.interactionsList?.getOrNull(0)?.response?.takeIf { it.isNotBlank() }
                    ?: RoboConfigConstants.DEFAULT_MODEL_REASONING
        }
        currentModelDetailsId = modelDetails.id
    }

    /**
     * Finalizes and returns the current turn.
     */
    fun finalizeCurrentTurn(): Turn {
        val turn = turnBuilder.build()
        turnBuilder = Turn.newBuilder()
        return turn
    }

    /** Returns the reasoning of the current modelDetails. */
    val currentModelReasoning: String
        get() = turnBuilder.reasoning

    /**
     * Adds an interaction to the current turn.
     *
     * @param action The [Action] representing the interaction.
     */
    fun addInteractionToCurrentTurn(action: Action) {
        turnBuilder.addInteractions(
            Interaction.newBuilder().apply {
                type = toCommandType(action)
                command = makeActionString(action)
                addArtifactsBefore(
                    Artifact.newBuilder()
                        .setType(ArtifactType.SCREENSHOT)
                        .setUri(displayStateToScreenshotPath[action.displayStateId])
                )
                setResult(
                    when (action.executionResult) {
                        Action.ExecutionResult.ACTION_SUCCESS -> Result.newBuilder()
                            .setStatus(Status.SUCCEEDED)

                        else -> Result.newBuilder()
                            .setStatus(Status.FAILED)
                            .setErrorMessage("Action failed")
                    }
                )
            }.build()
        )
    }

    /**
     * Updates the currently executing RoboScript.
     * @param roboScript The RoboScript that has just started.
     */
    fun updateCurrentRoboScript(roboScript: RoboScriptDetails) {
        currentRoboScript = roboScript
    }

    /**
     * Gets the index of the currently executing RoboScript.
     * @return The index, or -1 if no script has started execution yet.
     */
    val currentRoboScriptIndex: Int
        get() = currentRoboScript?.actionIndex ?: -1

    /**
     * Updates the index of the last completed RoboScript.
     * @param index The index of the RoboScript that has just finished.
     */
    fun updateLastCompletedRoboScriptIndex(index: Int) {
        lastCompletedRoboScriptIndex = index
    }

    /** Converts an Action to its corresponding CommandType. */
    private fun toCommandType(action: Action): CommandType {
        val details = action.details
        return when (details.detailsCase) {
            ActionDetails.DetailsCase.LAUNCH_ACTION -> CommandType.LAUNCH

            ActionDetails.DetailsCase.TARGET_ACTION -> when (action.details.targetAction.actionType) {
                TargetActionType.CLICK -> CommandType.CLICK
                TargetActionType.LONG_PRESS -> CommandType.LONG_PRESS
                TargetActionType.TYPE_TEXT -> CommandType.TYPE_TEXT
                TargetActionType.ENTER_TEXT -> CommandType.ENTER_TEXT
                TargetActionType.PRESS_IME -> CommandType.PRESS_IME
                TargetActionType.SWIPE_NATURAL -> CommandType.SWIPE_NATURAL
                else -> CommandType.COMMAND_TYPE_UNSPECIFIED
            }

            ActionDetails.DetailsCase.WAIT_ACTION -> CommandType.WAIT

            ActionDetails.DetailsCase.BACK_ACTION -> CommandType.NAVIGATE_BACK
            ActionDetails.DetailsCase.HOME_ACTION -> CommandType.NAVIGATE_HOME
            ActionDetails.DetailsCase.POINT_TAP_ACTION -> CommandType.POINT_TAP

            ActionDetails.DetailsCase.ADB_COMMAND_ACTION -> CommandType.ADB_COMMAND
            ActionDetails.DetailsCase.TWO_POINT_GESTURE_ACTION -> CommandType.TWO_POINT_GESTURE
            else -> CommandType.COMMAND_TYPE_UNSPECIFIED
        }
    }

    /** Creates a human-readable string describing the details of an Action  */
    private fun makeActionString(action: Action): String {
        val details = action.details
        return when (details.detailsCase) {
            ActionDetails.DetailsCase.LAUNCH_ACTION -> "Launched package ${details.launchAction.appPackageName}"
            ActionDetails.DetailsCase.TARGET_ACTION -> toString(
                details.targetAction,
                action.screenStateId
            )

            ActionDetails.DetailsCase.WAIT_ACTION -> {
                val duration = details.waitAction.waitDuration.seconds
                "Waited for $duration second${if (duration > 1) "s" else ""}"
            }

            ActionDetails.DetailsCase.BACK_ACTION -> "Pressed back"
            ActionDetails.DetailsCase.HOME_ACTION -> "Pressed home"
            ActionDetails.DetailsCase.POINT_TAP_ACTION -> "Point tap at ${toString(details.pointTapAction.coordinate)}"
            ActionDetails.DetailsCase.ADB_COMMAND_ACTION -> "ADB command: ${details.adbCommandAction.command}"
            ActionDetails.DetailsCase.TWO_POINT_GESTURE_ACTION -> {
                val gesture = details.twoPointGestureAction
                "Two point gesture from (${toString(gesture.firstPointStart)}, ${toString(gesture.firstPointEnd)}) to (${
                    toString(
                        gesture.secondPointStart
                    )
                }, ${toString(gesture.secondPointEnd)})"
            }

            else -> action.toString()
        }
    }

    /** Converts a TargetAction to a human-readable string. */
    private fun toString(targetAction: TargetAction, screenStateId: Int): String {
        val screenState = screenStatesMap[screenStateId]
        if (screenState == null) {
            return "${targetAction.actionType.name} on element with ID $screenStateId"
        }
        val targetElement = elementForId(screenState, targetAction.screenElementId)
        return buildString {
            append(targetAction.actionType.name)
            if (targetAction.actionType == TargetActionType.ENTER_TEXT || targetAction.actionType == TargetActionType.TYPE_TEXT) {
                append(" \"${targetAction.enterTextString}\" in ")
            } else {
                append(" on ")
            }
            append(toString(targetElement))
        }
    }

    /** Converts a ScreenElement to a human-readable string. */
    private fun toString(element: ScreenElement): String = buildString {
        if (element.contentDescription.isNotEmpty()) {
            append(element.contentDescription).append(" ")
        } else if (element.textProperties.text.isNotEmpty()) {
            append(element.textProperties.text).append(" ")
        }
        append(packageToClass(element.androidClassName))
        append(" (id: ").append(packageToClass(element.resourceName)).append(")")
    }

    /** Converts a Point to a human-readable string. */
    private fun toString(p: Point): String = "(${p.xCoordinate}, ${p.yCoordinate})"

    /** Converts a package name string to just the class name. */
    private fun packageToClass(packageStr: String): String = packageStr.substringAfterLast('.')

    /**
     * Returns the `ScreenElement` corresponding to the given `elementId` in the screen
     * given by the `screenStateId`.
     */
    private fun elementForId(screenState: ScreenState, elementId: String): ScreenElement {
        var currentElement = screenState.rootElementsList.first {
            elementId.startsWith(it.screenElementId)
        }
        while (currentElement.screenElementId != elementId) {
            currentElement = currentElement.childElementsList
                .maxByOrNull { stringMatchLength(it.screenElementId, elementId) }
                ?: break
        }
        return currentElement
    }

    /** Returns the length of the longest common prefix of the given strings.  */
    private fun stringMatchLength(s1: String, s2: String): Int =
        s1.zip(s2).takeWhile { (c1, c2) -> c1 == c2 }.count()
}
