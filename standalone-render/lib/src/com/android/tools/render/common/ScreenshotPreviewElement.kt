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

package com.android.tools.render.common

import com.android.tools.preview.ConfigurablePreviewElement

/**
 * Represents a resolved preview layout along with an optional display name from a parameter provider (e.g.
 * [androidx.compose.ui.tooling.preview.PreviewParameterProvider.getDisplayName]).
 */
data class ResolvedScreenshotLayout(val xmlLayout: String, val displayName: String? = null)

/** Interface required to be implemented for a [PreviewScreenshot] to be rendered. */
interface ScreenshotPreviewElement : ConfigurablePreviewElement<Unit> {
  /**
   * This method returns a sequence of XML layouts for each preview.
   *
   * By default, extracts [ResolvedScreenshotLayout.xmlLayout] from [resolveLayouts].
   */
  fun resolveXmlLayouts(): Sequence<String> = resolveLayouts().map { it.xmlLayout }

  /**
   * This method returns a sequence of [ResolvedScreenshotLayout] for each preview, preserving any metadata such as custom parameter display
   * names.
   *
   * By default, wraps each XML layout from [resolveXmlLayouts] with a null display name.
   */
  fun resolveLayouts(): Sequence<ResolvedScreenshotLayout> = resolveXmlLayouts().map { ResolvedScreenshotLayout(it) }
}
