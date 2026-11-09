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
package com.android.tools.render.drawable

import com.android.SdkConstants.ATTR_ADJUST_VIEW_BOUNDS
import com.android.SdkConstants.ATTR_LAYOUT_HEIGHT
import com.android.SdkConstants.ATTR_LAYOUT_WIDTH
import com.android.SdkConstants.ATTR_SRC
import com.android.SdkConstants.IMAGE_VIEW
import com.android.SdkConstants.VALUE_TRUE
import com.android.SdkConstants.VALUE_WRAP_CONTENT
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.resources.ResourceType
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.PreviewXmlBuilder
import com.android.tools.render.StandaloneRenderModelModule
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.ResourceTarget
import com.android.tools.render.common.ScreenshotPreviewElement

/** Resource types that can be previewed as a drawable. */
val DRAWABLE_RESOURCE_TYPES = setOf(ResourceType.DRAWABLE, ResourceType.MIPMAP)

/**
 * Information required to render a screenshot of a drawable or mipmap resource, for example `R.drawable.ic_logo`.
 *
 * Like the drawable preview in Android Studio, the drawable is shown in an `ImageView` that wraps the drawable, so the image has the
 * drawable's intrinsic size on the preview device, and the background is transparent.
 */
data class DrawableScreenshot(
  override val methodFQN: String,
  override val previewParams: Map<String, String> = emptyMap(),
  override val previewId: String,
  override val previewWrapperFqn: String? = null,
) : PreviewScreenshot {

  override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement {
    val target =
      (ResourceTarget.parse(methodFQN) as? ResourceTarget.Valid)?.takeIf { it.type in DRAWABLE_RESOURCE_TYPES }
        ?: throw IllegalArgumentException("Invalid drawable target: '$methodFQN'")
    val isFramework = target.packageName == ResourceNamespace.ANDROID.packageName
    val resourceRef = if (isFramework) "@android:${target.type.getName()}/${target.name}" else "@${target.type.getName()}/${target.name}"

    if (!isFramework) {
      val resourceItems = module.resourceRepositoryManager.appResources.getResources(ResourceNamespace.RES_AUTO, target.type, target.name)
      if (resourceItems.isEmpty()) {
        throw IllegalArgumentException("Drawable resource '$resourceRef' not found")
      }
    }

    val displaySettings =
      PreviewDisplaySettings(
        name = target.name,
        baseName = target.name,
        parameterName = null,
        group = null,
        showDecoration = false,
        background = PreviewDisplaySettings.Background.Default,
        organizationGroup = resourceRef,
        organizationName = target.name,
      )

    val xmlLayout =
      PreviewXmlBuilder(IMAGE_VIEW)
        .androidAttribute(ATTR_LAYOUT_WIDTH, VALUE_WRAP_CONTENT)
        .androidAttribute(ATTR_LAYOUT_HEIGHT, VALUE_WRAP_CONTENT)
        .androidAttribute(ATTR_SRC, resourceRef)
        // Keep the drawable's aspect ratio when the ImageView is clamped to the device screen.
        .androidAttribute(ATTR_ADJUST_VIEW_BOUNDS, VALUE_TRUE)
        .buildString()

    return DrawableScreenshotPreviewElement(
      displaySettings = displaySettings,
      configuration = PreviewConfiguration.cleanAndGet(),
      xmlLayout = xmlLayout,
    )
  }
}

private data class DrawableScreenshotPreviewElement(
  override val displaySettings: PreviewDisplaySettings,
  override val configuration: PreviewConfiguration,
  private val xmlLayout: String,
  override val previewElementDefinition: Unit? = null,
  override val previewBody: Unit? = null,
  override val hasAnimations: Boolean = false,
) : ScreenshotPreviewElement {

  override val transparentBackground: Boolean = true

  override fun resolveXmlLayouts(): Sequence<String> = sequenceOf(xmlLayout)
}
