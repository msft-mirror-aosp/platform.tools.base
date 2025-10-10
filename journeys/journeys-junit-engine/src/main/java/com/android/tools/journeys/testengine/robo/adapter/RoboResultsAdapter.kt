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
import androidx.test.tools.crawler.output.Crawl
import androidx.test.tools.crawler.output.ModelDetails
import androidx.test.tools.crawler.output.RoboScriptDetails
import com.android.tools.journeys.proto.JourneyRunEvent
import com.android.tools.journeys.proto.Result
import com.android.tools.journeys.proto.Status
import com.android.tools.journeys.proto.Step
import com.android.tools.journeys.proto.StepFinished
import com.android.tools.journeys.proto.StepStarted
import com.android.tools.journeys.proto.TurnAdded
import com.android.tools.journeys.testengine.adapter.JourneysResultAdapter
import com.android.tools.journeys.testengine.adapter.consumer.JourneyRunEventConsumer
import com.android.tools.journeys.testengine.robo.platform.RoboConfigConstants
import com.google.protobuf.Timestamp
import java.nio.file.Path

/**
 * An adapter that processes Robo crawl results and produces [JourneyRunEvent]s.
 *
 * @property config Configuration for the [RoboResultAdapter].
 */
class RoboResultAdapter(
    private val config: RoboResultAdapterConfig
) : JourneysResultAdapter {

    /**
     * Configuration for the [RoboResultAdapter].
     *
     * @param consumer The consumer to emit [JourneyRunEvent]s to.
     * @param crawlProcessingState The current [CrawlProcessingState] associated with the journey execution.
     * @param resultsDir The base directory where artifacts (like screenshots) should be saved.
     */
    data class RoboResultAdapterConfig(
        override val consumer: JourneyRunEventConsumer,
        val crawlProcessingState: CrawlProcessingState,
        val resultsDir: Path
    ) : JourneysResultAdapter.JourneysResultAdapterConfig

    private val journeyRunId = config.crawlProcessingState.getJourneyRunId()

    /**
     * Processes a [Crawl] object.
     * The raw artifact bytes are expected to be a serialized [Crawl] object.
     */
    override fun process(rawArtifactBytes: ByteArray) {
        try {
            val crawl = Crawl.parseFrom(rawArtifactBytes)
            onCrawlReceived(crawl)
        } catch (e: Exception) {
            println("Error while processing crawl bytes: ${e.message}")
        }
    }

    /**
     * Main entry point to process a received [Crawl] object.
     * Iterates through actions and associated RoboScript details, determines their status,
     * and triggers the appropriate event handlers.
     *
     * @param crawl The [Crawl] object containing actions and script details.
     */
    fun onCrawlReceived(crawl: Crawl) {
        preprocessCrawlResults(crawl)
        processActions(crawl)
    }

    /**
     * Pre-processes crawl results by storing screenshots and screen states.
     *
     * @param crawl The [Crawl] object containing display and screen states.
     */
    private fun preprocessCrawlResults(crawl: Crawl) {
        crawl.displayStatesList.forEach { displayState ->
            config.crawlProcessingState.addScreenshot(displayState, config.resultsDir)
        }
        crawl.screenStatesList.forEach { screenState ->
            config.crawlProcessingState.addScreenState(screenState)
        }
    }

    /**
     * Processes the actions within a crawl result, handling step starts, finishes, and turns.
     *
     * @param crawl The [Crawl] object containing actions and robo script details.
     */
    private fun processActions(crawl: Crawl) {
        for (action in crawl.actionsList) {
            // If an action has no RoboScriptDetails, we skip showing such actions.
            // This typically happens for setup actions like LAUNCH_ACTION before the first prompt.
            if (action.roboScriptDetailsList.isEmpty()) {
                if (action.actionSeq == 0 && !action.hasEndTime()) {
                    onStepStarted(0, action.startTime)
                }
                continue
            }

            val lastInActionListRoboScriptIndex = getLastRoboScriptIndexInAction(action)

            action.roboScriptDetailsList.forEach { roboScript ->
                if (roboScript.actionIndex == -1) return@forEach

                // TODO(b/416457494): Improve handling for built-in roboscripts.
                // For now, we just skip showing builtin roboscript actions.
                if (
                    RoboConfigConstants.ALLOWED_BUILTIN_ROBOSCRIPT_IDS.contains(
                        roboScript.activeRoboScriptId
                    )
                ) return@forEach

                handleRoboScriptEvents(
                    roboScript,
                    action,
                    lastInActionListRoboScriptIndex
                )
            }
        }
    }

    /**
     * Finds the index of the last non-builtin robo script within an action's robo script details list.
     *
     * @param action The [Action] to process.
     * @return The index of the last non-builtin robo script, or -1 if none is found.
     */
    private fun getLastRoboScriptIndexInAction(action: Action): Int {
        var lastElementIndex = action.roboScriptDetailsList.lastIndex
        var lastRoboScript = action.roboScriptDetailsList.getOrNull(lastElementIndex)
        while (lastElementIndex > 0 && RoboConfigConstants.ALLOWED_BUILTIN_ROBOSCRIPT_IDS.contains(
                lastRoboScript?.activeRoboScriptId ?: -1
            )
        ) {
            lastElementIndex--
            lastRoboScript = action.roboScriptDetailsList.getOrNull(lastElementIndex)
        }
        return lastRoboScript?.actionIndex ?: -1
    }

    /**
     * Handles the events related to a specific robo script within an action.
     *
     * @param roboScript The current [RoboScriptDetails] being processed.
     * @param action The [Action] containing the robo script.
     * @param lastInActionListRoboScriptIndex The index of the last non-builtin robo script in the action.
     */
    private fun handleRoboScriptEvents(
        roboScript: RoboScriptDetails,
        action: Action,
        lastInActionListRoboScriptIndex: Int
    ) {
        val roboIndex = roboScript.actionIndex
        val currentRoboScriptIndex = config.crawlProcessingState.currentRoboScriptIndex
        var lastCompletedIndex =
            config.crawlProcessingState.lastCompletedRoboScriptIndex
        // All events except addition of an interaction should be processed as soon as we receive
        // an action via pre action i.e. before it started. The pre action won't have the
        // end time and result. Addition of interaction will need the result of action which is
        // received only when the action completes and hence it is processed differently.
        // Further, terminate crawl action should always be used to process events as it is not
        // received via pre actions.
        val processEvent = !action.hasEndTime() || isTerminateCrawlAction(action)

        // If the currentRoboScript was a singleton in roboScript details, the finish would
        // not be triggered until next action is received. This check makes sure we invoke
        // finish for such cases.
        if (processEvent && roboIndex == currentRoboScriptIndex + 1 && lastCompletedIndex == currentRoboScriptIndex - 1) {
            val currentRoboScript = config.crawlProcessingState.currentRoboScript
            currentRoboScript?.let {
                onRoboScriptFinished(
                    action.startTime,
                    action.displayStateId,
                    it
                )
                config.crawlProcessingState.updateLastCompletedRoboScriptIndex(
                    currentRoboScriptIndex
                )
                lastCompletedIndex = currentRoboScriptIndex
            }
        }
        if (processEvent && roboIndex == lastCompletedIndex + 1 && roboIndex != currentRoboScriptIndex) {
            onRoboScriptStarted(
                action.startTime,
                action.displayStateId,
                roboScript
            )
            config.crawlProcessingState.updateCurrentRoboScript(roboScript)
        }
        if (roboIndex == lastInActionListRoboScriptIndex) {
            onActionPerformed(action, roboScript)
        }

        val isComplete = roboIndex < lastInActionListRoboScriptIndex
        if ((processEvent && isComplete) || isTerminateCrawlAction(action)) {
            onRoboScriptFinished(
                action.startTime,
                action.displayStateId,
                roboScript
            )
            config.crawlProcessingState.updateLastCompletedRoboScriptIndex(roboIndex)
        }
    }

    /**
     * Handles the start of a step.
     *
     * @param stepIndex The index of the step that started.
     * @param startTimestamp The timestamp when the step started.
     */
    private fun onStepStarted(stepIndex: Int, startTimestamp: Timestamp) {
        config.consumer.onEvent(
            JourneyRunEvent.newBuilder().setJourneyRunId(journeyRunId).setStepStarted(
                StepStarted.newBuilder()
                    .setInitialization(
                        Step.Initialization.newBuilder()
                            .setPromptText(config.crawlProcessingState.getPromptText(stepIndex))
                            .setStartTimestamp(startTimestamp)
                    )
            ).build()
        )
    }

    /**
     * Handles the completion of a step.
     *
     * @param result The result of the step.
     * @param endTimestamp The timestamp when the step finished.
     */
    private fun onStepFinished(result: Result, endTimestamp: Timestamp) {
        config.consumer.onEvent(
            JourneyRunEvent.newBuilder().setJourneyRunId(journeyRunId).setStepFinished(
                StepFinished.newBuilder()
                    .setCompletion(
                        Step.Completion.newBuilder()
                            .setResult(result)
                            .setEndTimestamp(endTimestamp)
                    )
            )
                .build()
        )
    }

    /**
     * Handles the addition (completion) of a turn. This function is
     * invoked whenever a new turn is received. Receiving a new turn
     * implies completion of the current one.
     */
    private fun onTurnAdded() {
        val currentTurn = config.crawlProcessingState.finalizeCurrentTurn()
        if (currentTurn.description.isBlank()) {
            return
        }
        config.consumer.onEvent(
            JourneyRunEvent.newBuilder()
                .setJourneyRunId(journeyRunId)
                .setTurnAdded(
                    TurnAdded.newBuilder()
                        .setTurn(currentTurn)
                )
                .build()
        )
    }

    /**
     * Handles the start of a new RoboScript execution (corresponds to a Prompt).
     * Reports the start event as a [StepStarted] proto.
     *
     * @param startTimestamp The timestamp when the prompt started.
     * @param displayStateId The ID of the display state just before the roboScript started.
     * @param roboScript The details for the prompt that started.
     */
    private fun onRoboScriptStarted(
        startTimestamp: Timestamp,
        displayStateId: Int,
        roboScript: RoboScriptDetails,
    ) {
        // Avoid sending step start callback for first prompt as that is invoked via the launch action.
        if (roboScript.actionIndex != 0) {
            onStepStarted(roboScript.actionIndex, startTimestamp)
        }
        handleModelDetails(roboScript.modelDetails, displayStateId)
    }

    /**
     * Handles the completion of a RoboScript execution (corresponds to a Prompt).
     * Reports the final status (success/failure) of the corresponding prompt as a
     * [StepFinished] proto.
     *
     * @param endTimestamp The timestamp when the prompt finished.
     * @param roboScript The details of the prompt that finished.
     */
    private fun onRoboScriptFinished(
        endTimestamp: Timestamp,
        displayStateId: Int,
        roboScript: RoboScriptDetails,
    ) {
        handleModelDetails(roboScript.modelDetails, displayStateId)
        val result = terminationCauseToResult(roboScript)
        if (result.status == Status.FAILED) {
            config.crawlProcessingState.setJourneyResult(result)
        }
        onTurnAdded()
        onStepFinished(result, endTimestamp)
    }

    /**
     * Handles an action that occurred while a RoboScript was ongoing.
     * If the action is associated with a new turn, the current turn is reported
     * and a new turn is created.
     * The action is then added as an Interaction to the current turn.
     *
     * @param action The [Action] that was performed.
     * @param roboScript The details of the prompt corresponding to the action.
     */
    private fun onActionPerformed(action: Action, roboScript: RoboScriptDetails) {
        val isPreAction = !action.hasEndTime()
        // Turn can be initialized with pre action as we do not need any result.
        // Always process terminate crawl action as it is not received in pre actions.
        if (isPreAction || isTerminateCrawlAction(action)) {
            handleModelDetails(roboScript.modelDetails, action.displayStateId)
        }
        if (isTerminateCrawlAction(action)) {
            return
        }
        // Interaction cannot be added with pre action as a result is required.
        if (!isPreAction) {
            config.crawlProcessingState.addInteractionToCurrentTurn(action)
        }
    }

    /**
     * Handles the model details associated with a RoboScript action, managing the lifecycle of a "turn".
     *
     * A "turn" represents a single cycle of the underlying model observing the screen,
     * reasoning about the next action, and executing it. The arrival of a new [ModelDetails]
     * object, identified by a unique ID, signals the start of a new turn.
     *
     * This function performs the following steps:
     * 1. Checks if the received `modelDetails` are new by comparing their ID. If not, no action is taken.
     * 2. If the `modelDetails` are new, it finalizes and reports the *previous* turn by calling [onTurnAdded].
     * 3. It initializes the new turn in the [CrawlProcessingState] with the new details.
     *
     * @param modelDetails The model details to handle.
     * @param displayStateId The ID of the display state associated with this turn's beginning.
     */
    private fun handleModelDetails(
        modelDetails: ModelDetails,
        displayStateId: Int
    ) {
        val currentModelDetailsId = config.crawlProcessingState.currentModelDetailsId
        if (modelDetails.id == currentModelDetailsId) {
            return
        }
        if (currentModelDetailsId != -1) {
            onTurnAdded()
        }
        config.crawlProcessingState.initNewTurn(
            displayStateId,
            modelDetails
        )
    }

    /** Checks if an action is a TerminateCrawlAction. */
    private fun isTerminateCrawlAction(action: Action): Boolean {
        return action.details?.detailsCase == ActionDetails.DetailsCase.TERMINATE_CRAWL_ACTION
    }

    /** Converts a RoboScript termination cause to a [Result] proto. */
    private fun terminationCauseToResult(roboScript: RoboScriptDetails): Result {
        return when (roboScript.terminationCause) {
            RoboScriptDetails.TerminationCause.NOT_A_TERMINATION,
            RoboScriptDetails.TerminationCause.END_OF_SCRIPT -> Result.newBuilder()
                .setStatus(Status.SUCCEEDED)
                .build()

            else -> {
                val modelReasoning = config.crawlProcessingState.currentModelReasoning
                val errorMessage =
                    if (!modelReasoning.isNullOrBlank() && modelReasoning != RoboConfigConstants.DEFAULT_MODEL_REASONING) {
                        "Prompt failed with model response: $modelReasoning"
                    } else {
                        getErrorMessageFromTerminationCause(roboScript.terminationCause)
                    }
                Result.newBuilder().setStatus(Status.FAILED).setErrorMessage(errorMessage).build()
            }
        }
    }

    /**
     * Provides an error message based on the termination cause.
     *
     * @param terminationCause The cause of termination.
     * @return The corresponding error message.
     */
    private fun getErrorMessageFromTerminationCause(
        terminationCause: RoboScriptDetails.TerminationCause
    ): String {
        return when (terminationCause) {
            RoboScriptDetails.TerminationCause.ACTION_FAILED,
            RoboScriptDetails.TerminationCause.ASSERTION_FAILED,
            RoboScriptDetails.TerminationCause.PROMPT_EVALUATION_FAILED -> "Encountered failure while executing the action"

            RoboScriptDetails.TerminationCause.ELEMENT_NOT_FOUND -> "Encountered missing element on screen while executing the action"
            RoboScriptDetails.TerminationCause.PROMPT_EVALUATION_LIMIT_REACHED -> "Could not successfully complete the action in max allowed attempts"
            RoboScriptDetails.TerminationCause.TIMED_OUT -> "Timed out while executing the action"
            RoboScriptDetails.TerminationCause.END_OF_SCRIPT,
            RoboScriptDetails.TerminationCause.NOT_A_TERMINATION -> "Not a failure"

            else -> "Journey terminated with unexpected error: ${terminationCause.name}"
        }
    }
}
