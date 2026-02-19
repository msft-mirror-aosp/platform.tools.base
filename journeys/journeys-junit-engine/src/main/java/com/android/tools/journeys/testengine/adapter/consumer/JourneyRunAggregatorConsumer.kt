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

package com.android.tools.journeys.testengine.adapter.consumer

import com.android.tools.journeys.proto.JourneyRunEvent
import com.android.tools.journeys.proto.JourneyRunResult
import com.android.tools.journeys.proto.Step
import java.io.File
import java.io.FileOutputStream

/**
 * An implementation of JourneyRunEventConsumer that builds a complete [JourneyRun] proto in memory by processing events as they arrive. It
 * writes the complete proto after the RunFinished event is processed.
 */
class JourneyRunAggregatorConsumer(private val outputDir: File) : JourneyRunEventConsumer {

  private val journeyRunResultBuilder = JourneyRunResult.newBuilder()
  private var currentStepBuilder: Step.Builder? = null

  override fun onEvent(event: JourneyRunEvent) {
    when (event.eventPayloadCase) {
      JourneyRunEvent.EventPayloadCase.RUN_STARTED -> {
        journeyRunResultBuilder.initialization = event.runStarted.initialization
      }
      JourneyRunEvent.EventPayloadCase.STEP_STARTED -> {
        currentStepBuilder = Step.newBuilder()
        currentStepBuilder?.initialization = event.stepStarted.initialization
      }
      JourneyRunEvent.EventPayloadCase.TURN_ADDED -> {
        currentStepBuilder?.addTurns(event.turnAdded.turn)
      }
      JourneyRunEvent.EventPayloadCase.STEP_FINISHED -> {
        currentStepBuilder?.completion = event.stepFinished.completion
        currentStepBuilder?.let { journeyRunResultBuilder.addSteps(it) }
        currentStepBuilder = null
      }
      JourneyRunEvent.EventPayloadCase.RUN_FINISHED -> {
        journeyRunResultBuilder.completion = event.runFinished.completion
        writeToFile()
      }
      else -> {}
    }
  }

  private fun writeToFile() {
    val outputFile = File(outputDir, "journey_results.pb")
    FileOutputStream(outputFile).use { output -> journeyRunResultBuilder.build().writeTo(output) }
  }
}
