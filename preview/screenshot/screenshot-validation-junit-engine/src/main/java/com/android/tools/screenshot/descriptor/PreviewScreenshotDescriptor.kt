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

package com.android.tools.screenshot.descriptor

import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.screenshot.PreviewScreenshotExecutionContext
import com.android.tools.screenshot.PreviewScreenshotTestEngineInput
import com.android.tools.screenshot.PreviewScreenshotTestEngineInput.ImageDifferInput
import com.android.tools.screenshot.differ.ImageDiffer
import com.android.tools.screenshot.differ.ImageUpdater
import com.android.tools.screenshot.differ.ImageVerifier
import com.android.tools.screenshot.differ.PixelPerfect
import java.io.File
import java.util.Optional
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.engine.support.hierarchical.Node

class PreviewScreenshotDescriptor(
  parentId: UniqueId,
  className: String,
  private val methodName: String,
  previewName: String,
  private val previewDisplayName: String,
  previewScreenshotResultIndex: Int,
  private val previewScreenshotResult: PreviewScreenshotResult,
) :
  AbstractTestDescriptor(
    parentId.append(SEGMENT_TYPE, previewScreenshotResult.previewId + "_${previewScreenshotResultIndex}"),
    methodName + previewName,
  ),
  Node<PreviewScreenshotExecutionContext> {
  companion object {
    const val SEGMENT_TYPE: String = "previewId"
  }

  private val source: MethodSource = MethodSource.from(className, methodName)

  override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST

  override fun getSource(): Optional<TestSource> {
    return Optional.of(source)
  }

  override fun execute(
    context: PreviewScreenshotExecutionContext,
    dynamicTestExecutor: Node.DynamicTestExecutor,
  ): PreviewScreenshotExecutionContext {
    val newImagePath = "${context.previewImageOutputDir.absolutePath}/${previewScreenshotResult.imagePath}"
    val refImagePath = "${context.referenceImageDir.absolutePath}/${previewScreenshotResult.imagePath}"
    val diffImagePath = "${context.previewDiffImageOutputDir.absolutePath}/${previewScreenshotResult.imagePath}"

    previewScreenshotResult.error?.let { System.err.println(it) }

    val imageVerifier = ImageVerifier(PixelPerfect(ImageDifferInput.threshold))
    var verificationResult: com.android.tools.screenshot.differ.VerificationResult? = null

    try {
      if (PreviewScreenshotTestEngineInput.TestOption.recordingModeEnabled) {
        ImageUpdater(PixelPerfect(ImageDifferInput.threshold)).updateIfDifferent(newImagePath, refImagePath)
      } else {
        verificationResult = imageVerifier.verify(newImagePath, refImagePath, diffImagePath)

        if (verificationResult.diffResult is ImageDiffer.DiffResult.Different) {
          throw ImageVerifier.ImageComparisonAssertionError(refImagePath, newImagePath, verificationResult.diffPercent, diffImagePath)
        }
      }
    } finally {
      // Always report diffPercentValue from the verification result
      verificationResult?.diffPercent?.let {
        context.executionListener.reportingEntryPublished(this, ReportEntry.from("PreviewScreenshot.diffPercent", it.toString()))
      }
      context.executionListener.reportingEntryPublished(this, ReportEntry.from("PreviewScreenshot.previewName", previewDisplayName))
      context.executionListener.reportingEntryPublished(this, ReportEntry.from("PreviewScreenshot.methodName", methodName))
      // Always publish refImagePath, this is required in IDE
      context.executionListener.reportingEntryPublished(this, ReportEntry.from("PreviewScreenshot.refImagePath", refImagePath))

      if (File(newImagePath).exists()) {
        context.executionListener.reportingEntryPublished(this, ReportEntry.from("PreviewScreenshot.newImagePath", newImagePath))
      }
      if (File(diffImagePath).exists()) {
        context.executionListener.reportingEntryPublished(this, ReportEntry.from("PreviewScreenshot.diffImagePath", diffImagePath))
      }
    }

    return context
  }
}
