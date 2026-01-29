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
package com.android.tools.lint.checks.fx.result

import org.jetbrains.uast.UElement

/**
 * An effect annotation is either explicit (with a source location for error reporting), or implicit (with zero or more inherited
 * annotations from the nearest super-classes/methods, accounting for multiple inheritance).
 */
sealed interface EffectAnnotation<out FX> {
  data class Explicit<out FX>(val annotated: FX, val origin: UElement) : EffectAnnotation<FX> {
    override fun toString() = annotated.toString()
  }

  data class Implicit<out FX>(val nearestBaseAnnotations: List<Explicit<FX>>) : EffectAnnotation<FX>

  companion object {
    val None = Implicit<Nothing>(listOf())
  }
}
