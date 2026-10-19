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

package com.android.tools.screenshot

import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/** Thrown when Layoutlib rendering fails during preview execution. */
class ScreenshotRenderException(message: String) : RuntimeException(message) {
  override fun fillInStackTrace(): Throwable = this
}

/** Thrown when the expected or actual screenshot image file cannot be found. */
class ScreenshotImageNotFoundException(message: String) : FileNotFoundException(message) {
  override fun fillInStackTrace(): Throwable = this
}

/** Thrown when a screenshot image file is corrupted or invalid and cannot be read. */
class ScreenshotImageInvalidException(message: String) : IOException(message) {
  override fun fillInStackTrace(): Throwable = this
}

/** Thrown when the actual rendered image differs from the reference image. */
class ImageComparisonAssertionError(
  val expectedImagePath: String,
  val actualImagePath: String,
  val diffPercentage: Double? = null,
  val diffImagePath: String? = null,
  message: String = "Image does not match.",
) : AssertionError(message) {
  override fun fillInStackTrace(): Throwable = this

  /**
   * The failure message.
   *
   * The image difference percentage is appended to the first line and is deliberately not repeated anywhere else in the message: consumers
   * such as Gradle's console output and the IDE test view show only the summary line of a failure.
   */
  override val message: String
    get() =
      super.message +
        (diffPercentage?.let { " (${formatPercentage(it)} difference)" } ?: "") +
        "\n" +
        "Expected: $expectedImagePath\n" +
        "Actual: $actualImagePath\n" +
        (diffImagePath?.let { "Diff Image: $it\n" } ?: "")

  companion object {
    /**
     * Formats [fraction] (e.g. `0.0042`) as a percentage (e.g. `"0.42%"`).
     *
     * [Locale.US] is used so that the reported value does not depend on the machine's default locale. A non-zero difference that would be
     * rounded down to `0.00%` is reported as `<0.01%` so that the message never claims that a failing image is identical.
     */
    fun formatPercentage(fraction: Double): String {
      val percentage = fraction * 100
      return if (percentage > 0.0 && percentage < 0.01) "<0.01%" else String.format(Locale.US, "%.2f%%", percentage)
    }
  }
}
