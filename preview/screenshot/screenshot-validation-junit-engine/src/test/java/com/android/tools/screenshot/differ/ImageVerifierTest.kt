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
      assertThrows(ScreenshotImageNotFoundException::class.java) {
        imageVerifier.verify(File(tempDir.root, "newImagePath"), File(createImageFile("circle", refDir)), diffImage, tempDir.root)
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
      assertThrows(ScreenshotImageNotFoundException::class.java) {
        imageVerifier.verify(File(newImagePath), File(tempDir.root, "referenceImagePath"), diffImage, tempDir.root)
      }
    assertThat(error).hasMessageThat().contains("Reference image file does not exist (referenceImagePath)")
    assertThat(diffImage.exists()).isFalse()
  }

  @Test
  fun verify_identicalImages_returnsSimilar() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val result =
      imageVerifier.verify(File(createImageFile("circle", newDir)), File(createImageFile("circle", refDir)), diffImage, tempDir.root)

    assertThat(result.diffResult).isInstanceOf(ImageDiffer.DiffResult.Similar::class.java)
    assertThat(result.diffPercent).isEqualTo(0.0)
    assertThat(diffImage.exists()).isFalse()
  }

  @Test
  fun verify_differentImagesBelowThreshold_returnsSimilar() {
    // The difference between circle.png and star.png is about 27.22%
    val imageVerifier = ImageVerifier(PixelPerfect(imageDiffThreshold = 0.28f))
    val diffImage = File(diffDir, "diff.png")
    val result =
      imageVerifier.verify(File(createImageFile("circle", newDir)), File(createImageFile("star", refDir)), diffImage, tempDir.root)

    assertThat(result.diffResult).isInstanceOf(ImageDiffer.DiffResult.Similar::class.java)
    assertThat(result.diffPercent).isNotEqualTo(0.0)
    assertThat(result.diffPercent).isWithin(0.0001).of(0.2722)
    assertThat(diffImage.exists()).isTrue()
  }

  @Test
  fun verify_differentImages_returnsDifferent() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val result =
      imageVerifier.verify(File(createImageFile("star", newDir)), File(createImageFile("circle", refDir)), diffImage, tempDir.root)

    assertThat(result.diffResult).isInstanceOf(ImageDiffer.DiffResult.Different::class.java)
    assertNotNull(result.diffPercent)
    assertThat(result.diffPercent).isNotNull()
    assertThat(result.diffPercent).isWithin(0.0001).of(0.2722)
    assertThat(diffImage.exists()).isTrue()

    // Verify that the generated diff image is what we expect.
    val diffCheckResult =
      ImageVerifier(PixelPerfect())
        .verify(diffImage, File(createImageFile("PixelPerfect_diff", refDir)), File(diffDir, "diff2.png"), tempDir.root)

    assertThat(diffCheckResult.diffResult).isInstanceOf(ImageDiffer.DiffResult.Similar::class.java)
    assertThat(diffCheckResult.diffPercent).isEqualTo(0.0)
  }

  @Test
  fun verify_sizeMismatch_throws() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val error =
      assertThrows(ImageComparisonAssertionError::class.java) {
        imageVerifier.verify(
          File(createImageFile("horizontal_rectangle", newDir)),
          File(createImageFile("vertical_rectangle", refDir)),
          diffImage,
          tempDir.root,
        )
      }
    assertThat(error).hasMessageThat().contains("Size Mismatch. Reference image size: 72x128. Rendered image size: 128x72")
    assertThat(error).hasMessageThat().contains("Diff Image: ${File("diff", "diff.png").path}")
    assertThat(diffImage.exists()).isFalse()
  }

  @Test
  fun verify_emptyImageFile_throws() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val emptyFile = File(newDir, "empty.png").apply { createNewFile() }
    val refFile = File(createImageFile("circle", refDir))

    val error =
      assertThrows(ScreenshotImageInvalidException::class.java) { imageVerifier.verify(emptyFile, refFile, diffImage, tempDir.root) }
    assertThat(error).hasMessageThat().contains("Cannot read preview image file")
  }

  @Test
  fun verify_emptyReferenceImageFile_throws() {
    val imageVerifier = ImageVerifier(PixelPerfect())
    val diffImage = File(diffDir, "diff.png")
    val newFile = File(createImageFile("circle", newDir))
    val emptyFile = File(refDir, "empty.png").apply { createNewFile() }

    val error =
      assertThrows(ScreenshotImageInvalidException::class.java) { imageVerifier.verify(newFile, emptyFile, diffImage, tempDir.root) }
    assertThat(error).hasMessageThat().contains("Cannot read reference image file")
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
