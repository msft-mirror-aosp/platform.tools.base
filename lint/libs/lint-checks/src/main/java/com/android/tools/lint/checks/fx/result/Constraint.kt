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
import com.android.tools.lint.checks.fx.utils.Lattice.Companion.dual
import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.assoc
import com.android.tools.lint.checks.fx.utils.possibilityLattice
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.plus

/**
 * During the analysis, we accumulate [Constraint]s on symbolic invocations. At sites where we get a concrete substitution for symbolic
 * invocations, we discharge the constraints if they turn out always true, or report errors if they fail.
 */
data class Constraint<out FX>(
    val concreteUpperbounds: ConcreteUpperBounds<@UnsafeVariance FX>?,
    val symbolicUpperbounds: SymbolicUpperBounds<@UnsafeVariance FX>? = persistentMapOf(),
) {
  constructor(vararg concreteUpperBounds: Pair<Type.Sym.Invoke<FX>, FX>) : this(persistentMapOf(*concreteUpperBounds))

  internal fun prettyPrint(): String {
    fun PersistentMap<*, *>?.formatAsBounds() = this?.asSequence()?.joinToString(" ∧ ") { (l, r) -> "$l ⊑ $r" } ?: "⊤"
    val c1 = concreteUpperbounds.formatAsBounds()
    val c2 = symbolicUpperbounds.formatAsBounds()
    val c =
        when {
          c1.isEmpty() && c2.isEmpty() -> null
          c1.isEmpty() -> c2
          c2.isEmpty() -> c1
          c1 == c2 -> c1
          else -> "$c1, $c2"
        }
    return if (c == null) "" else " (where $c)"
  }

  override fun toString() = prettyPrint() // TODO this means empty string for trivial constraints

  companion object {
    val MostPermissive: Constraint<Nothing> = Constraint(persistentMapOf(), persistentMapOf())
    val LeastPermissive: Constraint<Nothing> = Constraint(null, null)

    fun <FX> concrete(
        upperbound: FX,
        leftHandSides: UnboundedSet<Type.Sym<FX>>,
    ): ConcreteUpperBounds<FX>? = leftHandSides?.assoc { it to upperbound }

    /** A lattice on the concrete effect [FX] induces a lattice on the [Constraint]s */
    fun <FX> domain(onConcrete: Lattice<FX>): Lattice<Constraint<FX>> =
        Lattice.Companion.product(
            ::Constraint,
            Constraint<FX>::concreteUpperbounds,
            Constraint<FX>::symbolicUpperbounds,
            // For upper-bound constraints, joining means conjunction.
            // The absence of an entry means there's no constraint on it (i.e. trivial, most permissive
            // upperbound).
            Lattice.Companion.pointWise(onConcrete.dual()),
            Lattice.Companion.pointWise(possibilityLattice()),
        )
  }
}

internal typealias ConcreteUpperBounds<FX> = PersistentMap<Type.Sym<FX>, FX>

operator fun <FX> Constraint<FX>.plus(constraint: Pair<Type.Sym<FX>, FX>): Constraint<FX> =
    copy(concreteUpperbounds = concreteUpperbounds?.plus(constraint))

internal typealias SymbolicUpperBounds<FX> = PersistentMap<Type.Sym.Invoke<FX>, UnboundedSet<Type.Sym<FX>>>
