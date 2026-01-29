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

import com.android.tools.journeys.testengine.resolver.DeviceSelectorResolver
import com.android.tools.journeys.testengine.resolver.JourneysFileSelectorResolver
import com.android.tools.journeys.testengine.robo.platform.Adb
import com.android.tools.journeys.testengine.robo.platform.ChannelProviderFactory
import com.android.tools.journeys.testengine.robo.platform.Proxy
import java.util.ServiceLoader
import java.util.UUID
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.config.PrefixedConfigurationParameters
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolver
import org.junit.platform.engine.support.hierarchical.ForkJoinPoolHierarchicalTestExecutorService
import org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine
import org.junit.platform.engine.support.hierarchical.HierarchicalTestExecutorService

/** Journeys Test Engine for JUnit Platform. */
class JourneysTestEngine : HierarchicalTestEngine<JourneysExecutionContext>() {

  private val proxy: Proxy by lazy {
    val factory =
      ServiceLoader.load(ChannelProviderFactory::class.java).firstOrNull()
        ?: error("No ChannelProviderFactory implementation found on the classpath.")
    Proxy(
      Adb(JourneysTestEngineInput.ProxyInput.adbPath.absolutePath),
      JourneysTestEngineInput.ProxyInput.applicationId,
      JourneysTestEngineInput.ProxyInput.appApkPath.absolutePath,
      JourneysTestEngineInput.ProxyInput.accessTokenPath,
      factory.createChannelProvider(),
    )
  }

  /** Returns the unique ID of this test engine. */
  override fun getId(): String = "journeys-test-engine"

  override fun discover(request: EngineDiscoveryRequest, id: UniqueId): TestDescriptor {
    val engineDescriptor = EngineDescriptor(id, "Journeys Test Engine")
    EngineDiscoveryRequestResolver.builder<EngineDescriptor>()
      .addSelectorResolver(DeviceSelectorResolver())
      .addSelectorResolver(JourneysFileSelectorResolver())
      .addTestDescriptorVisitor { _ -> TestDescriptor.Visitor { it.prune() } }
      .build()
      .resolve(request, engineDescriptor)
    return engineDescriptor
  }

  override fun createExecutionContext(executionRequest: ExecutionRequest): JourneysExecutionContext {
    val listener =
      object : EngineExecutionListener by executionRequest.engineExecutionListener {
        override fun reportingEntryPublished(testDescriptor: TestDescriptor, entry: ReportEntry) {
          entry.keyValuePairs.forEach { (key, value) ->
            executionRequest.engineExecutionListener.reportingEntryPublished(testDescriptor, entry)
            if (JourneysTestEngineInput.enableStdoutReport) {
              println(
                "<JOURNEYS_TEST_ARTIFACT><DESCRIPTION>$key</DESCRIPTION><ENCODED_PROTO>$value</ENCODED_PROTO></JOURNEYS_TEST_ARTIFACT>"
              )
            }
          }
        }
      }
    return JourneysExecutionContext(UUID.randomUUID().toString(), listener, proxy)
  }

  override fun createExecutorService(request: ExecutionRequest): HierarchicalTestExecutorService {
    return ForkJoinPoolHierarchicalTestExecutorService(
      PrefixedConfigurationParameters(request.configurationParameters, "journeys.execution.parallel.config.")
    )
  }
}
