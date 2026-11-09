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
import com.android.SdkConstants.ATTR_BACKGROUND
import com.android.SdkConstants.ATTR_LAYOUT_HEIGHT
import com.android.SdkConstants.ATTR_LAYOUT_WIDTH
import com.android.SdkConstants.ATTR_SCALE_TYPE
import com.android.SdkConstants.ATTR_SRC
import com.android.SdkConstants.ATTR_THEME
import com.android.SdkConstants.IMAGE_VIEW
import com.android.SdkConstants.VALUE_TRUE
import com.android.SdkConstants.VALUE_WRAP_CONTENT
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.resources.ResourceType
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.PreviewXmlBuilder
import com.android.tools.preview.UNDEFINED_API_LEVEL
import com.android.tools.preview.UNDEFINED_DIMENSION
import com.android.tools.preview.config.PARAMETER_API_LEVEL
import com.android.tools.preview.config.PARAMETER_BACKGROUND_COLOR
import com.android.tools.preview.config.PARAMETER_DEVICE
import com.android.tools.preview.config.PARAMETER_HEIGHT_DP
import com.android.tools.preview.config.PARAMETER_LOCALE
import com.android.tools.preview.config.PARAMETER_UI_MODE
import com.android.tools.preview.config.PARAMETER_WIDTH_DP
import com.android.tools.render.StandaloneRenderModelModule
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.ResourceTarget
import com.android.tools.render.common.ScreenshotPreviewElement
import com.android.tools.render.common.toThemeReference
import com.android.utils.XmlUtils.toXmlAttributeValue

/** Resource types that can be previewed as a drawable. */
val DRAWABLE_RESOURCE_TYPES = setOf(ResourceType.DRAWABLE, ResourceType.MIPMAP)

/** Values of `android:scaleType`, which `scaleType` takes. */
private val SCALE_TYPES = setOf("matrix", "fitXY", "fitStart", "fitCenter", "fitEnd", "center", "centerCrop", "centerInside")

/**
 * Information required to render a screenshot of a drawable or mipmap resource, for example `R.drawable.ic_logo`.
 *
 * Like the drawable preview in Android Studio, the drawable is shown in an `ImageView`. By default the view wraps the drawable, so the
 * image has the drawable's intrinsic size on the preview device, and the background is transparent. Supported [previewParams]:
 * - `device`, `locale`, `uiMode` and `apiLevel`: the configuration to render with, as in Compose's `@Preview`. They select resource
 *   variants such as `drawable-night` or `drawable-xxhdpi`.
 * - `theme`: the theme that resolves `?attr/` references in the drawable, for example `@style/Theme.MyApp`. A plain name such as
 *   `Theme.Material` is the project's style of that name, or the framework style if the project doesn't have one.
 * - `widthDp` and `heightDp`: the size of the view, in dp, for example for shapes and nine-patches.
 * - `backgroundColor` and `scaleType`: the view's background and scale type, like `tools:background` and `tools:scaleType` in drawable
 *   files.
 *
 * Invalid parameters fail the screenshot with an error that names the parameter.
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

    val widthDp = intParam(PARAMETER_WIDTH_DP)?.takeIf { it != UNDEFINED_DIMENSION }
    val heightDp = intParam(PARAMETER_HEIGHT_DP)?.takeIf { it != UNDEFINED_DIMENSION }
    val apiLevel = intParam(PARAMETER_API_LEVEL)?.takeIf { it != UNDEFINED_API_LEVEL }
    val scaleType = param(ATTR_SCALE_TYPE)
    require(scaleType == null || scaleType in SCALE_TYPES) {
      "Invalid $ATTR_SCALE_TYPE '$scaleType': expected one of ${SCALE_TYPES.joinToString()}"
    }

    val configuration =
      PreviewConfiguration.cleanAndGet(
        apiLevel = apiLevel,
        locale = param(PARAMETER_LOCALE),
        uiMode = intParam(PARAMETER_UI_MODE),
        device = param(PARAMETER_DEVICE),
      )

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

    val xmlBuilder =
      PreviewXmlBuilder(IMAGE_VIEW)
        .androidAttribute(ATTR_LAYOUT_WIDTH, widthDp?.let { "${it}dp" } ?: VALUE_WRAP_CONTENT)
        .androidAttribute(ATTR_LAYOUT_HEIGHT, heightDp?.let { "${it}dp" } ?: VALUE_WRAP_CONTENT)
        .androidAttribute(ATTR_SRC, resourceRef)
        // Keep the drawable's aspect ratio when one of widthDp/heightDp is wrap_content, or when neither is set and the ImageView is
        // clamped to the device screen.
        .androidAttribute(ATTR_ADJUST_VIEW_BOUNDS, VALUE_TRUE)
    // XmlBuilder doesn't escape attribute values.
    param(PARAMETER_BACKGROUND_COLOR)?.let { xmlBuilder.androidAttribute(ATTR_BACKGROUND, toXmlAttributeValue(it)) }
    scaleType?.let { xmlBuilder.androidAttribute(ATTR_SCALE_TYPE, it) }
    param(ATTR_THEME)?.let {
      xmlBuilder.androidAttribute(ATTR_THEME, toXmlAttributeValue(toThemeReference(it, module.resourceRepositoryManager.appResources)))
    }

    return DrawableScreenshotPreviewElement(
      displaySettings = displaySettings,
      configuration = configuration,
      xmlLayout = xmlBuilder.buildString(),
      expandToContent = widthDp != null || heightDp != null,
    )
  }

  private fun param(name: String): String? = previewParams[name]?.trim()?.takeIf { it.isNotEmpty() }

  private fun intParam(name: String): Int? = param(name)?.toInt()
}

private data class DrawableScreenshotPreviewElement(
  override val displaySettings: PreviewDisplaySettings,
  override val configuration: PreviewConfiguration,
  private val xmlLayout: String,
  override val expandToContent: Boolean = false,
  override val previewElementDefinition: Unit? = null,
  override val previewBody: Unit? = null,
  override val hasAnimations: Boolean = false,
) : ScreenshotPreviewElement {

  // Areas the drawable does not cover stay transparent, unless backgroundColor sets the view's background.
  override val transparentBackground: Boolean = true

  override fun resolveXmlLayouts(): Sequence<String> = sequenceOf(xmlLayout)
}
