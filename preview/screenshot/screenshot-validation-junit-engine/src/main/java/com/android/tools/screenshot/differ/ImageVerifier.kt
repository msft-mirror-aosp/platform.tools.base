/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tools.screenshot.differ

import java.io.File
import java.io.FileNotFoundException
import javax.imageio.ImageIO

data class VerificationResult(val diffResult: ImageDiffer.DiffResult, val diffPercent: Double?)

class ImageVerifier(private val imageDiffer: ImageDiffer) {

  fun verify(newImageFile: File, referenceImageFile: File, diffOutputFile: File, projectRoot: File): VerificationResult {
    if (diffOutputFile.exists()) {
      diffOutputFile.delete()
    }
    diffOutputFile.parentFile.mkdirs()

    if (!newImageFile.exists()) {
      throw FileNotFoundException("Preview image file does not exist (${newImageFile.relativeTo(projectRoot).path}).")
    }

    if (!referenceImageFile.exists()) {
      throw FileNotFoundException("Reference image file does not exist (${referenceImageFile.relativeTo(projectRoot).path}).")
    }

    val actual = ImageIO.read(newImageFile)
    val reference = ImageIO.read(referenceImageFile)

    if (actual.width != reference.width || actual.height != reference.height) {
      throw ImageComparisonAssertionError(
        referenceImageFile.relativeTo(projectRoot).path,
        newImageFile.relativeTo(projectRoot).path,
        diffImagePath = diffOutputFile.relativeTo(projectRoot).path,
        message =
          "Size Mismatch. Reference image size: ${reference.width}x${reference.height}." +
            " Rendered image size: ${actual.width}x${actual.height}",
      )
    }

    val diff = imageDiffer.diff(actual, reference)
    if (diff.highlights != null) {
      ImageIO.write(diff.highlights, "png", diffOutputFile)
    }

    // Extract percentDiff from the diff result
    val diffPercentValue: Double? = diff.percentDiff
    return VerificationResult(diff, diffPercentValue)
  }

  class ImageComparisonAssertionError(
    val expectedImagePath: String,
    val actualImagePath: String,
    val diffPercentage: Double? = null,
    val diffImagePath: String? = null,
    message: String = "Image does not match.",
  ) : AssertionError(message) {
    override val message: String
      get() =
        super.message +
          "\n" +
          "Expected: $expectedImagePath\n" +
          "Actual: $actualImagePath\n" +
          (diffPercentage?.let { "Difference: ${"%.2f".format(it*100)}%\n" } ?: "") +
          (diffImagePath?.let { "Diff Image: $it\n" } ?: "")
  }
}
