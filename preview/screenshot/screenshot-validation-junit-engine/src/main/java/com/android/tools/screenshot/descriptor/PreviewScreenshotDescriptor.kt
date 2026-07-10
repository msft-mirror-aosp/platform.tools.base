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
import com.android.tools.screenshot.ImageComparisonAssertionError
import com.android.tools.screenshot.PreviewScreenshotExecutionContext
import com.android.tools.screenshot.PreviewScreenshotTestEngineInput
import com.android.tools.screenshot.PreviewScreenshotTestEngineInput.ImageDifferInput
import com.android.tools.screenshot.ScreenshotRenderException
import com.android.tools.screenshot.differ.ImageDiffer
import com.android.tools.screenshot.differ.ImageUpdater
import com.android.tools.screenshot.differ.ImageVerifier
import com.android.tools.screenshot.differ.PixelPerfect
import com.android.tools.screenshot.differ.VerificationResult
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
    val newImageFile = File(context.previewImageOutputDir, previewScreenshotResult.imagePath)
    val refImageFile = File(context.referenceImageDir, previewScreenshotResult.imagePath)
    val diffImageFile = File(context.previewDiffImageOutputDir, previewScreenshotResult.imagePath)
    previewScreenshotResult.error?.let { error ->
      val detailedMessage = buildString {
        append("Screenshot rendering failed: ").append(error.message).append("\n")
        if (error.problems.isNotEmpty()) {
          append("Render Problems:\n")
          error.problems.forEach { problem ->
            append("- ").append(problem.html).append("\n")
            if (!problem.stackTrace.isNullOrBlank()) {
              append(problem.stackTrace).append("\n")
            }
          }
        }
        if (error.brokenClasses.isNotEmpty()) {
          append("Broken Classes:\n")
          error.brokenClasses.forEach { brokenClass ->
            append("- Class: ").append(brokenClass.className).append("\n")
            append(brokenClass.stackTrace).append("\n")
          }
        }
        if (error.missingClasses.isNotEmpty()) {
          append("Missing Classes: ").append(error.missingClasses.joinToString(", ")).append("\n")
        }
        if (error.stackTrace.isNotBlank()) {
          append("Stacktrace:\n").append(error.stackTrace).append("\n")
        }
      }
      throw ScreenshotRenderException(detailedMessage)
    }

    val absoluteProjectRoot = context.projectRoot.absoluteFile
    val relativeRefPath = refImageFile.absoluteFile.relativeTo(absoluteProjectRoot).invariantSeparatorsPath
    val relativeNewPath = newImageFile.absoluteFile.relativeTo(absoluteProjectRoot).invariantSeparatorsPath
    val relativeDiffPath = diffImageFile.absoluteFile.relativeTo(absoluteProjectRoot).invariantSeparatorsPath

    val imageVerifier = ImageVerifier(PixelPerfect(ImageDifferInput.threshold))
    var verificationResult: VerificationResult? = null

    try {
      if (PreviewScreenshotTestEngineInput.TestOption.recordingModeEnabled) {
        ImageUpdater(PixelPerfect(ImageDifferInput.threshold))
          .updateIfDifferent(newImageFile.absoluteFile, refImageFile.absoluteFile, absoluteProjectRoot)
      } else {
        verificationResult =
          imageVerifier.verify(newImageFile.absoluteFile, refImageFile.absoluteFile, diffImageFile.absoluteFile, absoluteProjectRoot)

        if (verificationResult.diffResult is ImageDiffer.DiffResult.Different) {
          throw ImageComparisonAssertionError(relativeRefPath, relativeNewPath, verificationResult.diffPercent, relativeDiffPath)
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
      context.executionListener.reportingEntryPublished(this, ReportEntry.from("PreviewScreenshot.refImagePath", relativeRefPath))

      if (newImageFile.exists()) {
        context.executionListener.reportingEntryPublished(this, ReportEntry.from("PreviewScreenshot.newImagePath", relativeNewPath))
      }
      if (diffImageFile.exists()) {
        context.executionListener.reportingEntryPublished(this, ReportEntry.from("PreviewScreenshot.diffImagePath", relativeDiffPath))
      }
    }

    return context
  }
}
