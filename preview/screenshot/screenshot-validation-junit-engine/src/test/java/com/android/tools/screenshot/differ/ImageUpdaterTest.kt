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

import com.android.tools.screenshot.ScreenshotImageInvalidException
import com.android.tools.screenshot.ScreenshotImageNotFoundException
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageUpdaterTest {

  @get:Rule val tempDir = TemporaryFolder()

  val newDir: File by lazy { tempDir.newFolder("new") }
  val refDir: File by lazy { tempDir.newFolder("ref") }

  @Test
  fun updateIfDifferent_missingNewImage_throws() {
    val imageUpdater = ImageUpdater(PixelPerfect())
    val refFile = File(createImageFile("circle", refDir))
    val missingNewFile = File(tempDir.root, "missingNewImage.png")

    val error =
      assertThrows(ScreenshotImageNotFoundException::class.java) {
        imageUpdater.updateIfDifferent(missingNewFile, refFile, tempDir.root)
      }
    assertThat(error).hasMessageThat().contains("Preview image file does not exist (missingNewImage.png)")
  }

  @Test
  fun updateIfDifferent_missingReferenceImage_copiesFile() {
    val imageUpdater = ImageUpdater(PixelPerfect())
    val newFile = File(createImageFile("circle", newDir))
    val refFile = File(refDir, "nonExistentReference.png")

    assertThat(refFile.exists()).isFalse()
    imageUpdater.updateIfDifferent(newFile, refFile, tempDir.root)
    assertThat(refFile.exists()).isTrue()
    assertThat(refFile.readBytes()).isEqualTo(newFile.readBytes())
  }

  @Test
  fun updateIfDifferent_invalidNewImage_throws() {
    val imageUpdater = ImageUpdater(PixelPerfect())
    val invalidNewFile = File(newDir, "invalid.png").apply { writeText("invalid image bytes") }
    val refFile = File(createImageFile("circle", refDir))

    val error =
      assertThrows(ScreenshotImageInvalidException::class.java) {
        imageUpdater.updateIfDifferent(invalidNewFile, refFile, tempDir.root)
      }
    assertThat(error).hasMessageThat().contains("Cannot read preview image file")
  }

  @Test
  fun updateIfDifferent_invalidReferenceImage_throws() {
    val imageUpdater = ImageUpdater(PixelPerfect())
    val newFile = File(createImageFile("circle", newDir))
    // Simulates an un-pulled Git LFS pointer text file
    val lfsPointerRefFile =
      File(refDir, "circle.png").apply {
        writeText(
          "version https://git-lfs.github.com/spec/v1\noid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1218128f550ef869f710734\nsize 4252\n"
        )
      }

    val error =
      assertThrows(ScreenshotImageInvalidException::class.java) {
        imageUpdater.updateIfDifferent(newFile, lfsPointerRefFile, tempDir.root)
      }
    assertThat(error).hasMessageThat().contains("Cannot read reference image file")
  }

  @Test
  fun updateIfDifferent_identicalImages_doesNotOverwrite() {
    val imageUpdater = ImageUpdater(PixelPerfect())
    val newFile = File(createImageFile("circle", newDir))
    val refFile = File(createImageFile("circle", refDir))
    val lastModified = 1000000L
    refFile.setLastModified(lastModified)

    imageUpdater.updateIfDifferent(newFile, refFile, tempDir.root)

    assertThat(refFile.lastModified()).isEqualTo(lastModified)
  }

  @Test
  fun updateIfDifferent_differentImages_overwritesReference() {
    val imageUpdater = ImageUpdater(PixelPerfect())
    val newFile = File(createImageFile("star", newDir))
    val refFile = File(createImageFile("circle", refDir))

    assertThat(refFile.readBytes()).isNotEqualTo(newFile.readBytes())
    imageUpdater.updateIfDifferent(newFile, refFile, tempDir.root)
    assertThat(refFile.readBytes()).isEqualTo(newFile.readBytes())
  }

  @Test
  fun updateIfDifferent_differentDimensions_overwritesReference() {
    val imageUpdater = ImageUpdater(PixelPerfect())
    val newFile = File(createImageFile("horizontal_rectangle", newDir))
    val refFile = File(createImageFile("vertical_rectangle", refDir))

    assertThat(refFile.readBytes()).isNotEqualTo(newFile.readBytes())
    imageUpdater.updateIfDifferent(newFile, refFile, tempDir.root)
    assertThat(refFile.readBytes()).isEqualTo(newFile.readBytes())
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
