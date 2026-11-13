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

import com.android.tools.uicheck.PreviewComponentTree
import com.android.tools.uicheck.snapshot.UiCheckSnapshot
import com.android.tools.uicheck.snapshot.UiCheckSnapshot.Companion.DEFAULT_DENSITY_DPI
import com.android.tools.uicheck.snapshot.UiCheckSnapshot.Companion.DEFAULT_FONT_SCALE
import java.awt.image.BufferedImage

/** Runs the component tree checks on one rendered preview configuration and collects their findings into a snapshot. */
object UiCheckAnalyzer {
  private val CHECKS: List<ComponentTreeCheck> =
    listOf(
      ButtonSizeCheck,
      TextFieldSizeCheck,
      BottomNavCheck,
      BottomAppBarCheck,
      LongTextCheck,
    )

  /**
   * Checks [componentTree], the rendered configuration [configDescription] of [previewName].
   *
   * @param renderedImage the rendered PNG. Its size is the full screen, including the status and navigation bars, which the component
   *   tree's own size leaves out; without it the component tree's size is used.
   * @param userCode decides which call sites are the user's, so findings point at the user's code rather than a library's.
   */
  fun analyze(
    previewName: String,
    configDescription: String,
    componentTree: PreviewComponentTree,
    renderedImage: BufferedImage?,
    userCode: UserCodeFilter,
  ): UiCheckSnapshot {
    val context =
      CheckContext(
        roots = componentTree.components,
        screenWidthPx = renderedImage?.width ?: componentTree.width,
        screenHeightPx = renderedImage?.height ?: componentTree.height,
        densityDpi = componentTree.densityDpi?.takeIf { it > 0 } ?: DEFAULT_DENSITY_DPI,
        fontScale = componentTree.fontScale?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_FONT_SCALE,
        renderedImage = renderedImage,
        sourceLocations = SourceLocations.resolve(componentTree.components, userCode),
      )

    return UiCheckSnapshot(
      name = previewName,
      configurationDescription = configDescription,
      screenWidthPx = context.screenWidthPx,
      screenHeightPx = context.screenHeightPx,
      densityDpi = context.densityDpi,
      fontScale = context.fontScale,
      issues = CHECKS.flatMap { it.check(context) },
      previewId = componentTree.previewId,
    )
  }
}
