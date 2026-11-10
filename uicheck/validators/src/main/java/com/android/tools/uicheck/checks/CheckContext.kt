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

package com.android.tools.uicheck.checks

import com.android.tools.uicheck.ComponentTreeNode
import com.android.tools.uicheck.snapshot.UiCheckSourceLocation
import java.awt.image.BufferedImage

/** One rendered preview configuration, as seen by a [ComponentTreeCheck]. */
data class CheckContext(
  val roots: List<ComponentTreeNode>,
  val screenWidthPx: Int,
  val screenHeightPx: Int,
  val densityDpi: Int,
  val fontScale: Float = 1.0f,
  val isRoundDevice: Boolean = false,
  val showSystemUi: Boolean = false,
  /** The rendered PNG, for checks that look at pixels such as color contrast. */
  val renderedImage: BufferedImage? = null,
  private val sourceLocations: SourceLocations = SourceLocations.EMPTY,
) {
  /** Returns the user's source location that a finding on [node] should point to. */
  fun sourceLocationOf(node: ComponentTreeNode): UiCheckSourceLocation? = sourceLocations[node]
}
