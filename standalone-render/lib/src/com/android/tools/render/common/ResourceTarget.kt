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
import com.android.resources.ResourceUrl

/**
 * A preview target written like a resource: either the fully qualified name of an R class field, `R.<type>.<name>` (optionally
 * package-qualified, for example `com.example.R.layout.activity_main`), or a resource reference, `@[<package>:]<type>/<name>` (optionally
 * with the file extension, for example `@layout/activity_main.xml`).
 *
 * It is [Valid] if it refers to a resource, for example a layout in `res/layout/`, or [Invalid] otherwise, for example `R.layot.main`.
 */
sealed interface ResourceTarget {
  /** A target that refers to the resource [name] of [type], in [packageName] if the target is package-qualified. */
  data class Valid(val type: ResourceType, val name: String, val packageName: String? = null) : ResourceTarget

  /** A target written like a resource that doesn't refer to one, with the [reason], for example `unknown resource type 'layot'`. */
  data class Invalid(val reason: String) : ResourceTarget

  companion object {
    /**
     * Returns the [ResourceTarget] that [target] is written as, or null if it isn't written like a resource (for example a Compose preview
     * FQN).
     */
    @JvmStatic
    fun parse(target: String): ResourceTarget? {
      val trimmed = target.trim()
      val typeName: String
      val type: ResourceType?
      if (trimmed.startsWith("@")) {
        val url = ResourceUrl.parse(trimmed)?.takeIf { it.urlType == ResourceUrl.UrlType.NORMAL && !it.isPrivateAccessOverride }
        // ResourceUrl keeps the rest of the reference as the name, including a file extension such as .xml.
        val name = url?.name?.substringBefore('.')
        if (url != null && !name.isNullOrEmpty()) return Valid(url.type, name, url.namespace)
        // @[+][*][<package>:]<type>/<name>
        typeName = trimmed.drop(1).trimStart('+', '*').substringBefore('/').substringAfter(':')
        type = ResourceType.fromXmlValue(typeName)
      } else {
        val segments = trimmed.split('.')
        if (segments.size < 3 || segments[segments.size - 3] != "R") return null
        typeName = segments[segments.size - 2]
        type = ResourceType.fromClassName(typeName)
        val name = segments.last()
        if (type != null && name.isNotEmpty()) return Valid(type, name, segments.dropLast(3).joinToString(".").ifEmpty { null })
      }
      return Invalid(if (type == null) "unknown resource type '$typeName'" else "not a resource reference that can be previewed")
    }
  }
}
