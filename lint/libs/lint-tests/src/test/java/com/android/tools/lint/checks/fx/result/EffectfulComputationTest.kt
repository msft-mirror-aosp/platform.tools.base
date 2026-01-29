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

import com.android.tools.lint.checks.fx.utils.EffectfulComputation
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.foldM
import com.android.tools.lint.checks.fx.utils.forM
import com.android.tools.lint.checks.fx.utils.lastM
import com.android.tools.lint.checks.fx.utils.mapM
import com.android.tools.lint.checks.fx.utils.possibilityLattice
import com.android.tools.lint.checks.fx.utils.pure
import com.android.tools.lint.checks.fx.utils.unboundedSetOf
import com.google.common.truth.Truth
import org.jetbrains.uast.UExpression
import org.junit.Test

class EffectfulComputationTest : EffectfulComputation<UnboundedSet<String>>, Lattice<UnboundedSet<String>> by possibilityLattice<String>() {

  @Test
  fun `pure returns with no effect`() {
    val res = pure(42)
    Truth.assertThat(res.value).isEqualTo(42)
    Truth.assertThat(res.effect).isEqualTo(bottom)
  }

  @Test
  fun `forM collects and join all effects`() {
    val runForEffect = forM(instrumentedStrLen(), listOf(StringExpr("foo"), StringExpr("bar"), StringExpr("apple")))
    Truth.assertThat(runForEffect).isEqualTo(unboundedSetOf("foo", "bar", "apple"))
  }

  @Test
  fun `lastM returns last computation and join all effects`() {
    val runForLast =
        lastM(
            instrumentedStrLen(),
            listOf(StringExpr("foo"), StringExpr("bar"), StringExpr("apple")),
        )!!
    Truth.assertThat(runForLast.value).isEqualTo("apple".length)
    Truth.assertThat(runForLast.effect).isEqualTo(unboundedSetOf("foo", "bar", "apple"))
  }

  @Test
  fun `lastM returns nothing on empty list`() {
    Truth.assertThat(lastM(instrumentedStrLen(), listOf())).isNull()
  }

  @Test
  fun `mapM transforms list and joins effects`() {
    val runForEach = mapM(instrumentedStrLen(), listOf(StringExpr("foo"), StringExpr("null"), StringExpr("apple")))
    Truth.assertThat(runForEach.value).isEqualTo(listOf("foo".length, "null".length, "apple".length))
    Truth.assertThat(runForEach.effect).isEqualTo(null)
  }

  @Test
  fun `foldM accumulates result and joins effects`() {
    val runFold =
        foldM(
            1,
            Int::times,
            instrumentedStrLen(),
            listOf(StringExpr("foo"), StringExpr("bar"), StringExpr("apple")),
        )
    Truth.assertThat(runFold.value).isEqualTo("foo".length * "bar".length * "apple".length)
    Truth.assertThat(runFold.effect).isEqualTo(unboundedSetOf("foo", "bar", "apple"))
  }

  /**
   * Silly evaluation example of [StringExpr], whose value is the string's length, and effect is the set of strings called, except if the
   * string is "null" then `null`
   */
  private fun instrumentedStrLen(): (StringExpr) -> Result<Int, UnboundedSet<String>> {
    return fun(s) =
        Result(
            value = s.str.length,
            effect =
                when (s.str) {
                  "null" -> null
                  else -> unboundedSetOf(s.str)
                },
        )
  }

  private class StringExpr(val str: String) : UExpression {
    override val uastParent = null
    override val psi = null
    override val uAnnotations = listOf<Nothing>()

    override fun asLogString() = str
  }
}
