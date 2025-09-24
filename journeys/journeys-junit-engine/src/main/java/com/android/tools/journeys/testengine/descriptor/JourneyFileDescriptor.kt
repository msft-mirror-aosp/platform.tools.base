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
package com.android.tools.journeys.testengine.descriptor

import com.android.tools.journeys.proto.JourneyRunEvent
import com.android.tools.journeys.proto.JourneyRunResult
import com.android.tools.journeys.proto.Result
import com.android.tools.journeys.proto.RunFinished
import com.android.tools.journeys.proto.RunStarted
import com.android.tools.journeys.proto.Status
import com.android.tools.journeys.testengine.JourneysExecutionContext
import com.android.tools.journeys.testengine.JourneysTestEngineInput
import com.android.tools.journeys.testengine.adapter.consumer.CompositeJourneyRunEventConsumer
import com.android.tools.journeys.testengine.adapter.consumer.JourneyRunAggregatorConsumer
import com.android.tools.journeys.testengine.adapter.consumer.StreamingEventConsumer
import com.android.tools.journeys.testengine.adapter.createAdapter
import com.android.tools.journeys.testengine.robo.adapter.CrawlProcessingState
import com.android.tools.journeys.testengine.robo.adapter.RoboResultAdapter.RoboResultAdapterConfig
import com.android.tools.journeys.testengine.robo.platform.RoboConfigConstants
import com.android.tools.journeys.testengine.robo.platform.RoboConverter
import com.google.cloud.test.appcrawler.proto.Artifact
import com.google.protobuf.util.Timestamps
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.hierarchical.Node
import java.io.File
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

class JourneyFileDescriptor(
    parentId: UniqueId,
    private val journeyFile: File
) : AbstractTestDescriptor(
    parentId.append(SEGMENT_TYPE, journeyFile.name),
    journeyFile.name
),
    Node<JourneysExecutionContext> {

    companion object {

        const val SEGMENT_TYPE: String = "journeyFile"
    }

    override fun getType() = TestDescriptor.Type.TEST

    // Force to run this mode in the same thread as its parent (=DeviceDescriptor)
    // so that two Journey file will not be executed on the same device in parallel.
    override fun getExecutionMode() = Node.ExecutionMode.SAME_THREAD

    override fun execute(
        context: JourneysExecutionContext,
        dynamicTestExecutor: Node.DynamicTestExecutor
    ): JourneysExecutionContext {
        when (context.backendId) {
            "ROBO" -> executeForRoboBackend(context)
            else -> throw IllegalArgumentException("Unknown backend: ${context.backendId}")
        }

        return context
    }

    private fun executeForRoboBackend(context: JourneysExecutionContext) {
        requireNotNull(context.targetDeviceId) { "Target Device ID should not be null." }

        val reportEntryPublisher = { key: String, value: String ->
            if (value.isNotBlank()) {
                context.executionListener.reportingEntryPublished(
                    this,
                    ReportEntry.from(key, value)
                )
            }
        }
        val streamingConsumer = StreamingEventConsumer(reportEntryPublisher)

        val outputPath = Path(
            JourneysTestEngineInput.resultsDir.absolutePath,
            context.targetDeviceId,
            journeyFile.nameWithoutExtension
        ).also { it.toFile().mkdirs() }
        val fileConsumer = JourneyRunAggregatorConsumer(outputPath.toFile())

        val compositeConsumer =
            CompositeJourneyRunEventConsumer(listOf(streamingConsumer, fileConsumer))
        val prompts = mutableListOf<String>()

        try {
            prompts.addAll(journeyFile.toPath().inputStream().use { RoboConverter.getPrompts(it) })
        } catch (e: Exception) {
            compositeConsumer.onEvent(createRunStartedEvent(context, prompts))
            val errorResult = Result.newBuilder()
                .setStatus(Status.ERROR)
                .setErrorMessage(e.message)
                .build()
            compositeConsumer.onEvent(createRunFinishedEvent(context, errorResult))
            throw e
        }

        compositeConsumer.onEvent(createRunStartedEvent(context, prompts))

        val crawlProcessingState = CrawlProcessingState(context.journeyRunId, prompts)
        try {
            val adapterConfig = RoboResultAdapterConfig(
                compositeConsumer,
                crawlProcessingState,
                outputPath
            )
            val resultAdapter = createAdapter(context.backendId, adapterConfig)

            val artifactProcessor = { artifact: Artifact ->
                val hostPath = outputPath.resolve(artifact.name)
                hostPath.outputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    .use(artifact.data::writeTo)
                if (artifact.name == RoboConfigConstants.ROBO_RESULTS_FILE_NAME) {
                    resultAdapter.process(artifact.data.toByteArray())
                }
            }

            context.proxy.executeJourney(
                context.journeyRunId,
                context.targetDeviceId,
                journeyFile.toPath(),
                artifactProcessor
            )
        } catch (e: Exception) {
            val errorResult = Result.newBuilder()
                .setStatus(Status.ERROR)
                .setErrorMessage(e.message)
                .build()
            compositeConsumer.onEvent(createRunFinishedEvent(context, errorResult))
            throw e
        }

        compositeConsumer.onEvent(
            createRunFinishedEvent(
                context,
                crawlProcessingState.journeyResult
            )
        )

        if (!crawlProcessingState.journeyResult.errorMessage.isNullOrBlank()) {
            throw AssertionError("Journey failed: ${crawlProcessingState.journeyResult.errorMessage}")
        }
    }

    private fun createRunStartedEvent(
        context: JourneysExecutionContext,
        prompts: List<String>
    ): JourneyRunEvent {
        val initialization =
            JourneyRunResult.Initialization.newBuilder()
                .setId(context.journeyRunId)
                .addAllPrompts(prompts)
                .setStartTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
                .putMetadata("deviceId", context.targetDeviceId)

        context.targetDeviceName?.let {
            initialization.putMetadata("deviceName", it)
        }

        return JourneyRunEvent.newBuilder()
            .setJourneyRunId(context.journeyRunId)
            .setRunStarted(
                RunStarted.newBuilder()
                    .setInitialization(initialization.build())
                    .build()
            )
            .build()
    }

    private fun createRunFinishedEvent(
        context: JourneysExecutionContext,
        result: Result
    ): JourneyRunEvent {
        return JourneyRunEvent.newBuilder()
            .setJourneyRunId(context.journeyRunId)
            .setRunFinished(
                RunFinished.newBuilder()
                    .setCompletion(
                        JourneyRunResult.Completion.newBuilder()
                            .setEndTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
                            .setResult(result)
                            .build()
                    ).build()
            ).build()
    }
}
