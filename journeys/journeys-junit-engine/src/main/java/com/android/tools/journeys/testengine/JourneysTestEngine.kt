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

package com.android.tools.journeys.testengine

import com.android.tools.journeys.testengine.resolver.JourneysFileSelectorResolver
import androidx.test.tools.crawler.output.Crawl
import com.android.tools.journeys.testengine.descriptor.JourneyFileDescriptor
import org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolver
import com.android.tools.journeys.testengine.output.CrawlProcessingState
import com.android.tools.journeys.testengine.output.ProgressReporter
import com.android.tools.journeys.testengine.robo.Adb
import com.android.tools.journeys.testengine.robo.Proxy
import com.android.tools.journeys.testengine.robo.RoboConfigConstants
import com.google.cloud.test.appcrawler.proto.Artifact
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.outputStream

// TODO(saxenaankita): Update the engine to HierarchicalTestEngine, support multiple devices and improve error handling.
class JourneysTestEngine : TestEngine {

    private val proxy = Proxy(
        Adb(
            JourneysTestEngineInput.ProxyInput.adbPath.absolutePath,
            JourneysTestEngineInput.testDeviceId
        ),
        JourneysTestEngineInput.ProxyInput.crawlerApkPath.absolutePath,
        JourneysTestEngineInput.ProxyInput.applicationId,
        JourneysTestEngineInput.ProxyInput.appApkPath.absolutePath
    )

    override fun getId(): String = "journeys-test-engine"

    override fun discover(request: EngineDiscoveryRequest, id: UniqueId): TestDescriptor {
        val engineDescriptor = EngineDescriptor(id, "Journeys Test Engine")

        EngineDiscoveryRequestResolver.builder<EngineDescriptor>()
            .addSelectorResolver(JourneysFileSelectorResolver())
            .addTestDescriptorVisitor { _ ->
                TestDescriptor.Visitor { it.prune() }
            }
            .build()
            .resolve(request, engineDescriptor)

        return engineDescriptor
    }

    override fun execute(request: ExecutionRequest) {
        val listener = request.engineExecutionListener
        request.rootTestDescriptor.children.filterIsInstance<JourneyFileDescriptor>().forEach {
            try {
                val journeyFileName = it.getJourneyFileName()
                val journeyPath =
                    Path(
                        JourneysTestEngineInput.journeysInputDir.absolutePath,
                        journeyFileName
                    )
                val outputPath =
                    Path(
                        JourneysTestEngineInput.resultsDir.absolutePath,
                        JourneysTestEngineInput.testDeviceId.sanitizeForPath(), journeyFileName.sanitizeForPath()
                    )
                outputPath.toFile().mkdirs()

                val crawlProcessingState = CrawlProcessingState(it)
                val reporter = ProgressReporter(crawlProcessingState, listener, outputPath)
                val artifactProcessor = { artifact: Artifact ->
                    val hostPath = outputPath.resolve(artifact.name)
                    hostPath.outputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                        .use(artifact.data::writeTo)
                    if (artifact.name == RoboConfigConstants.ROBO_RESULTS_FILE_NAME) {
                        reporter.onCrawlReceived(Crawl.parseFrom(artifact.data))
                    }
                }
                listener.executionStarted(it)
                println("[additionalTestArtifacts]deviceId=${JourneysTestEngineInput.testDeviceId}")
                proxy.executeJourney(journeyPath, artifactProcessor)
                reporter.reportSkippedPrompts()
                listener.executionFinished(it, TestExecutionResult.successful())
            } catch (e: Exception) {
                listener.executionFinished(it, TestExecutionResult.failed(e))
            }
        }
    }

    private fun String.sanitizeForPath(): String {
        return this.replace(SANITIZE_SPECIAL_CHARS_REGEX, "_")
    }

    companion object {
        private val SANITIZE_SPECIAL_CHARS_REGEX = Regex("[^a-zA-Z0-9_]")
    }
}
