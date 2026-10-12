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

import com.android.tools.rendering.compose.ComposeViewInfo
import java.io.Serializable

/**
 * Result of a screenshot creation.
 *
 * [previewId] is a string designed to uniquely identify the preview (currently an image name without the suffix for method params).
 * [methodFQN] is the fully qualified name of the test method that this result is generated from. [imagePath] is the relative path of the
 * screenshot image in the output directory (using OS-dependent file separators), or an empty string (`""`) when image rendering is disabled
 * (`renderImage = false`). When image rendering is enabled and succeeds, the image is created in the output folder at this path; otherwise,
 * it identifies the screenshot that failed. [error] contains info about issues encountered during rendering. Both an image and an error may
 * be present if rendering succeeded with fidelity warnings or problems. [componentTreePath] is the relative path of the serialized
 * component tree JSON file in the output directory if requested and extracted.
 */
data class PreviewScreenshotResult(
  val previewId: String,
  val methodFQN: String,
  val imagePath: String,
  val error: ScreenshotError?,
  val displayName: String? = null,
  val viewInfos: List<ComposeViewInfo> = emptyList(),
  val componentTreePath: String? = null,
) : Serializable
