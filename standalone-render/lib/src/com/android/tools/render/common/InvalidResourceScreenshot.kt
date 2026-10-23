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
package com.android.tools.render.common

import com.android.tools.render.StandaloneRenderModelModule

/**
 * A preview target that is written like a resource but is not a valid resource target, for example `R.layot.activity_main`, with the
 * [reason] from [ResourceTarget.Invalid].
 *
 * Rendering it fails with a [ScreenshotError] for this screenshot only, instead of looking for a Compose preview of that name.
 */
data class InvalidResourceScreenshot(
  override val methodFQN: String,
  override val previewId: String,
  val reason: String,
  override val previewParams: Map<String, String> = emptyMap(),
  override val previewWrapperFqn: String? = null,
) : PreviewScreenshot {

  override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement =
    throw IllegalArgumentException("Invalid resource preview target '$methodFQN': $reason.")
}
