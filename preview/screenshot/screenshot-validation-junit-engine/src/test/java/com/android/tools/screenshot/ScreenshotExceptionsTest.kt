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

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class ScreenshotExceptionsTest {

  @Test
  fun imageComparisonAssertionError_reportsDiffPercentageOnceOnFirstLine() {
    val error =
      ImageComparisonAssertionError(
        expectedImagePath = "app/src/screenshotTestDebug/reference/pkg/name/ExampleTest/preview_0.png",
        actualImagePath = "app/build/outputs/screenshotTest-results/preview/debug/rendered/preview_0.png",
        diffPercentage = 0.0042,
        diffImagePath = "app/build/outputs/screenshotTest-results/preview/debug/diffs/preview_0.png",
      )

    assertThat(error.message)
      .isEqualTo(
        """
        Image does not match. (0.42% difference)
        Expected: app/src/screenshotTestDebug/reference/pkg/name/ExampleTest/preview_0.png
        Actual: app/build/outputs/screenshotTest-results/preview/debug/rendered/preview_0.png
        Diff Image: app/build/outputs/screenshotTest-results/preview/debug/diffs/preview_0.png

        """
          .trimIndent()
      )
    // The percentage must be reported exactly once: no separate "Difference:" line.
    assertThat(error.message).doesNotContain("Difference:")
  }

  @Test
  fun imageComparisonAssertionError_withoutDiffPercentageOrDiffImage() {
    val error = ImageComparisonAssertionError("reference/preview_0.png", "rendered/preview_0.png")

    assertThat(error.message)
      .isEqualTo(
        """
        Image does not match.
        Expected: reference/preview_0.png
        Actual: rendered/preview_0.png

        """
          .trimIndent()
      )
  }

  @Test
  fun imageComparisonAssertionError_keepsCustomMessageOnFirstLine() {
    val error =
      ImageComparisonAssertionError(
        "reference/preview_0.png",
        "rendered/preview_0.png",
        diffImagePath = "diffs/preview_0.png",
        message = "Size Mismatch. Reference image size: 72x128. Rendered image size: 128x72",
      )

    assertThat(error.message)
      .isEqualTo(
        """
        Size Mismatch. Reference image size: 72x128. Rendered image size: 128x72
        Expected: reference/preview_0.png
        Actual: rendered/preview_0.png
        Diff Image: diffs/preview_0.png

        """
          .trimIndent()
      )
  }

  @Test
  fun formatPercentage_roundsToTwoDecimals() {
    assertThat(ImageComparisonAssertionError.formatPercentage(0.0)).isEqualTo("0.00%")
    assertThat(ImageComparisonAssertionError.formatPercentage(0.0042)).isEqualTo("0.42%")
    assertThat(ImageComparisonAssertionError.formatPercentage(0.272215)).isEqualTo("27.22%")
    assertThat(ImageComparisonAssertionError.formatPercentage(1.0)).isEqualTo("100.00%")
  }

  @Test
  fun formatPercentage_neverReportsANonZeroDifferenceAsZero() {
    assertThat(ImageComparisonAssertionError.formatPercentage(0.00000001)).isEqualTo("<0.01%")
  }

  @Test
  fun formatPercentage_isLocaleIndependent() {
    val defaultLocale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.GERMANY)
      assertThat(ImageComparisonAssertionError.formatPercentage(0.0042)).isEqualTo("0.42%")
    } finally {
      Locale.setDefault(defaultLocale)
    }
  }
}
