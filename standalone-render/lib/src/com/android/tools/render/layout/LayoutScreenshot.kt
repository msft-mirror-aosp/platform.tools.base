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
package com.android.tools.render.layout

import com.android.SdkConstants.ANDROID_NS_NAME_PREFIX
import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_BACKGROUND
import com.android.SdkConstants.ATTR_LAYOUT
import com.android.SdkConstants.ATTR_LAYOUT_HEIGHT
import com.android.SdkConstants.ATTR_LAYOUT_WIDTH
import com.android.SdkConstants.ATTR_THEME
import com.android.SdkConstants.FRAME_LAYOUT
import com.android.SdkConstants.VALUE_MATCH_PARENT
import com.android.SdkConstants.VIEW_INCLUDE
import com.android.SdkConstants.XMLNS_ANDROID
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.resources.ResourceType
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.UNDEFINED_API_LEVEL
import com.android.tools.preview.UNDEFINED_DIMENSION
import com.android.tools.preview.config.PARAMETER_API_LEVEL
import com.android.tools.preview.config.PARAMETER_BACKGROUND_COLOR
import com.android.tools.preview.config.PARAMETER_DEVICE
import com.android.tools.preview.config.PARAMETER_FONT_SCALE
import com.android.tools.preview.config.PARAMETER_HEIGHT_DP
import com.android.tools.preview.config.PARAMETER_LOCALE
import com.android.tools.preview.config.PARAMETER_SHOW_BACKGROUND
import com.android.tools.preview.config.PARAMETER_UI_MODE
import com.android.tools.preview.config.PARAMETER_WALLPAPER
import com.android.tools.preview.config.PARAMETER_WIDTH_DP
import com.android.tools.render.StandaloneRenderModelModule
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.ResourceTarget
import com.android.tools.render.common.ScreenshotPreviewElement
import com.android.tools.render.common.toThemeReference
import com.android.utils.XmlUtils.toXmlAttributeValue

/** Resource types that can be previewed as a layout. */
val LAYOUT_RESOURCE_TYPES = setOf(ResourceType.LAYOUT)

/** The background of the preview when `showBackground` is not `false` and no `backgroundColor` is set, as in a running activity. */
private const val WINDOW_BACKGROUND = "?android:attr/windowBackground"

/**
 * Information required to render a screenshot of a layout resource, for example `R.layout.activity_main`.
 *
 * The layout is included in a `FrameLayout` that fills the preview device, like the layout editor in Android Studio, so that `<merge>`
 * layouts also render. This `FrameLayout` is the root of the component tree. Supported [previewParams]:
 * - `device`, `locale`, `fontScale`, `uiMode`, `apiLevel` and `wallpaper`: the configuration to render with, as in Compose's `@Preview`.
 *   They select resource variants such as `layout-land` or `values-night`. `wallpaper` is -1 (none) or the index of a
 *   [com.android.tools.configurations.Wallpaper].
 * - `theme`: the theme to inflate the layout with, for example `@style/Theme.MyApp`. A plain name such as `Theme.Material` is the project's
 *   style of that name, or the framework style if the project doesn't have one.
 * - `widthDp` and `heightDp`: the size of the layout's parent, in dp, instead of the device screen. It can be larger than the screen. -1,
 *   like a missing value, fills the device screen.
 * - `showBackground` and `backgroundColor`: by default, the preview has the theme's window background. `showBackground=false` makes it
 *   transparent, and `backgroundColor` replaces it with a color: `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB`, a color resource such as
 *   `@color/name` or a theme attribute such as `?attr/name`. Unlike Compose's `@Preview`, `showBackground=true` changes nothing, as the
 *   background is shown by default, `backgroundColor` applies without it, and `showBackground=false` ignores `backgroundColor`.
 *
 * Invalid parameters fail the screenshot with an error that names the parameter.
 */
data class LayoutScreenshot(
  override val methodFQN: String,
  override val previewParams: Map<String, String> = emptyMap(),
  override val previewId: String,
  override val previewWrapperFqn: String? = null,
) : PreviewScreenshot {

  override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement {
    val target =
      (ResourceTarget.parse(methodFQN) as? ResourceTarget.Valid)?.takeIf { it.type in LAYOUT_RESOURCE_TYPES }
        ?: throw IllegalArgumentException("Invalid layout target: '$methodFQN'")
    val isFramework = target.packageName == ResourceNamespace.ANDROID.packageName
    val resourceRef = if (isFramework) "@android:layout/${target.name}" else "@layout/${target.name}"

    if (!isFramework) {
      val resourceItems = module.resourceRepositoryManager.appResources.getResources(ResourceNamespace.RES_AUTO, target.type, target.name)
      if (resourceItems.isEmpty()) {
        throw IllegalArgumentException("Layout resource '$resourceRef' not found")
      }
    }

    val widthDp = intParam(PARAMETER_WIDTH_DP)?.takeIf { it != UNDEFINED_DIMENSION }
    val heightDp = intParam(PARAMETER_HEIGHT_DP)?.takeIf { it != UNDEFINED_DIMENSION }
    val showBackground =
      param(PARAMETER_SHOW_BACKGROUND)?.let {
        when (it.lowercase()) {
          "true" -> true
          "false" -> false
          else -> throw IllegalArgumentException("Invalid $PARAMETER_SHOW_BACKGROUND '$it': expected true or false")
        }
      }
    val backgroundColor = param(PARAMETER_BACKGROUND_COLOR)

    val configuration =
      PreviewConfiguration.cleanAndGet(
        apiLevel = intParam(PARAMETER_API_LEVEL)?.takeIf { it != UNDEFINED_API_LEVEL },
        locale = param(PARAMETER_LOCALE),
        fontScale = floatParam(PARAMETER_FONT_SCALE),
        uiMode = intParam(PARAMETER_UI_MODE),
        device = param(PARAMETER_DEVICE),
        wallpaper = intParam(PARAMETER_WALLPAPER),
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

    val attributes = buildList {
      add(ATTR_LAYOUT_WIDTH to (widthDp?.let { "${it}dp" } ?: VALUE_MATCH_PARENT))
      add(ATTR_LAYOUT_HEIGHT to (heightDp?.let { "${it}dp" } ?: VALUE_MATCH_PARENT))
      param(ATTR_THEME)?.let {
        add(ATTR_THEME to toThemeReference(it, module.resourceRepositoryManager.appResources))
      }
      background(showBackground, backgroundColor)?.let { add(ATTR_BACKGROUND to it) }
    }
    return LayoutScreenshotPreviewElement(
      displaySettings = displaySettings,
      configuration = configuration,
      xmlLayout = buildLayoutXml(resourceRef, attributes),
      // Like for drawables, FULL_EXPAND lets widthDp and heightDp be larger than the device screen while keeping its qualifiers. The
      // renderer crops the image to the layout's parent.
      expandToContent = widthDp != null || heightDp != null,
    )
  }

  /** Returns a `FrameLayout` with the given `android:` [attributes] that includes the [resourceRef] layout. */
  private fun buildLayoutXml(resourceRef: String, attributes: List<Pair<String, String>>): String = buildString {
    // PreviewXmlBuilder can't add child elements.
    append("<$FRAME_LAYOUT $XMLNS_ANDROID=\"$ANDROID_URI\"")
    for ((name, value) in attributes) {
      append("\n    $ANDROID_NS_NAME_PREFIX$name=\"${toXmlAttributeValue(value)}\"")
    }
    append(">\n")
    append("  <$VIEW_INCLUDE $ATTR_LAYOUT=\"${toXmlAttributeValue(resourceRef)}\" />\n")
    append("</$FRAME_LAYOUT>\n")
  }

  /** Returns the background of the layout's parent, or `null` for a transparent background. */
  private fun background(showBackground: Boolean?, backgroundColor: String?): String? =
    if (showBackground == false) null else backgroundColor ?: WINDOW_BACKGROUND

  private fun param(name: String): String? = previewParams[name]?.trim()?.takeIf { it.isNotEmpty() }

  private fun intParam(name: String): Int? = param(name)?.toInt()

  private fun floatParam(name: String): Float? = param(name)?.toFloat()
}

private data class LayoutScreenshotPreviewElement(
  override val displaySettings: PreviewDisplaySettings,
  override val configuration: PreviewConfiguration,
  private val xmlLayout: String,
  override val expandToContent: Boolean = false,
  override val previewElementDefinition: Unit? = null,
  override val previewBody: Unit? = null,
  override val hasAnimations: Boolean = false,
) : ScreenshotPreviewElement {

  // The background is drawn by the layout's parent, so that it follows the theme and the showBackground and backgroundColor parameters.
  override val transparentBackground: Boolean = true

  override fun resolveXmlLayouts(): Sequence<String> = sequenceOf(xmlLayout)
}
