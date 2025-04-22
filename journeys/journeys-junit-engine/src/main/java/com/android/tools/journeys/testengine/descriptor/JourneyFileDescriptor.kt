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

import androidx.test.tools.crawler.output.Crawl
import com.android.tools.journeys.testengine.JourneysExecutionContext
import com.android.tools.journeys.testengine.JourneysTestEngineInput
import com.android.tools.journeys.testengine.output.CrawlProcessingState
import com.android.tools.journeys.testengine.output.ProgressReporter
import com.android.tools.journeys.testengine.robo.RoboConfigConstants
import com.android.tools.journeys.testengine.robo.RoboConverter
import com.google.cloud.test.appcrawler.proto.Artifact
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
        requireNotNull(context.targetDeviceId) { "Target Device ID should not be null." }
        val outputPath =
            Path(
                JourneysTestEngineInput.resultsDir.absolutePath,
                context.targetDeviceId, journeyFile.nameWithoutExtension
            )
        outputPath.toFile().mkdirs()
        val prompts = journeyFile.toPath().inputStream().use { RoboConverter.getPrompts(it) }
        val crawlProcessingState = CrawlProcessingState(prompts)
        val reportEntryPublisher = { key: String, value: String ->
            if (value.isNotBlank()) {
                context.executionListener.reportingEntryPublished(
                    this,
                    ReportEntry.from("Journeys.$key", value)
                )
            }
        }
        val reporter = ProgressReporter(crawlProcessingState, outputPath, reportEntryPublisher)
        val artifactProcessor = { artifact: Artifact ->
            val hostPath = outputPath.resolve(artifact.name)
            hostPath.outputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                .use(artifact.data::writeTo)
            if (artifact.name == RoboConfigConstants.ROBO_RESULTS_FILE_NAME) {
                reporter.onCrawlReceived(Crawl.parseFrom(artifact.data))
            }
        }
        prompts.forEachIndexed { index, prompt ->
            reportEntryPublisher(
                "PromptScheduled.prompt$index",
                prompt
            )
        }
        context.proxy.executeJourney(
            context.targetDeviceId,
            journeyFile.toPath(),
            artifactProcessor
        )
        reporter.reportSkippedPrompts()
        crawlProcessingState.getJourneyError()?.let {
            throw AssertionError("Journey failed: ${it.message}")
        }
        return context
    }
}
