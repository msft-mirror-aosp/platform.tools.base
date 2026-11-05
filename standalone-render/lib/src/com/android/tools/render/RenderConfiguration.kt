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

package com.android.tools.render

import java.io.Serializable

/**
 * Output and inspection options for rendering a [com.android.tools.render.common.PreviewScreenshot] with [Renderer].
 *
 * @property includeComponentTree Whether to extract and write the `ComposeViewInfo` component tree JSON alongside each rendered preview.
 * @property renderImage Whether to render and save the PNG bitmap image. When `true`, layoutlib callbacks are executed before rendering and
 *   any extracted component tree reflects the settled post-callback hierarchy. When `false` (component-tree-only mode), the layout is only
 *   inflated without executing layoutlib callbacks or rendering a bitmap, so any extracted component tree reflects the initial
 *   post-inflation hierarchy before `LaunchedEffect`s or animations run.
 * @property allowShowDecorations Whether to honor [com.android.tools.preview.PreviewDisplaySettings.showDecoration] (for example,
 *   `@Preview(showSystemUi = true)`) by rendering device system decorations (status/navigation bars, device frame). Defaults to `false` so
 *   screenshot tests render composable content only unless opted in.
 * @property detectAnimations Whether an extracted component tree also reports the Compose animations detected in the slot table. Walking
 *   the slot table for animation primitives adds overhead, so this defaults to `false`.
 */
data class RenderConfiguration
@JvmOverloads
constructor(
  val includeComponentTree: Boolean = false,
  val renderImage: Boolean = true,
  val allowShowDecorations: Boolean = false,
  val detectAnimations: Boolean = false,
) : Serializable
