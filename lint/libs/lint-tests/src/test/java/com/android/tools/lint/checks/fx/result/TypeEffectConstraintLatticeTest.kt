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
package com.android.tools.lint.checks.fx.result

import com.android.tools.lint.checks.fx.result.Type.Sym
import com.android.tools.lint.checks.fx.result.Type.Sym.Invoke
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.checks.fx.utils.LatticeTest
import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.possibilityLattice
import com.google.common.truth.Truth
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test

private typealias IntsFx = UnboundedSet<Int>

private val concreteEffectLattice = possibilityLattice<Int>()
private val constraintLattice = Constraint.domain(concreteEffectLattice)
private val effectLattice =
  Lattice.product(
    ::Effect,
    Effect<IntsFx>::concrete,
    Effect<IntsFx>::invocations,
    Effect<IntsFx>::constraint,
    concreteEffectLattice,
    possibilityLattice(),
    constraintLattice,
  )

class TypeLatticeTest :
  LatticeTest<Type<IntsFx>>(lattice = Type.latticeOf(effectLattice), poolInits = listOf(Type.Int, Type.Boolean, x, x["f", y]))

class EffectLatticeTest :
  LatticeTest<Effect<IntsFx>>(
    lattice = effectLattice,
    poolInits =
      listOf(
        Effect(persistentSetOf(1, 2, 3), persistentSetOf(x["f", Type.Int])),
        Effect(persistentSetOf(2, 3, 4), persistentSetOf(x["f", Type.String])),
        Effect(persistentSetOf(3, 4, 5), persistentSetOf(x["f", Type.Int, Type.String])),
      ),
  )

class ConstraintLatticeTest :
  LatticeTest<Constraint<IntsFx>>(
    lattice = constraintLattice,
    poolInits =
      listOf(
        Constraint(persistentMapOf(x["f"] to persistentSetOf(1, 2, 3)), persistentMapOf(x["f"] to persistentSetOf(y["g"]))),
        Constraint(persistentMapOf(x["g"] to persistentSetOf(2, 3, 4)), persistentMapOf(x["f"] to persistentSetOf(x["g"]))),
        Constraint(persistentMapOf(x["f"] to persistentSetOf(2, 3, 4)), persistentMapOf(x["f"] to persistentSetOf(y["h"]))),
      ),
  ) {

  @Test
  fun `joining over constraints`() {
    val c1 = Constraint(persistentMapOf(x["f"] to persistentSetOf(1, 2, 3)), persistentMapOf(y["g"] to persistentSetOf(x["h"])))
    val c2 = Constraint(persistentMapOf(x["f"] to persistentSetOf(2, 3, 4)), persistentMapOf(y["g"] to persistentSetOf(x["m"])))

    val c = c1 join c2 // Most permissive constraint implied by `c1` and `c2`
    Truth.assertThat(c.concreteUpperbounds).isEqualTo(persistentMapOf(x["f"] to persistentSetOf(2, 3)))
    Truth.assertThat(c.symbolicUpperbounds).isEqualTo(persistentMapOf(y["g"] to persistentSetOf(x["h"], x["m"])))
  }
}

// Constructor shorthands

internal val x = Sym.Param("x", Scope.Generated(0))
internal val y = Sym.Param("y", Scope.Generated(0))
internal val z = Sym.Param("z", Scope.Generated(0))

internal operator fun <FX> Sym<FX>.get(methodName: String, vararg args: Type<FX>): Invoke<FX> {
  // Method descriptor. Type signature unimportant for this test.
  val methodId = MethodId(true, methodName, List(args.size) { null })
  return Invoke(this, methodId, args.asList())
}
