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
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageVerifierTest {

  @get:Rule val tempDir = TemporaryFolder()

  val newDir: File by lazy { tempDir.newFolder("new") }
  val refDir: File by lazy { tempDir.newFolder("ref") }
  val diffDir: File by lazy { tempDir.newFolder("diff") }

  @Test
  fun verify_missingNewImage_throws() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val error =
      assertThrows(FileNotFoundException::class.java) {
        imageVerifier.verify("newImagePath", createImageFile("circle", refDir), diffImage.absolutePath)
      }
    assertThat(error).hasMessageThat().contains("Preview image file does not exist (newImagePath)")
    assertThat(diffImage.exists()).isFalse()
  }

  @Test
  fun verify_missingReferenceImage_throws() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val newImagePath = createImageFile("circle", newDir)
    val error =
      assertThrows(FileNotFoundException::class.java) { imageVerifier.verify(newImagePath, "referenceImagePath", diffImage.absolutePath) }
    assertThat(error).hasMessageThat().contains("Reference image file does not exist (referenceImagePath)")
    assertThat(diffImage.exists()).isFalse()
  }

  @Test
  fun verify_identicalImages_returnsSimilar() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val result = imageVerifier.verify(createImageFile("circle", newDir), createImageFile("circle", refDir), diffImage.absolutePath)

    assertThat(result.diffResult).isInstanceOf(ImageDiffer.DiffResult.Similar::class.java)
    assertThat(result.diffPercent).isEqualTo(0.0)
    assertThat(diffImage.exists()).isFalse()
  }

  @Test
  fun verify_differentImagesBelowThreshold_returnsSimilar() {
    // The difference between circle.png and star.png is about 27.22%
    val imageVerifier = ImageVerifier(PixelPerfect(imageDiffThreshold = 0.28f))
    val diffImage = File(diffDir, "diff.png")
    val result = imageVerifier.verify(createImageFile("circle", newDir), createImageFile("star", refDir), diffImage.absolutePath)

    assertThat(result.diffResult).isInstanceOf(ImageDiffer.DiffResult.Similar::class.java)
    assertThat(result.diffPercent).isNotEqualTo(0.0)
    assertThat(result.diffPercent).isWithin(0.0001).of(0.2722)
    assertThat(diffImage.exists()).isTrue()
  }

  @Test
  fun verify_differentImages_returnsDifferent() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val result = imageVerifier.verify(createImageFile("star", newDir), createImageFile("circle", refDir), diffImage.absolutePath)

    assertThat(result.diffResult).isInstanceOf(ImageDiffer.DiffResult.Different::class.java)
    assertNotNull(result.diffPercent)
    assertThat(result.diffPercent).isNotNull()
    assertThat(result.diffPercent).isWithin(0.0001).of(0.2722)
    assertThat(diffImage.exists()).isTrue()

    // Verify that the generated diff image is what we expect.
    val diffCheckResult =
      ImageVerifier(PixelPerfect())
        .verify(diffImage.absolutePath, createImageFile("PixelPerfect_diff", refDir), File(diffDir, "diff2.png").absolutePath)

    assertThat(diffCheckResult.diffResult).isInstanceOf(ImageDiffer.DiffResult.Similar::class.java)
    assertThat(diffCheckResult.diffPercent).isEqualTo(0.0)
  }

  @Test
  fun verify_sizeMismatch_throws() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val error =
      assertThrows(ImageVerifier.ImageComparisonAssertionError::class.java) {
        imageVerifier.verify(
          createImageFile("horizontal_rectangle", newDir),
          createImageFile("vertical_rectangle", refDir),
          diffImage.absolutePath,
        )
      }
    assertThat(error).hasMessageThat().contains("Size Mismatch. Reference image size: 72x128. Rendered image size: 128x72")
    assertThat(diffImage.exists()).isFalse()
  }

  /** Create a reference image for this test from the supplied test image [name]. */
  private fun createImageFile(name: String, dir: File): String {
    val resourceStream = javaClass.getResourceAsStream("$name.png")
    requireNotNull(resourceStream) { "Test image '$name.png' not found." }
    resourceStream.use { from ->
      val outputFile = dir.resolve("$name.png").canonicalFile
      outputFile.outputStream().use { to -> from.copyTo(to) }
      return outputFile.absolutePath
    }
  }
}
