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

package com.android.tools.uicheck.config

import com.android.tools.render.common.PreviewScreenshot

/** A preview configuration to render and its [label] in reports. */
data class PlannedPreview(
  val screenshot: PreviewScreenshot,
  /** Name of the UI Check variation (e.g., `"Medium Tablet"`), or the `device` of a base preview (`"Base"` when it sets none). */
  val label: String,
) {

  /**
   * Describes one render of this preview in reports (e.g., `"Medium Tablet [param 0]"`), adding the `@PreviewParameter` [displayName] of
   * the rendered instance, if any.
   */
  fun describe(displayName: String?): String = if (displayName.isNullOrBlank()) label else "$label [$displayName]"
}
