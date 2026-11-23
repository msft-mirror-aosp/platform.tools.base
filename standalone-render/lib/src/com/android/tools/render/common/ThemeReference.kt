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
internal fun toThemeReference(theme: String, appResources: ResourceRepository): String =
  when (val parsed = parseTheme(theme)) {
    is ParsedTheme.Resolved -> parsed.reference
    is ParsedTheme.Unresolved ->
      if (appResources.hasResources(ResourceNamespace.RES_AUTO, ResourceType.STYLE, parsed.styleName)) {
        STYLE_RESOURCE_PREFIX + parsed.styleName
      } else {
        ANDROID_STYLE_RESOURCE_PREFIX + parsed.styleName
      }
    null -> throw IllegalArgumentException(invalidThemeMessage(theme.trim()))
  }

/**
 * Returns whether [theme] has valid theme parameter syntax (without checking whether the referenced style exists in resources).
 *
 * @see toThemeReference
 */
internal fun isValidThemeSyntax(theme: String): Boolean = parseTheme(theme) != null

/** Result of syntactically parsing a `theme` preview parameter before any resource lookup. */
private sealed interface ParsedTheme {
  /** A theme already normalized to its final XML `android:theme` reference (e.g. `@null`, `@[package:]style/<name>`, `?attr/<name>`). */
  data class Resolved(val reference: String) : ParsedTheme

  /** A bare style name (e.g. `Theme.MyApp`) whose namespace (app vs framework) must still be resolved against `appResources`. */
  data class Unresolved(val styleName: String) : ParsedTheme
}

private fun parseTheme(theme: String): ParsedTheme? {
  val name = theme.trim()
  if (name.isEmpty()) return null
  if (name.startsWith(PREFIX_RESOURCE_REF) || name.startsWith(PREFIX_THEME_REF)) {
    return if (isValidThemeReference(name)) ParsedTheme.Resolved(name) else null
  }
  val (styleName, prefix) =
    when {
      name.startsWith(ANDROID_NS_NAME_PREFIX) ->
        name.removePrefix(ANDROID_NS_NAME_PREFIX).removePrefix(REFERENCE_STYLE) to ANDROID_STYLE_RESOURCE_PREFIX
      name.startsWith(REFERENCE_STYLE) -> name.removePrefix(REFERENCE_STYLE) to STYLE_RESOURCE_PREFIX
      else -> name to null
    }
  if (!ResourceUrl.isValidName(styleName, ResourceType.STYLE)) return null
  return if (prefix != null) ParsedTheme.Resolved(prefix + styleName) else ParsedTheme.Unresolved(styleName)
}

private fun isValidThemeReference(reference: String): Boolean =
  reference == NULL_RESOURCE || isValidResourceOrThemeReference(reference, ResourceType.STYLE)

internal fun isValidResourceOrThemeReference(reference: String, expectedType: ResourceType): Boolean {
  val colon = reference.indexOf(':')
  val slash = reference.indexOf('/')
  if (colon >= 0 && slash in 0 until colon) return false
  val typeStart = if (colon >= 0) colon + 1 else 1
  if (slash == typeStart) return false
  val url = ResourceUrl.parse(reference) ?: return false
  if (url.isPrivateAccessOverride || !url.hasValidName()) return false
  val namespace = url.namespace
  if (namespace != null && !isValidPackageName(namespace)) return false
  return when (url.urlType) {
    ResourceUrl.UrlType.NORMAL -> url.type == expectedType
    ResourceUrl.UrlType.THEME -> url.type == ResourceType.ATTR
    else -> false
  }
}

private fun isValidPackageName(value: String): Boolean =
  value.split('.').all { segment ->
    segment.isNotEmpty() &&
      Character.isJavaIdentifierStart(segment[0]) &&
      (1 until segment.length).all { Character.isJavaIdentifierPart(segment[it]) }
  }

internal fun invalidThemeMessage(value: String): String =
  "Invalid theme '$value': expected a style name, @[package:]style/<name>, ?[package:][attr/]<name>, or @null"
