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

package com.android.tools.screenshot.differ

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDifferTest {
  @Test
  fun mssimMatcherSimilar() {
    val result = MSSIMMatcher().diff(loadTestImage("circle"), loadTestImage("circle"))
    assertIs<ImageDiffer.DiffResult.Similar>(result)
    assertEquals("[MSSIM] Required SSIM: 1.000, Actual SSIM: 1.000", result.description)
    assertNull(result.highlights)
  }

  @Test
  fun mssimMatcherDifferentWithImageDifferenceThreshold() {
    val differ = MSSIMMatcher(0.9f)
    val a = loadTestImage("circle")
    val b = loadTestImage("star")

    val result = differ.diff(a, b)
    assertIs<ImageDiffer.DiffResult.Similar>(result)
    assertEquals("[MSSIM] Required SSIM: 0.100, Actual SSIM: 0.338", result.description)
    verifyHighlightsPattern(result.highlights!!, a, b)
  }

  @Test
  fun mssimMatcherDifferent() {
    val a = loadTestImage("circle")
    val b = loadTestImage("star")
    val result = MSSIMMatcher().diff(a, b)
    assertIs<ImageDiffer.DiffResult.Different>(result)
    assertEquals("[MSSIM] Required SSIM: 1.000, Actual SSIM: 0.338", result.description)
    verifyHighlightsPattern(result.highlights, a, b)
  }

  @Test
  fun mmsimName() {
    assertEquals("MSSIMMatcher", MSSIMMatcher().name)
  }

  @Test
  fun pixelPerfectSimilar() {
    val result = PixelPerfect().diff(loadTestImage("circle"), loadTestImage("circle"))
    assertIs<ImageDiffer.DiffResult.Similar>(result)
    assertEquals("Pixel percentage difference: 0.00%. 0 of 65536 pixels are different", result.description)
    assertNull(result.highlights)
    assertThat(result.percentDiff).isEqualTo(0.0)
  }

  @Test
  fun pixelPerfectMatcherDifferentWithImageDifferenceThreshold() {
    val differ = PixelPerfect(0.9f)
    val a = loadTestImage("circle")
    val b = loadTestImage("star")

    val result = differ.diff(a, b)
    assertIs<ImageDiffer.DiffResult.Similar>(result)
    assertEquals("Pixel percentage difference: 27.22%. 17837 of 65536 pixels are different", result.description)
    verifyHighlightsPattern(result.highlights!!, a, b)
    assertThat(result.percentDiff).isWithin(0.0001).of(0.2722) // Approximate double comparison
  }

  @Test
  fun pixelPerfectDifferent() {
    val a = loadTestImage("circle")
    val b = loadTestImage("star")
    val result = PixelPerfect().diff(a, b)

    assertIs<ImageDiffer.DiffResult.Different>(result)
    assertEquals("Pixel percentage difference: 27.22%. 17837 of 65536 pixels are different", result.description)
    verifyHighlightsPattern(result.highlights, a, b)
    assertThat(result.percentDiff).isWithin(0.0001).of(0.2722) // Approximate double comparison
  }

  @Test
  fun pixelPerfectName() {
    assertEquals("PixelPerfect", PixelPerfect().name)
  }

  private fun verifyHighlightsPattern(highlights: BufferedImage, a: BufferedImage, b: BufferedImage) {
    assertEquals(a.width, highlights.width)
    assertEquals(a.height, highlights.height)
    for (x in 0 until highlights.width) {
      for (y in 0 until highlights.height) {
        val aPixel = a.getRGB(x, y)
        val bPixel = b.getRGB(x, y)
        val diffPixel = highlights.getRGB(x, y)

        if (aPixel == bPixel || (aPixel ushr 24 == 0 && bPixel ushr 24 == 0)) {
          assertEquals("Pixel at ($x, $y) should be transparent", 0x00FFFFFF.toInt(), diffPixel)
        } else {
          val alpha = (diffPixel ushr 24) and 0xFF
          val red = (diffPixel ushr 16) and 0xFF
          val blue = diffPixel and 0xFF
          assertEquals("Pixel at ($x, $y) red channel should be 255", 255, red)
          assertEquals("Pixel at ($x, $y) blue channel should be 255", 255, blue)
          assertEquals("Pixel at ($x, $y) alpha channel should be 255", 255, alpha)

          val green = (diffPixel ushr 8) and 0xFF
          assertTrue("Pixel at ($x, $y) green channel $green is not in 0..200", green in 0..200)
        }
      }
    }
  }

  private fun loadTestImage(name: String) = ImageIO.read(javaClass.getResourceAsStream("$name.png")!!)
}
