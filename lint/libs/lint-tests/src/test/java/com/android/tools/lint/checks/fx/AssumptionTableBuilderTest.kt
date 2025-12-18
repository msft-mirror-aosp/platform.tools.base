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
package com.android.tools.lint.checks.fx

import com.android.tools.lint.checks.fx.AssumptionTableBuilder.Companion.build
import com.android.tools.lint.checks.fx.AssumptionTableBuilder.Container
import com.android.tools.lint.checks.fx.AssumptionTableBuilder.Pkg
import com.android.tools.lint.checks.fx.result.ClassId
import com.android.tools.lint.checks.fx.result.Constraint
import com.android.tools.lint.checks.fx.result.MethodId
import com.android.tools.lint.checks.fx.result.Type
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.static
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.virtual
import com.android.tools.lint.checks.fx.result.get
import com.android.tools.lint.checks.fx.utils.Lattice
import com.google.common.truth.Truth
import java.io.File
import kotlin.test.assertEquals
import kotlinx.collections.immutable.plus
import org.junit.Test

class AssumptionTableBuilderTest {

  enum class SideEffect {
    Pure,
    Impure;

    companion object : Lattice<SideEffect> {
      override val bottom = Pure
      override val top = Impure

      override fun precede(first: SideEffect, second: SideEffect) = first <= second

      override fun joinOf(first: SideEffect, second: SideEffect) = maxOf(first, second)

      override fun meetOf(first: SideEffect, second: SideEffect) = minOf(first, second)
    }
  }

  @Test
  fun `test reifying types`() {
    assertEquals(Integer::class(), Type.Int)
    assertEquals(Int::class(), Type.Int)
    assertEquals(Unit::class(), Type.Unit)
    assertEquals(String::class(), Type.String)
  }

  @Test
  fun `test assuming standard functions`() {
    val assumptions =
      SideEffect.build {
        // Math::max : (Int, Int) -> Int @ pure
        static<Int, Int>(Math::max) assumedAs
          given(Int::class(), Int::class()) { range = Int::class() }

        // Math::max : (Float, Float) -> Float @ pure
        static<Float, Float>(Math::max) assumedAs
          given(Float::class(), Float::class()) { range = Float::class() }

        // println : () -> Unit @ effectful
        static(::println) assumedAs
          given {
            range = Unit::class()
            concreteEffect = SideEffect.Impure
          }

        // Iterable::map : ∀ {X, Y, F ≼ (X) → Y}. (Iterable<X>, F) -> List<X> @ F(X)
        static<_, (Any?) -> Any>(Iterable<*>::map) assumedAs
          forAll { x ->
            forAll { y ->
              forAll(Function1::class(x, y)) { transform ->
                given(Iterable::class(x), transform) {
                  range = List::class(y)
                  symbolicInvocations += transform[MethodId.Invoke[1], x]
                }
              }
            }
          }

        // File::delete : (File) -> Unit @ effectful
        virtual(File::delete) assumedAs
          given(File::class()) {
            range = Unit::class()
            concreteEffect = SideEffect.Impure
          }
      }

    static<Int, Int>(Math::max).unambiguously { max ->
      with(assumptions[max]!!) {
        Truth.assertThat(typeBounds).isEmpty()
        Truth.assertThat(domains).isEqualTo(listOf(Type.Int, Type.Int))
        Truth.assertThat(range).isEqualTo(Type.Int)
        Truth.assertThat(effect.concrete).isEqualTo(SideEffect.Pure)
        Truth.assertThat(effect.constraint).isEqualTo(Constraint.MostPermissive)
        Truth.assertThat(effect.invocations).isEmpty()
      }
    }

    static<Float, Float>(Math::max).unambiguously { max ->
      with(assumptions[max]!!) {
        Truth.assertThat(typeBounds).isEmpty()
        Truth.assertThat(domains).isEqualTo(listOf(Type.Float, Type.Float))
        Truth.assertThat(range).isEqualTo(Type.Float)
        Truth.assertThat(effect.concrete).isEqualTo(SideEffect.Pure)
        Truth.assertThat(effect.constraint).isEqualTo(Constraint.MostPermissive)
        Truth.assertThat(effect.invocations).isEmpty()
      }
    }

    static(::println).unambiguously { println ->
      with(assumptions[println]!!) {
        Truth.assertThat(typeBounds).isEmpty()
        Truth.assertThat(domains).isEmpty()
        Truth.assertThat(range).isEqualTo(Type.Unit)
        Truth.assertThat(effect.concrete).isEqualTo(SideEffect.Impure)
        Truth.assertThat(effect.constraint).isEqualTo(Constraint.MostPermissive)
        Truth.assertThat(effect.invocations).isEmpty()
      }
    }

    static<_, (Any?) -> Any>(Iterable<*>::map).forEach { map ->
      with(assumptions[map]!!) {
        Truth.assertThat(typeBounds).hasSize(3)
        Truth.assertThat(domains).hasSize(2)
        Truth.assertThat(range).isInstanceOf(Type.Application::class.java)
        Truth.assertThat((range as Type.Application).args).hasSize(1)
        Truth.assertThat((range as Type.Application).args.first())
          .isInstanceOf(Type.Sym.Param::class.java)
        Truth.assertThat(effect.concrete).isEqualTo(SideEffect.Pure)
        Truth.assertThat(effect.constraint).isEqualTo(Constraint.MostPermissive)
        Truth.assertThat(effect.invocations).hasSize(1)
      }
    }
  }

  @Test
  fun `test packages and classes giving correct names`() {
    with(Pkg<SideEffect>("com.pkg")) {
      Truth.assertThat(path).isEqualTo("com.pkg")

      with(child("internal")) {
        Truth.assertThat(path).isEqualTo("com.pkg.internal")

        with(klass("Class1")) {
          Truth.assertThat(prefix).isEqualTo("com.pkg.internal")
          Truth.assertThat(path).isEqualTo("com.pkg.internal")
          Truth.assertThat(self).isEqualTo(ClassId.of("com.pkg.internal.Class1"))
        }
      }
    }

    with(Container<SideEffect>("com.pkg.Class2")) {
      Truth.assertThat(prefix).isEqualTo("com.pkg")
      Truth.assertThat(self).isEqualTo(ClassId.of("com.pkg.Class2"))
    }
  }

  private fun <X, A> Collection<X>.unambiguously(run: (X) -> A): A {
    require(size == 1) { "Expected unambiguous, but got [${joinToString()}]" }
    return run(first())
  }
}
