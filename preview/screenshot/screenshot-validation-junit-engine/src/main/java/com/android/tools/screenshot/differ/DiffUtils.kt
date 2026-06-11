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

import java.awt.image.BufferedImage

private const val TRANSPARENT = 0x00_FF_FF_FFu

/** Returns an image highlighting the pixels that differ between image a and b and the number of pixels that differed. */
fun generatePixelDiffImage(a: BufferedImage, b: BufferedImage): Pair<BufferedImage, Int> {
  check(a.width == b.width && a.height == b.height) { "Images are different sizes" }
  val width = a.width
  val height = b.height
  val highlights = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
  var count = 0

  for (x in 0 until width) {
    for (y in 0 until height) {
      val aPixel = a.getRGB(x, y)
      val bPixel = b.getRGB(x, y)

      // Compare full ARGB pixels, but allow other channels to differ if alpha is 0
      if (aPixel == bPixel || (aPixel ushr 24 == 0 && bPixel ushr 24 == 0)) {
        highlights.setRGB(x, y, TRANSPARENT.toInt())
      } else {
        count++
        val aA = (aPixel shr 24) and 0xFF
        val aR = (aPixel shr 16) and 0xFF
        val aG = (aPixel shr 8) and 0xFF
        val aB = aPixel and 0xFF

        val bA = (bPixel shr 24) and 0xFF
        val bR = (bPixel shr 16) and 0xFF
        val bG = (bPixel shr 8) and 0xFF
        val bB = bPixel and 0xFF

        val diffSum = Math.abs(aA - bA) + Math.abs(aR - bR) + Math.abs(aG - bG) + Math.abs(aB - bB)
        val diffRatio = diffSum.toDouble() / 1020.0
        val greenValue = (200 * (1.0 - diffRatio)).toInt().coerceIn(0, 200)
        val highlightColor = 0xFF_FF_00_FFu.toInt() or (greenValue shl 8)

        highlights.setRGB(x, y, highlightColor)
      }
    }
  }
  return Pair(highlights, count)
}
