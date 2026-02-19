/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.lint.checks.fx.utils

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import org.jetbrains.annotations.TestOnly

/**
 * A pool for re-using equal instances of objects. For two instances of [T] interned by the *same* pool, their logical equality coincides
 * with referential equality. The pool does not retain an instance if there is no other reference to it.
 */
class InterningPool<T : Any> {
  private val instances = WeakHashMap<T, WeakReference<T>>()

  @TestOnly fun size() = instances.size

  @Synchronized
  fun intern(instance: T): T =
    when (val priorInstance = instances[instance]?.get()) {
      null -> instance.also { instances[instance] = WeakReference(instance) }
      else -> priorInstance
    }

  companion object {
    /** Interns a [String] without retaining it if no live object does otherwise */
    val string: (String) -> String = InterningPool<String>()::intern
  }
}
