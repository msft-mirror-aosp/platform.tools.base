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

package com.android.tools.screenshot.differ

import com.android.tools.screenshot.differ.ImageDiffer.DiffResult
import com.android.tools.screenshot.differ.ImageDiffer.DiffResult.Different
import com.android.tools.screenshot.differ.ImageDiffer.DiffResult.Similar
import java.awt.image.BufferedImage
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pixel perfect image differ requiring images to be identical.
 *
 * The alpha channel is treated as pre-multiplied, meaning RGB channels may differ if the alpha channel is 0 (fully transparent).
 */
// TODO(b/244752233): Support wide gamut images.
class PixelPerfect(private var imageDiffThreshold: Float = 0f) : ImageDiffer {
  override fun diff(a: BufferedImage, b: BufferedImage): DiffResult {
    val pixelDiff = generatePixelDiffImage(a, b)
    val highlights = pixelDiff.first
    val numPixelsDifferent = pixelDiff.second

    val percentDiff: Double = numPixelsDifferent.toDouble() / (a.width * a.height)
    val percentDiffString = "${BigDecimal(percentDiff * 100).setScale(2, RoundingMode.HALF_EVEN)}%"
    val description = "Pixel percentage difference: $percentDiffString. $numPixelsDifferent of ${a.width * a.height} pixels are different"
    return if (numPixelsDifferent == 0) {
      Similar(description, null, percentDiff)
    } else if (percentDiff.compareTo(imageDiffThreshold) <= 0) {
      Similar(description, highlights, percentDiff)
    } else {
      Different(description, highlights, percentDiff)
    }
  }
}
