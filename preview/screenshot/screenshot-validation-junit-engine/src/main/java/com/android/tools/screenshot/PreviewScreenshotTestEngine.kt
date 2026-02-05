/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.screenshot

import com.android.tools.preview.multipreview.PreviewMethodFinder
import com.android.tools.screenshot.descriptor.PreviewScreenshotTestEngineDescriptor
import com.android.tools.screenshot.resolver.ClassSelectorResolver
import com.android.tools.screenshot.resolver.MethodSelectorResolver
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolver
import org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine

class PreviewScreenshotTestEngine : HierarchicalTestEngine<PreviewScreenshotExecutionContext>() {

  override fun getId(): String {
    return "preview-screenshot-test-engine"
  }

  override fun discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
    val engineDescriptor = PreviewScreenshotTestEngineDescriptor(uniqueId, "Preview Screenshot Test Engine")

    EngineDiscoveryRequestResolver.builder<EngineDescriptor>()
      .addClassContainerSelectorResolver { true }
      .addSelectorResolver(ClassSelectorResolver())
      .addSelectorResolver(MethodSelectorResolver())
      .addTestDescriptorVisitor { _ -> TestDescriptor.Visitor { it.prune() } }
      .build()
      .resolve(discoveryRequest, engineDescriptor)

    return engineDescriptor
  }

  override fun createExecutionContext(executionRequest: ExecutionRequest): PreviewScreenshotExecutionContext {
    val listener =
      if (PreviewScreenshotTestEngineInput.ReportEntrySetting.redirectToStdout) {
        object : EngineExecutionListener by executionRequest.engineExecutionListener {
          override fun reportingEntryPublished(testDescriptor: TestDescriptor, entry: ReportEntry) {
            executionRequest.engineExecutionListener.reportingEntryPublished(testDescriptor, entry)
            entry.keyValuePairs.forEach { key, value -> println("[additionalTestArtifacts]$key=$value") }
          }
        }
      } else {
        executionRequest.engineExecutionListener
      }

    val methodNameToPreview =
      PreviewMethodFinder(
          PreviewScreenshotTestEngineInput.screenshotTestDirectory,
          PreviewScreenshotTestEngineInput.screenshotTestJars,
          PreviewScreenshotTestEngineInput.mainDirectory,
          PreviewScreenshotTestEngineInput.mainJars,
          PreviewScreenshotTestEngineInput.dependencyJars,
        )
        .findAllPreviewMethods()
        .asSequence()
        .associateBy { it.method.methodFqn }

    return PreviewScreenshotExecutionContext(
      listener,
      methodNameToPreview,
      PreviewScreenshotTestEngineInput.previewImageOutputDir,
      PreviewScreenshotTestEngineInput.previewDiffImageOutputDir,
      PreviewScreenshotTestEngineInput.referenceImageDir,
    )
  }
}
