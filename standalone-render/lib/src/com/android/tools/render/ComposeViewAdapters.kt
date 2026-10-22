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

import com.android.ide.common.rendering.api.ViewInfo
import java.lang.reflect.Method
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("com.android.tools.render.ComposeViewAdapters")

internal const val CLASS_COMPOSE_VIEW_ADAPTER = "androidx.compose.ui.tooling.ComposeViewAdapter"

/** Recursively collects all `ComposeViewAdapter` instances from [views] and their children. */
internal fun findComposeViewAdapters(views: List<ViewInfo>): List<Any> = views.flatMap { view ->
  val self = view.viewObject?.takeIf { it.javaClass.name == CLASS_COMPOSE_VIEW_ADAPTER }
  listOfNotNull(self) + findComposeViewAdapters(view.children)
}

/**
 * Looks up the zero-arg `dispose` or `dispose$<module>` method on a `ComposeViewAdapter` class, caching the result per class and logging
 * once at `FINE` if no matching method exists.
 */
internal val disposeMethodByClass =
  object : ClassValue<Method?>() {
    override fun computeValue(adapterClass: Class<*>): Method? =
      adapterClass.methods
        .firstOrNull { (it.name == "dispose" || it.name.startsWith("dispose$")) && it.parameterCount == 0 }
        ?.apply { isAccessible = true }
        .also {
          if (it == null) {
            logger.log(Level.FINE, "No zero-arg dispose method found on ${adapterClass.name}")
          }
        }
  }
