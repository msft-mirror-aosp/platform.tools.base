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

package com.android.tools.uicheck.snapshot

import com.android.tools.visuallint.VisualLintErrorType
import kotlin.math.max
import kotlin.math.min

/** Bounding rectangle in rendered screen pixels. */
data class VisualLintRectData(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val width: Int
    get() = max(0, right - left)

  val height: Int
    get() = max(0, bottom - top)

  val area: Int
    get() = width * height

  /** Returns true if this rectangle and [other] both have positive dimensions and intersect with non-zero overlapping area. */
  fun intersects(other: VisualLintRectData): Boolean = intersectionArea(other) > 0

  /** Returns the overlapping area in square pixels between this rectangle and [other], or 0 if they do not intersect. */
  fun intersectionArea(other: VisualLintRectData): Int {
    val overlapWidth = max(0, min(right, other.right) - max(left, other.left))
    val overlapHeight = max(0, min(bottom, other.bottom) - max(top, other.top))
    return overlapWidth * overlapHeight
  }
}

/**
 * Source file, line, and Composable name of the call that a finding points to.
 *
 * @property filePath Source file path or simple file name recorded in the component tree.
 * @property lineNumber 1-based line number in [filePath] (matching Compose source information), or `-1` when unknown.
 * @property functionName Simple name of the Composable or view at the call site, if known.
 */
data class VisualLintSourceLocationData(
  val filePath: String,
  val lineNumber: Int = -1,
  val functionName: String? = null,
)

/**
 * A finding from a Visual Lint or accessibility check.
 *
 * @property errorType The [VisualLintErrorType] category of the check that produced this finding.
 * @property message Short human-readable summary of the issue.
 * @property description Detailed explanation or recommendation for resolving the issue, which may contain HTML formatting, or `null` if
 *   omitted.
 * @property sourceLocation Call site location in user source code for the component that triggered the finding, or `null` if unknown.
 * @property viewBounds Bounding box of the offending component in rendered screen pixels, or `null` if not tied to a specific element.
 * @property viewClassName Simple class or Composable name of the UI component associated with this finding, or `null` if unknown.
 */
data class VisualLintAnalyzerIssueData(
  val errorType: VisualLintErrorType,
  val message: String,
  val description: String? = null,
  val sourceLocation: VisualLintSourceLocationData? = null,
  val viewBounds: VisualLintRectData? = null,
  val viewClassName: String? = null,
)

/**
 * The findings for one rendered configuration (for example Phone, Tablet or font scale 200%) of a Compose `@Preview` or an XML layout.
 *
 * Headless UI Check runners produce these in-memory without needing Android Lint's `lint-api` on their classpath.
 */
data class VisualLintSnapshotData(
  val name: String,
  val configurationDescription: String,
  val screenWidthPx: Int,
  val screenHeightPx: Int,
  val densityDpi: Int = DEFAULT_DENSITY_DPI,
  val fontScale: Float = 1.0f,
  val analyzerIssues: List<VisualLintAnalyzerIssueData> = emptyList(),
  val previewId: String? = null,
  val imagePath: String? = null,
) {
  companion object {
    /** Density of the default Phone reference device (Pixel, 420dpi), used when a snapshot does not specify one. */
    const val DEFAULT_DENSITY_DPI = 420
  }
}
