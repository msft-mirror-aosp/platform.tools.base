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
import androidx.test.tools.crawler.output.DisplayState
import androidx.test.tools.crawler.output.ModelDetails
import androidx.test.tools.crawler.output.RoboScriptDetails
import com.android.tools.journeys.testengine.descriptor.JourneyFileDescriptor
import com.android.tools.journeys.testengine.descriptor.PromptDescriptor

/**
 * Holds the mutable state associated with processing the crawl results for a single journey
 * file execution.
 *
 * @param journeyFileDescriptor The [JourneyFileDescriptor] representing the journey being executed.
 */
class CrawlProcessingState(private val journeyFileDescriptor: JourneyFileDescriptor) {

    // Stores model details received, keyed by model ID.
    private val modelDetails = mutableMapOf<Int, ModelDetails>()

    // Stores the screenshot corresponding to a display state ID. This is used for reporting
    // the screenshot for any action.
    private val displayStateToScreenshot = mutableMapOf<Int, ByteArray>()

    // Accumulates actions received without a corresponding roboscript detail, for example launch action.
    private val accumulatedActions = mutableListOf<Action>()

    // Tracks the index of the last completed RoboScript prompt. -1 indicates none finished.
    private var lastCompletedRoboScriptIndex = -1

    // Tracks the current roboscript.
    private var currentRoboScript: RoboScriptDetails? = null

    /**
     * Adds model details to the state, if not already present for the given ID.
     * @param modelDetail The [ModelDetails] to add.
     */
    fun addModelDetail(modelDetail: ModelDetails) {
        modelDetails.putIfAbsent(modelDetail.id, modelDetail)
    }

    /**
     * Retrieves the description text for a given model ID.
     * @param id The ID of the model.
     * @return The model's description, or null if not found.
     */
    fun getModelDescription(id: Int): String? {
        return modelDetails[id]?.description
    }

    /**
     * Retrieves the reasoning text from the first interaction of a given model ID.
     * Assumes the reasoning is stored in the 'response' field of the first interaction.
     * @param id The ID of the model.
     * @return The reasoning text, or null if not found or if interactions are missing/empty.
     */
    fun getModelReasoning(id: Int): String? {
        return modelDetails[id]?.interactionsList?.firstOrNull()?.response
    }

    /**
     * Stores the screenshot data associated with a display state ID, if not already present.
     *
     * @param displayState The [DisplayState] containing the ID and screenshot data.
     */
    fun addScreenshot(displayState: DisplayState) {
        // Convert ByteString to ByteArray (creates a copy) and store if absent
        displayStateToScreenshot.putIfAbsent(
            displayState.displayStateId,
            displayState.screenshot.toByteArray()
        )
    }

    /**
     * Retrieves the screenshot data associated with a display state ID.
     *
     * @param displayStateId The ID of the display state.
     * @return The screenshot data as a [ByteArray], or null if no screenshot is stored for the given ID.
     */
    fun getScreenshot(displayStateId: Int): ByteArray? {
        return displayStateToScreenshot[displayStateId]
    }

    /**
     * Updates the currently executing RoboScript.
     * @param roboScript The RoboScript that has just started.
     */
    fun updateCurrentRoboScript(roboScript: RoboScriptDetails) {
        currentRoboScript = roboScript
    }

    /**
     * Gets the currently executing RoboScript.
     * @return The roboScript, or null if no script has started yet.
     */
    fun getCurrentRoboScript(): RoboScriptDetails? {
        return currentRoboScript
    }
    /**
     * Gets the index of the currently executing RoboScript.
     * @return The index, or -1 if no script has started execution yet.
     */
    fun getCurrentRoboScriptIndex(): Int {
        return currentRoboScript?.actionIndex ?: -1
    }

    /**
     * Updates the index of the last completed RoboScript.
     * @param index The index of the RoboScript that has just finished.
     */
    fun updateLastCompletedRoboScriptIndex(index: Int) {
        lastCompletedRoboScriptIndex = index
    }

    /**
     * Gets the index of the last completed RoboScript.
     * @return The index, or -1 if no script has finished execution yet.
     */
    fun getLastCompletedRoboScriptIndex(): Int {
        return lastCompletedRoboScriptIndex
    }

    /**
     * Adds an [Action] to the list of actions accumulated since the last RoboScript finished.
     * @param action The [Action] to accumulate.
     */
    fun accumulateAction(action: Action) {
        accumulatedActions.add(action)
    }

    /**
     * Retrieves the accumulated actions.
     * @return A [List] of [Action]s.
     */
    fun getAccumulatedActions(): List<Action> {
        return accumulatedActions
    }

    /**
     * Clears the list of accumulated actions. Called after processing them.
     */
    fun clearAccumulatedActions() {
        accumulatedActions.clear()
    }

    /**
     * Finds the [PromptDescriptor] corresponding to a given prompt index within the journey.
     *
     * @param promptIndex The index of the prompt (corresponds to RoboScript actionIndex).
     * @return The found [PromptDescriptor], or null if no descriptor exists for that index.
     */
    fun getPromptDescriptor(promptIndex: Int): PromptDescriptor? {
        val targetUniqueId = journeyFileDescriptor.uniqueId.append(
            PromptDescriptor.SEGMENT_TYPE,
            "$promptIndex"
        )
        val descriptorOptional = journeyFileDescriptor.findByUniqueId(targetUniqueId)

        return descriptorOptional.orElse(null)?.let { descriptor ->
            if (descriptor is PromptDescriptor) {
                descriptor
            } else {
                System.err.println("Warning: Found descriptor for $targetUniqueId but it was not a PromptDescriptor.")
                null
            }
        }
    }

    /**
     * Gets the [JourneyFileDescriptor] associated with this state.
     * @return The [JourneyFileDescriptor].
     */
    fun getJourneyFileDescriptor(): JourneyFileDescriptor {
        return journeyFileDescriptor
    }
}
