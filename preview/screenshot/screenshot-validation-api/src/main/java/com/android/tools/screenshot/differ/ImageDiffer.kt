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

/**
 * Functional interface to compare two images and returns a [ImageDiffer.DiffResult] ADT containing comparison statistics and a difference
 * image, if applicable.
 */
fun interface ImageDiffer {
  /** Compare image [a] to image [b]. Implementations may assume [a] and [b] have the same dimensions. */
  fun diff(a: BufferedImage, b: BufferedImage): DiffResult

  /** A name to be used in logs for this differ, defaulting to the class's simple name. */
  val name
    get() =
      requireNotNull(this::class.simpleName) { "Could not determine ImageDiffer.name reflectively. Please override ImageDiffer.name." }

  /**
   * Result ADT returned from [diff].
   *
   * A differ may permit a small amount of difference, even for [Similar] results. Similar results must include a [description], even if
   * it's trivial, but may omit the [highlights] image if it would be fully transparent.
   *
   * @property description A human-readable description of how the images differed, such as the count of different pixels or percentage
   *   changed. Displayed in test failure messages and in CI.
   * @property highlights An image with a transparent background, highlighting where the compared images differ, typically in shades of
   *   magenta. Displayed in CI.
   * @property percentDiff The percentage of pixels that differed between the reference image and the screenshot image. This percentage is
   *   rounded to two decimal places.
   */
  sealed interface DiffResult {
    val description: String
    val highlights: BufferedImage?
    val percentDiff: Double?

    data class Similar(
      override val description: String,
      override val highlights: BufferedImage? = null,
      override val percentDiff: Double? = null,
    ) : DiffResult

    data class Different(
      override val description: String,
      override val highlights: BufferedImage,
      override val percentDiff: Double? = null,
    ) : DiffResult
  }
}
