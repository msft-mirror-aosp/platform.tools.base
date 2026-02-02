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

import com.android.tools.lint.checks.fx.result.Result
import org.jetbrains.uast.UElement

/**
 * An [EffectfulComputation] tracks effects [FX] from a lattice, and also provides a context-sensitive [merge] operation that may push the
 * result further up the lattice compared to [join]
 */
interface EffectfulComputation<FX> : Lattice<FX> {
  fun merge(context: UElement, left: FX, right: FX): FX = joinOf(left, right)
}

fun <T, FX> Lattice<FX>.pure(value: T): Result<T, FX> = Result(value, bottom)

/** Run [step] on each of [targets] only for the effect [FX] */
fun <X : UElement, FX> EffectfulComputation<FX>.forM(step: (X) -> Result<*, FX>, targets: List<X>): FX =
  foldM(Unit, { _, _ -> }, step, targets).effect

/** Run [step] on each of [targets], only taking the last one's result */
fun <X : UElement, T : Any, FX> EffectfulComputation<FX>.lastM(step: (X) -> Result<T, FX>, targets: List<X>): Result<T, FX>? {
  val (t, fx) = step(targets.firstOrNull() ?: return null)
  return foldM(t, { _, x -> x }, step, targets.subList(1, targets.size), initFx = fx)
}

/** Run [step] on each of [targets], returning the corresponding [T]s and joined effect [FX] */
fun <X : UElement, T, FX> EffectfulComputation<FX>.mapM(step: (X) -> Result<T, FX>, targets: List<X>): Result<List<T>, FX> =
  foldM(ArrayList(targets.size), { l, t -> l.apply { add(t) } }, step, targets)

/** Run [step] on each of [targets], [accum]-ulating result [R] besides joined effect [FX] */
fun <X : UElement, T, R, FX> EffectfulComputation<FX>.foldM(
  init: R,
  accum: (R, T) -> R,
  step: (X) -> Result<T, FX>,
  targets: List<X>,
  initFx: FX = bottom,
): Result<R, FX> {
  var acc = init
  var fx = initFx
  for (target in targets) {
    val (tI, fxI) = step(target)
    fx = merge(target, fx, fxI)
    acc = accum(acc, tI)
  }
  return Result(acc, fx)
}
