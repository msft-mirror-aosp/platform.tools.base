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

import com.android.SdkConstants.ANDROID_NS_NAME_PREFIX
import com.android.SdkConstants.ANDROID_STYLE_RESOURCE_PREFIX
import com.android.SdkConstants.NULL_RESOURCE
import com.android.SdkConstants.PREFIX_RESOURCE_REF
import com.android.SdkConstants.PREFIX_THEME_REF
import com.android.SdkConstants.REFERENCE_STYLE
import com.android.SdkConstants.STYLE_RESOURCE_PREFIX
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceRepository
import com.android.resources.ResourceType
import com.android.resources.ResourceUrl

/**
 * Returns the theme reference for the `theme` preview parameter:
 * - Style references such as `@style/Theme.MyApp` or `@android:style/Theme.Material`, `@null`, and theme attributes such as `?attr/myTheme`
 *   are kept.
 * - `android:Theme.Material` and `android:style/Theme.Material`, as in a style's `parent`, refer to the framework style
 *   `@android:style/Theme.Material`, and `style/Theme.MyApp` refers to `@style/Theme.MyApp`.
 * - A plain style name such as `Theme.MyApp` refers to the style of that name in [appResources], the project's resources and those of its
 *   libraries, or to the framework style if there is none. So a misspelled project style is reported as a missing framework style.
 *
 * @throws IllegalArgumentException if [theme] is blank or is a malformed or non-style reference.
 */
internal fun toThemeReference(theme: String, appResources: ResourceRepository): String {
  val name = theme.trim()
  require(name.isNotEmpty()) { invalidThemeMessage(name) }
  return when {
    name.startsWith(PREFIX_RESOURCE_REF) || name.startsWith(PREFIX_THEME_REF) -> {
      require(isValidThemeReference(name)) { invalidThemeMessage(name) }
      name
    }
    name.startsWith(ANDROID_NS_NAME_PREFIX) -> {
      val styleName = name.removePrefix(ANDROID_NS_NAME_PREFIX).removePrefix(REFERENCE_STYLE)
      require(isPlainName(styleName)) { invalidThemeMessage(name) }
      ANDROID_STYLE_RESOURCE_PREFIX + styleName
    }
    name.startsWith(REFERENCE_STYLE) -> {
      val styleName = name.removePrefix(REFERENCE_STYLE)
      require(isPlainName(styleName)) { invalidThemeMessage(name) }
      STYLE_RESOURCE_PREFIX + styleName
    }
    else -> {
      require(isPlainName(name)) { invalidThemeMessage(name) }
      if (appResources.hasResources(ResourceNamespace.RES_AUTO, ResourceType.STYLE, name)) {
        STYLE_RESOURCE_PREFIX + name
      } else {
        ANDROID_STYLE_RESOURCE_PREFIX + name
      }
    }
  }
}

private fun isValidThemeReference(reference: String): Boolean {
  if (reference == NULL_RESOURCE) return true
  val colon = reference.indexOf(':')
  val slash = reference.indexOf('/')
  if (colon >= 0 && slash in 0 until colon) return false
  val url = ResourceUrl.parse(reference) ?: return false
  if (url.isPrivateAccessOverride) return false
  if (url.namespace?.let(::isPlainName) == false) return false
  return when (url.urlType) {
    ResourceUrl.UrlType.NORMAL -> url.type == ResourceType.STYLE && isPlainName(url.name)
    ResourceUrl.UrlType.THEME -> url.type == ResourceType.ATTR && isPlainName(url.name)
    else -> false
  }
}

private fun isPlainName(name: String): Boolean = name.isNotEmpty() && '/' !in name && ':' !in name && name.none(Char::isWhitespace)

private fun invalidThemeMessage(value: String): String =
  "Invalid theme '$value': expected a style name, @[package:]style/<name>, ?[package:][attr/]<name>, or @null"
