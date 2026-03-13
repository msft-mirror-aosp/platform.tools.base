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

import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.possibilityLattice
import kotlinx.collections.immutable.intersect
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus

/** Given a [Lattice] on the concrete effect, mutually recursively define [Lattice]s on [Type], [Effect], and [Constraint] */
class TypeEffectConstraintLattice<FX>(private val fxLattice: Lattice<FX>) {
  val symbolicEffectLattice: Lattice<UnboundedSet<Type.Sym<FX>>> = possibilityLattice()

  val constraintLattice = Constraint.domain(fxLattice)

  val effectLattice =
    Lattice.product(
      ::Effect,
      Effect<FX>::concrete,
      Effect<FX>::invocations,
      Effect<FX>::constraint,
      fxLattice,
      symbolicEffectLattice,
      constraintLattice,
    )

  val typeLattice: Lattice<Type<FX>> = TypeLattice(effectLattice)

  private class TypeLattice<FX>(private val effectLattice: Lattice<Effect<FX>>) : Lattice<Type<FX>> {
    override val bottom = Type.None
    override val top = Type.WildCard

    // Inconsequential for now
    override fun meetOf(first: Type<FX>, second: Type<FX>) =
      when {
        top precedes first -> second
        top precedes second -> first
        first precedes bottom || second precedes bottom -> bottom
        first == second -> first
        second is Type.Union -> meetUnion(first, second)
        first is Type.Union -> meetUnion(second, first)
        else -> bottom
      }

    private fun meetUnion(first: Type<FX>, second: Type.Union<FX>): Type<FX> =
      when (first) {
        is Type.Union -> Type.Union(first.cases intersect second.cases)
        else -> if (first in second.cases) first else bottom
      }

    override fun joinOf(first: Type<FX>, second: Type<FX>): Type<FX> =
      when {
        first is Type.WildCard || second is Type.WildCard -> Type.WildCard
        first is Type.Union && second is Type.Union -> Type.Union(first.cases + second.cases)
        first is Type.Union -> Type.Union(first.cases + second)
        second is Type.Union -> Type.Union(second.cases + first)
        first is Type.Lambda &&
          second is Type.Lambda &&
          first.intf != null &&
          first.intf == second.intf &&
          first.params == second.params -> {
          val (bodyType1, bodyFx1) = first.body
          val (bodyType2, bodyFx2) = second.body
          first.copy(body = Result(joinOf(bodyType1, bodyType2), effectLattice.joinOf(bodyFx1, bodyFx2)))
        }
        else -> Type.Union(persistentSetOf(first, second))
      }

    override fun precede(first: Type<FX>, second: Type<FX>) =
      when {
        second == top -> true
        first == top -> false
        first == bottom -> true
        second == bottom -> false
        first == second -> true
        second is Type.Union ->
          when (first) {
            is Type.Union -> second.cases.containsAll(first.cases)
            else -> first in second.cases
          }
        else -> false
      }
  }
}
