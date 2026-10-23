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

import com.android.resources.ResourceType
import com.android.tools.render.StandaloneRenderModelModule

/**
 * A preview target that refers to a resource whose type cannot be previewed yet (for example `R.drawable.ic_logo`).
 *
 * Rendering it fails with a [ScreenshotError] for this screenshot only, so that the other screenshots of the same request still render.
 */
data class UnsupportedResourceScreenshot(
  override val methodFQN: String,
  override val previewId: String,
  val resourceType: ResourceType,
  override val previewParams: Map<String, String> = emptyMap(),
  override val previewWrapperFqn: String? = null,
) : PreviewScreenshot {

  override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement =
    throw IllegalArgumentException("Previews of ${resourceType.getName()} resources are not supported: '$methodFQN'.")
}
