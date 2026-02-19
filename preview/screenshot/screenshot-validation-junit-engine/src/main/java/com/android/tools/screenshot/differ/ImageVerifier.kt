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

  fun verify(newImagePath: String, referenceImagePath: String, diffImageOutputPath: String): VerificationResult {
    val diffFile = File(diffImageOutputPath)
    if (diffFile.exists()) {
      diffFile.delete()
    }
    diffFile.parentFile.mkdirs()

    val newImageFile = File(newImagePath)
    if (!newImageFile.exists()) {
      throw FileNotFoundException("Preview image file does not exist ($newImagePath).")
    }

    val refImageFile = File(referenceImagePath)
    if (!refImageFile.exists()) {
      throw FileNotFoundException("Reference image file does not exist ($referenceImagePath).")
    }

    val actual = ImageIO.read(newImageFile)
    val reference = ImageIO.read(refImageFile)

    if (actual.width != reference.width || actual.height != reference.height) {
      throw ImageComparisonAssertionError(
        referenceImagePath,
        newImagePath,
        message =
          "Size Mismatch. Reference image size: ${reference.width}x${reference.height}." +
            " Rendered image size: ${actual.width}x${actual.height}",
      )
    }

    val diff = imageDiffer.diff(actual, reference)
    if (diff.highlights != null) {
      ImageIO.write(diff.highlights, "png", diffFile)
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
