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
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class ImageVerifierTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    val newDir: File by lazy { tempDir.newFolder("new") }
    val refDir: File by lazy { tempDir.newFolder("ref") }
    val diffDir: File by lazy { tempDir.newFolder("diff") }

    @Test
    fun assertMatchReference_missingReference() {
        val imageVerifier = ImageVerifier(MSSIMMatcher())
        val diffImage = File(diffDir, "diff.png")
        val error = assertThrows(FileNotFoundException::class.java) {
            imageVerifier.verify(
                "newImagePath",
                createImageFile("circle", refDir),
                diffImage.absolutePath)
        }
        assertThat(error).hasMessageThat().contains(
            "Preview image file does not exist (newImagePath)")
        assertThat(diffImage.exists()).isFalse()
    }

    @Test
    fun assertMatchReference_passed() {
        val imageVerifier = ImageVerifier(MSSIMMatcher())
        val diffImage = File(diffDir, "diff.png")
        imageVerifier.verify(
            createImageFile("circle", newDir),
            createImageFile("circle", refDir),
            diffImage.absolutePath)

        assertThat(diffImage.exists()).isFalse()
    }

    @Test
    fun assertMatchReferenceWithThreshold_passed() {
        val imageVerifier = ImageVerifier(MSSIMMatcher(imageDiffThreshold = 0.9f))
        val diffImage = File(diffDir, "diff.png")
        imageVerifier.verify(
            createImageFile("circle", newDir),
            createImageFile("star", refDir),
            diffImage.absolutePath)

        assertThat(diffImage.exists()).isTrue()
    }

    @Test
    fun assertMatchReference_failed() {
        val imageVerifier = ImageVerifier(MSSIMMatcher())
        val diffImage = File(diffDir, "diff.png")
        val error = assertThrows(ImageVerifier.ImageComparisonAssertionError::class.java) {
            imageVerifier.verify(
                createImageFile("star", newDir),
                createImageFile("circle", refDir),
                diffImage.absolutePath)
        }
        assertThat(error).hasMessageThat().contains("Image does not match")
        assertThat(diffImage.exists()).isTrue()

        imageVerifier.verify(
            diffImage.absolutePath,
            createImageFile("PixelPerfect_diff", refDir),
            File(diffDir, "diff2.png").absolutePath)
    }

    @Test
    fun assertMatchReference_sizeMismatch() {
        val imageVerifier = ImageVerifier(MSSIMMatcher())
        val diffImage = File(diffDir, "diff.png")
        val error = assertThrows(ImageVerifier.ImageComparisonAssertionError::class.java) {
            imageVerifier.verify(
                createImageFile("horizontal_rectangle", newDir),
                createImageFile("vertical_rectangle", refDir),
                diffImage.absolutePath)
        }
        assertThat(error).hasMessageThat().contains(
            "Size Mismatch. Reference image size: 72x128. Rendered image size: 128x72")
        assertThat(diffImage.exists()).isFalse()
    }

    /** Create a reference image for this test from the supplied test image [name]. */
    private fun createImageFile(name: String, dir: File): String {
        javaClass.getResourceAsStream("$name.png").use { from ->
            val outputFile = dir.resolve("$name.png").canonicalFile
            outputFile.outputStream().use { to ->
                from.copyTo(to)
            }
            return outputFile.absolutePath
        }
    }
}
