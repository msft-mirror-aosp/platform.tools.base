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
package com.android.testutils

import java.awt.image.BufferedImage
import java.nio.file.Path
import org.junit.rules.ExternalResource

/**
 * This rule provides a wrapper around [ImageDiffUtil.assertImageSimilar] that allows for creation of multiple missing golden files in a
 * single test run.
 */
class GoldenImageRule(val goldenFileDir: String) : ExternalResource() {

  private val missingGoldenFiles = mutableListOf<Path>()

  override fun after() {
    if (missingGoldenFiles.isNotEmpty()) {
      val names = missingGoldenFiles.joinToString(", ") { it.fileName.toString() }
      val suffix = if (missingGoldenFiles.size > 1) "s" else ""
      throw AssertionError("Golden image$suffix $names didn't exist, created in ${missingGoldenFiles[0].parent} and in undeclared outputs")
    }
  }

  fun assertImageSimilar(
    goldenFileName: String,
    actual: BufferedImage,
    maxPercentDifferent: Double = 0.0,
    maxSizeDifference: Int = 0,
    ignoreMissingGoldenFile: Boolean = false,
  ) {
    try {
      val goldenFile = getGoldenFile(goldenFileName)
      ImageDiffUtil.assertImageSimilar(goldenFile, actual, maxPercentDifferent, maxSizeDifference, ignoreMissingGoldenFile)
    } catch (e: MissingGoldenFileException) {
      missingGoldenFiles.add(e.file)
    }
  }

  private fun getGoldenFile(name: String): Path = TestUtils.resolveWorkspacePathUnchecked("$goldenFileDir/$name.png")
}
