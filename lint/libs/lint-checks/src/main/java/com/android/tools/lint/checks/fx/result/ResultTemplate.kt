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

import kotlinx.collections.immutable.PersistentMap

/**
 * Like [Result], but binding the free type parameters, and tracking the refined [domains].
 *
 * For example, function `id` declared as `fun<X> id(x:X): X = x` has a [Result] of `X @ AnyThread`,
 * and a [ResultTemplate] of `∀X.X → X @ AnyThread`. (We can't reuse the method's descriptor for the
 * [domains], because the descriptor only tracks runtime class tags, erasing type parameters).
 */
data class ResultTemplate<out FX>(
  val typeBounds: TypeBounds<Nothing>,
  val domains: List<Type<Nothing>>,
  val range: Type<FX>,
  val effect: Effect<FX>,
) {
  override fun toString(): String {
    val body = "(${domains.joinToString()}) -> $range @ $effect"
    return when {
      typeBounds.isEmpty() -> body
      else -> "∀ ${typeBounds.format()}. $body"
    }
  }
}

/** An [AssumptionTable] is just a flat table of assumed analysis results */
typealias AssumptionTable<FX> = PersistentMap<ClassId, PersistentMap<MethodId, ResultTemplate<FX>>>

operator fun <FX> AssumptionTable<FX>.get(ref: Type.MethodRef): ResultTemplate<FX>? =
  get(ref.klass)?.get(ref.method)

/** A [ResultTable] contains results that are either assumed or lazily computed/loaded */
interface ResultTable<FX> {
  operator fun get(ref: Type.MethodRef): ResultTemplate<FX>?

  companion object {
    fun <FX> of(assumptions: AssumptionTable<FX>): ResultTable<FX> =
      object : ResultTable<FX> {
        override fun get(ref: Type.MethodRef) = assumptions[ref]
      }
  }
}
