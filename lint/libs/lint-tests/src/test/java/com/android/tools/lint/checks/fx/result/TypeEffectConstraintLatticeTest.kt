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

import com.android.tools.lint.checks.fx.result.Type.Sym
import com.android.tools.lint.checks.fx.result.Type.Sym.Invoke
import com.android.tools.lint.checks.fx.utils.LatticeTest
import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.possibilityLattice
import com.google.common.truth.Truth
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test

private typealias IntsFx = UnboundedSet<Int>

private val UnitTypeEffectConstraintLattice = TypeEffectConstraintLattice(possibilityLattice<Int>())

class TypeLatticeTest :
  LatticeTest<Type<IntsFx>>(
    lattice = UnitTypeEffectConstraintLattice.typeLattice,
    poolInits = listOf(Type.Int, Type.Boolean, Sym.Param("x"), Sym.Param("x")["f", Sym.Param("y")]),
  ) {

  @Test
  fun `widening works`() {
    Truth.assertThat(widen(Type.Unit, Type.Unit)).isEqualTo(Type.Unit)
    Truth.assertThat(widen(Type.Int, Type.Boolean)).isEqualTo(Type.Int join Type.Boolean)
    Truth.assertThat(widen(Type.Int, Type.Int join Type.Boolean))
      .isEqualTo(Type.Int join Type.Boolean)

    Truth.assertThat(widen(x, x)).isEqualTo(x)
    Truth.assertThat(widen(x, y)).isEqualTo(x join y)
    Truth.assertThat(widen(x, x join y["f"])).isEqualTo(x join y["f"])

    // x
    // y.f(y).g(x)
    // -->
    // μα. x ⊔ y.f(y).g(α)
    testInductiveWidening(x, y["f", y]["g", x] to fix { listOf(x, y["f", y]["g", it]) })

    // x
    // x.f(x.g())
    // -->
    // μα. x ⊔ α.f(α.g())
    testInductiveWidening(x, x["f", x["g"]] to fix { listOf(x, it["f", it["g"]]) })

    // x
    // x.f()
    // x.g()
    // -->
    // μα. x ⊔ α.f() ⊔ α.g()
    testInductiveWidening(
      x,
      x["f"] to fix { listOf(x, it["f"]) },
      x["g"] to fix { listOf(x, it["f"], it["g"]) },
    )

    // x.h()
    // x.h().f().g()
    // x.h().f().g().f().g()
    // -->
    // μα. x.h() ⊔ α.f().g()
    testInductiveWidening(
      x["h"],
      x["h"]["f"]["g"] to fix { listOf(x["h"], it["f"]["g"]) },
      x["h"]["f"]["g"]["f"]["g"] to fix { listOf(x["h"], it["f"]["g"]) },
    )
  }

  @Test
  fun `summary of growing symbolic invocations works`() {
    Truth.assertThat(widen(x["f", Type.Int]["g"]["f", Type.String]))
      .isEqualTo(fix { listOf(x["f", Type.Int], it["g"]["f", Type.String]) })

    Truth.assertThat(
        widen(
          (fix { listOf(x["f", Type.Int], it["g"]["f", Type.String]) })["g"]["f", Type.Int]["g"]
        )
      )
      .isEqualTo(
        fix { listOf(x["f", Type.Int], it["g"], it["f", Type.String], it["f", Type.Int]["g"]) }
      )
  }

  @Test
  fun `no excessive widening`() {
    // `x` and `x ∪ y.f(x)` should get widened to just `x ∪ y.f(x)`, not `μα. x ∪ y.f(α)`
    val sym = y["f", Type.Application(ClassId.of<List<*>>(), listOf(x))]
    Truth.assertThat(widen(x, x join sym)).isEqualTo(x join sym)
  }

  @Test
  fun `widen growing set`() {
    // `x ∪ y.f(x)` and `x ∪ y.f(x) ∪ y.f(x).f(x)` should get widened to `μα.x ∪ y.f(α) ∪ α.f(α)`
    val sym = y["f", x]
    val sym1 = sym["f", x]
    Truth.assertThat(widen(x join sym, x join sym join sym1))
      .isEqualTo(fix { listOf(x, y["f", it], it["f", it]) })
  }
}

class EffectLatticeTest :
  LatticeTest<Effect<IntsFx>>(
    lattice = UnitTypeEffectConstraintLattice.effectLattice,
    poolInits =
      listOf(
        Effect(persistentSetOf(1, 2, 3), persistentSetOf(x["f", Type.Int])),
        Effect(persistentSetOf(2, 3, 4), persistentSetOf(x["f", Type.String])),
        Effect(persistentSetOf(3, 4, 5), persistentSetOf(x["f", Type.Int, Type.String])),
      ),
  ) {

  @Test
  fun `symbolic invocations summarized`() {
    val fx1 = Effect(persistentSetOf(1, 2, 3), persistentSetOf(x["f", Type.Int]))
    val fx2 = Effect(persistentSetOf(2, 3, 4), persistentSetOf(x["f", Type.String]))
    val fx3 = Effect(persistentSetOf(3, 4, 5), persistentSetOf(x["f", Type.Int, Type.String]))
    val fx = widen(fx1, widen(fx2, fx3))
    Truth.assertThat(fx.concrete).isEqualTo(persistentSetOf(1, 2, 3, 4, 5))
    Truth.assertThat(fx.invocations)
      .isEqualTo(
        persistentSetOf(
          x["f", Type.Union(persistentSetOf(Type.Int, Type.String))],
          x["f", Type.Int, Type.String],
        )
      )
  }
}

class ConstraintLatticeTest :
  LatticeTest<Constraint<IntsFx>>(
    lattice = UnitTypeEffectConstraintLattice.constraintLattice,
    poolInits =
      listOf(
        Constraint(
          persistentMapOf(x["f"] to persistentSetOf(1, 2, 3)),
          persistentMapOf(x["f"] to persistentSetOf(y["g"])),
        ),
        Constraint(
          persistentMapOf(x["g"] to persistentSetOf(2, 3, 4)),
          persistentMapOf(x["f"] to persistentSetOf(x["g"])),
        ),
        Constraint(
          persistentMapOf(x["f"] to persistentSetOf(2, 3, 4)),
          persistentMapOf(x["f"] to persistentSetOf(y["h"])),
        ),
      ),
  ) {

  @Test
  fun `joining over constraints`() {
    val c1 =
      Constraint(
        persistentMapOf(x["f"] to persistentSetOf(1, 2, 3)),
        persistentMapOf(y["g"] to persistentSetOf(x["h"])),
      )
    val c2 =
      Constraint(
        persistentMapOf(x["f"] to persistentSetOf(2, 3, 4)),
        persistentMapOf(y["g"] to persistentSetOf(x["m"])),
      )

    val c = c1 join c2 // Most permissive constraint implied by `c1` and `c2`
    Truth.assertThat(c.concreteUpperbounds)
      .isEqualTo(persistentMapOf(x["f"] to persistentSetOf(2, 3)))
    Truth.assertThat(c.symbolicUpperbounds)
      .isEqualTo(persistentMapOf(y["g"] to persistentSetOf(x["h"], x["m"])))
  }
}

// Constructor shorthands

internal val x = Sym.Param("x")
internal val y = Sym.Param("y")
internal val z = Sym.Param("z")

internal fun <FX> fix(body: (Sym<FX>) -> Collection<Sym<FX>>) = Sym.Fix(body(Sym.Rec))

internal operator fun <FX> Sym<FX>.get(methodName: String, vararg args: Type<FX>): Invoke<FX> {
  // Method descriptor. Type signature unimportant for this test.
  val methodId = MethodId(true, methodName, List(args.size) { null })
  return Invoke(this, methodId, args.asList())
}
