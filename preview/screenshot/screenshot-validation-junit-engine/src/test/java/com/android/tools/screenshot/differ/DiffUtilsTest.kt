/*
 * Copyright (C) 2026 The Android Open Source Project
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
import org.junit.Assert.assertThrows
import org.junit.Test

class DiffUtilsTest {

  @Test
  fun generatePixelDiffImage_identicalImages() {
    val a = createImage(10, 10, 0xFF0000FF.toInt())
    val b = createImage(10, 10, 0xFF0000FF.toInt())

    val (highlights, count) = generatePixelDiffImage(a, b)

    assertThat(count).isEqualTo(0)
    for (x in 0 until highlights.width) {
      for (y in 0 until highlights.height) {
        assertThat(highlights.getRGB(x, y)).isEqualTo(0x00FFFFFF.toInt()) // Transparent
      }
    }
  }

  @Test
  fun generatePixelDiffImage_differentImages() {
    val a = createImage(10, 10, 0xFF0000FF.toInt())
    val b = createImage(10, 10, 0xFF0000FF.toInt())
    // Make one pixel different
    b.setRGB(5, 5, 0xFFFF0000.toInt()) // Red

    val (highlights, count) = generatePixelDiffImage(a, b)

    assertThat(count).isEqualTo(1)
    assertThat(highlights.getRGB(5, 5)).isEqualTo(0xFFFF00FF.toInt()) // Magenta
    // Check other pixels are transparent
    assertThat(highlights.getRGB(0, 0)).isEqualTo(0x00FFFFFF.toInt())
  }

  @Test
  fun generatePixelDiffImage_differentSizes_throws() {
    val a = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
    val b = BufferedImage(10, 11, BufferedImage.TYPE_INT_ARGB)

    assertThrows(IllegalStateException::class.java) { generatePixelDiffImage(a, b) }
  }

  @Test
  fun generatePixelDiffImage_bothAlphaZero_consideredEqual() {
    val a = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
    val b = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)

    a.setRGB(5, 5, 0x00FF0000.toInt()) // Transparent Red
    b.setRGB(5, 5, 0x0000FF00.toInt()) // Transparent Green

    val (highlights, count) = generatePixelDiffImage(a, b)

    assertThat(count).isEqualTo(0)
    assertThat(highlights.getRGB(5, 5)).isEqualTo(0x00FFFFFF.toInt()) // Transparent
  }

  private fun createImage(width: Int, height: Int, color: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (x in 0 until width) {
      for (y in 0 until height) {
        image.setRGB(x, y, color)
      }
    }
    return image
  }
}
