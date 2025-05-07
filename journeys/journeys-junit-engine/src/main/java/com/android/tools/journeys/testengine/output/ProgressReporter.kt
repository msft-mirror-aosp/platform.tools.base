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

package com.android.tools.journeys.testengine.output

import androidx.test.tools.crawler.output.Action
import androidx.test.tools.crawler.output.ActionDetails
import androidx.test.tools.crawler.output.Crawl
import androidx.test.tools.crawler.output.RoboScriptDetails
import com.android.tools.journeys.testengine.descriptor.PromptDescriptor
import com.android.tools.journeys.testengine.robo.RoboConfigConstants
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.reporting.ReportEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Data class holding processed information about a single model-driven action for reporting.
 *
 * @param index The sequence index of the action.
 * @param description A human-readable description of the action.
 * @param durationInMillis The duration of the action in milliseconds.
 * @param result The execution result status name (e.g., "ACTION_SUCCESS").
 * @param reasoning Optional reasoning text provided by the model for the action.
 * @param screenshotPath Optional string path to the screenshot saved for this action's display state.
 */
data class ModelAction(
    val index: Int,
    val description: String,
    val durationInMillis: Long,
    val result: Action.ExecutionResult,
    val reasoning: String? = null,
    val screenshotPath: String? = null
)

/**
 * Processes [Crawl] results, interprets the sequence of RoboScripts and Actions,
 * updates the [CrawlProcessingState], saves relevant artifacts (screenshots),
 * and reports test execution progress to the JUnit [EngineExecutionListener].
 *
 * @param state The current [CrawlProcessingState] associated with the journey execution.
 * @param listener The JUnit [EngineExecutionListener] to report events to.
 * @param resultsDir The base directory where results (like screenshots) should be saved.
 */
class ProgressReporter(
    private val state: CrawlProcessingState,
    private val listener: EngineExecutionListener,
    private val resultsDir: Path
) {

    /**
     * Handles the start of a new RoboScript execution (corresponds to a Prompt).
     * Reports the start event to the listener.
     *
     * @param roboScript The details of the RoboScript that started.
     */
    private fun onRoboScriptStarted(roboScript: RoboScriptDetails) {
        val promptDescriptor = state.getPromptDescriptor(roboScript.actionIndex) ?: return
        listener.executionStarted(promptDescriptor)
    }

    /**
     * Handles the completion of a RoboScript execution.
     * Reports the final status (success/failure) of the corresponding prompt.
     *
     * @param roboScript The details of the RoboScript that finished.
     * @param actionSeq The index of the action at which the RoboScript was finished.
     */
    private fun onRoboScriptFinished(roboScript: RoboScriptDetails, actionSeq: Int) {
        val promptDescriptor = state.getPromptDescriptor(roboScript.actionIndex) ?: return
        val result = terminationCauseToResult(roboScript)
        val id = "PromptComplete.prompt${promptDescriptor.getPromptIndex()}"
        val modelDetailsReasoning = state.getModelReasoning(roboScript.modelDetails.id) ?: ""
        // This screenshot is already written as the action was performed for a previous prompt
        // and the display state remained same for the following prompt.
        val screenshotPath = resultsDir.resolve("action$actionSeq.png").toString()
        promptDescriptor.reportEntry("$id.modelReasoning", modelDetailsReasoning)
        promptDescriptor.reportEntry("$id.screenshotPath", screenshotPath)
        listener.executionFinished(promptDescriptor, result)
    }

    /**
     * Handles an action that occurred, while a RoboScript was ongoing.
     * Reports the action(s) (accumulated + current) dynamically under the currently running prompt.
     *
     * @param action The [Action] that was performed.
     * @param roboScript The details of the RoboScript corresponding to the action.
     */
    private fun onActionPerformed(action: Action, roboScript: RoboScriptDetails) {
        val promptIndex = roboScript.actionIndex
        val promptDescriptor = state.getPromptDescriptor(promptIndex) ?: return

        val actionsToReport = buildList {
            addAll(state.getAccumulatedActions().map { getModelAction(it) })
            add(getModelAction(action, roboScript))
        }
        handleAllActions(actionsToReport, promptDescriptor)
        state.clearAccumulatedActions()
    }

    /**
     * Processes a list of [ModelAction]s, dynamically reporting each as a test execution
     * event under the given [PromptDescriptor].
     *
     * @param actions The list of processed [ModelAction]s to report.
     * @param promptDescriptor The parent [PromptDescriptor] under which to report these actions.
     */
    private fun handleAllActions(actions: List<ModelAction>, promptDescriptor: PromptDescriptor) {
        actions.filter { action -> action.description != TERMINATE_ACTION_DESCRIPTION }
            .forEach { action ->
                val id = "ActionPerformed.action${action.index}"
                promptDescriptor.reportEntry("$id.description", action.description)
                promptDescriptor.reportEntry(
                    "$id.durationInMillis",
                    action.durationInMillis.toString()
                )
                promptDescriptor.reportEntry("$id.result", action.result.name)
                action.reasoning?.let { promptDescriptor.reportEntry("$id.modelReasoning", it) }
                action.screenshotPath?.let {
                    promptDescriptor.reportEntry(
                        "$id.screenshotPath",
                        it
                    )
                }
            }
    }

    /**
     * Main entry point to process a received [Crawl] result object.
     * Iterates through actions and associated RoboScript details, determines their status,
     * and triggers the appropriate event handlers.
     *
     * @param crawl The [Crawl] object containing actions and script details.
     */
    fun onCrawlReceived(crawl: Crawl) {
        // Pre-process all display states to store screenshots.
        for (displayState in crawl.displayStatesList) {
            state.addScreenshot(displayState)
        }
        for (action in crawl.actionsList) {
            // If an action has no RoboScriptDetails, accumulate it and report with the immediate
            // next prompt. This typically happens for setup actions like LAUNCH_ACTION before
            // the first prompt.
            if (action.roboScriptDetailsList.isEmpty()) {
                state.accumulateAction(action)
                continue
            }

            val lastInActionListRoboScriptIndex =
                action.roboScriptDetailsList.lastOrNull()?.actionIndex ?: -1

            action.roboScriptDetailsList.forEach { roboScript ->
                if (roboScript.actionIndex == -1) return@forEach

                state.addModelDetail(roboScript.modelDetails)
                val roboIndex = roboScript.actionIndex
                val currentRoboScriptIndex = state.getCurrentRoboScriptIndex()
                val lastCompletedIndex = state.getLastCompletedRoboScriptIndex()

                // If the currentRoboScript was a singleton in roboscript details, the finish would
                // not be triggered until next action is received. This check makes sure we invoke
                // finish for such cases.
                if (roboIndex == currentRoboScriptIndex + 1 && lastCompletedIndex == currentRoboScriptIndex - 1) {
                    val currentRoboScript = state.getCurrentRoboScript()
                    currentRoboScript?.let {
                        onRoboScriptFinished(it, action.actionSeq - 1)
                        state.updateLastCompletedRoboScriptIndex(currentRoboScriptIndex)
                    }
                }
                if (roboIndex == lastCompletedIndex + 1 && roboIndex != currentRoboScriptIndex) {
                    onRoboScriptStarted(roboScript)
                    state.updateCurrentRoboScript(roboScript)
                }
                if (roboIndex == lastInActionListRoboScriptIndex) {
                    onActionPerformed(action, roboScript)
                }

                val isComplete = roboIndex < lastInActionListRoboScriptIndex
                if (isComplete || isTerminateCrawlAction(action)) {
                    onRoboScriptFinished(roboScript, action.actionSeq)
                    state.updateLastCompletedRoboScriptIndex(roboIndex)
                }
            }
        }
    }

    /** Checks if an action is a TerminateCrawlAction. */
    private fun isTerminateCrawlAction(action: Action): Boolean {
        return action.details?.detailsCase == ActionDetails.DetailsCase.TERMINATE_CRAWL_ACTION
    }

    /** Converts a RoboScript termination cause to a JUnit [TestExecutionResult]. */
    private fun terminationCauseToResult(roboScript: RoboScriptDetails): TestExecutionResult {
        return when (roboScript.terminationCause) {
            RoboScriptDetails.TerminationCause.NOT_A_TERMINATION,
            RoboScriptDetails.TerminationCause.END_OF_SCRIPT -> TestExecutionResult.successful()

            else -> {
                val errorMessage = state.getModelReasoning(roboScript.modelDetails.id)?.let {
                    "RoboScript terminated unexpectedly with model response: $it"
                } ?: "Roboscript terminated with error: ${roboScript.terminationCause.name}"
                TestExecutionResult.failed(AssertionError(errorMessage))
            }
        }
    }

    /**
     * Creates a [ModelAction] data object from an [Action].
     * Extracts description, reasoning, duration, result, and saves the screenshot.
     *
     * @param action The [Action] object.
     * @param roboscript Optional [RoboScriptDetails] associated with this action (used for reasoning).
     * @return A populated [ModelAction].
     */
    private fun getModelAction(action: Action, roboscript: RoboScriptDetails? = null): ModelAction {
        val modelDetailsDescription =
            roboscript?.modelDetails?.let { state.getModelDescription(it.id) }
        val reasoning = roboscript?.modelDetails?.let { state.getModelReasoning(it.id) }

        val description = if (isTerminateCrawlAction(action)) {
            TERMINATE_ACTION_DESCRIPTION
        } else if (isValidModelDescription(modelDetailsDescription)) {
            modelDetailsDescription!!
        } else {
            getActionDescription(action)
        }

        val screenshotPath: String? = state.getScreenshot(action.displayStateId)?.let { bytes ->
            val targetFilePath = resultsDir.resolve("action${action.actionSeq}.png")
            try {
                Files.write(targetFilePath, bytes)
                targetFilePath.toString()
            } catch (e: IOException) {
                System.err.println("Failed to write screenshot for action ${action.actionSeq} to $targetFilePath: ${e.message}")
                null
            }
        }

        return ModelAction(
            index = action.actionSeq,
            description = description,
            durationInMillis = getActionDuration(action),
            result = action.executionResult,
            reasoning = reasoning,
            screenshotPath = screenshotPath
        )
    }

    /** Checks if a model description can be used as display name for reporting. */
    private fun isValidModelDescription(description: String?): Boolean {
        return !(description.isNullOrEmpty() || description in RoboConfigConstants.GOAL_STATUS_MODEL_DESCRIPTION)
    }

    /** Calculates the duration of an action in milliseconds from its start and end timestamps. */
    private fun getActionDuration(action: Action): Long {
        val startTime =
            Instant.ofEpochSecond(action.startTime.seconds, action.startTime.nanos.toLong())
        val endTime = Instant.ofEpochSecond(action.endTime.seconds, action.endTime.nanos.toLong())
        val duration = Duration.between(startTime, endTime)
        return if (duration.isNegative) 0 else duration.toMillis()
    }

    /**
     * Gets a description for actions which are not expected to have detailed model output.
     * Currently only handles LAUNCH_ACTION and WAIT_ACTION explicitly.
     *
     * @param action The [Action] to describe.
     * @return A simple description string.
     */
    private fun getActionDescription(action: Action): String {
        val details = action.details ?: return "Action with no details"
        return when (details.detailsCase) {
            ActionDetails.DetailsCase.LAUNCH_ACTION ->
                "Launched package ${details.launchAction.appPackageName}"

            ActionDetails.DetailsCase.WAIT_ACTION -> {
                val duration = details.waitAction.waitDuration.seconds
                "Waited for $duration second${if (duration > 1) "s" else ""}"
            }
            // Return a generic description for other action types handled via model description.
            else -> "Performed action: ${details.detailsCase ?: "Unknown"}"
        }
    }

    /**
     * Helper method to publish reporting entries to the listener and print the artifact line.
     *
     * @param key The key for the entry.
     * @param value The value for the entry
     */
    private fun PromptDescriptor.reportEntry(key: String, value: String) {
        println("[additionalTestArtifacts]Journeys.$key=$value")
        if (key.isNotBlank() && value.isNotBlank()) {
            listener.reportingEntryPublished(this, ReportEntry.from(key, value))
        }
    }

    /**
     * Reports all prompts following the currently finished one as skipped.
     * This is called when the crawl terminates early.
     */
    fun reportSkippedPrompts() {
        val lastCompletedIndex = state.getLastCompletedRoboScriptIndex()
        state.getJourneyFileDescriptor().children.filterIsInstance<PromptDescriptor>()
            .forEach { child ->
                if (child.getPromptIndex() > lastCompletedIndex) {
                    listener.executionSkipped(
                        child,
                        "Skipped due to failure at previous step (index $lastCompletedIndex)"
                    )
                }
            }
    }

    private companion object {

        const val TERMINATE_ACTION_DESCRIPTION = "crawlTerminated"
    }
}
