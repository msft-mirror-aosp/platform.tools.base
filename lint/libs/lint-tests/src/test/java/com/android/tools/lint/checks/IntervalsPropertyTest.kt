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
package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Intervals
import com.android.tools.lint.detector.api.Intervals.Companion.atLeast
import com.android.tools.lint.detector.api.Intervals.Companion.atMost
import com.android.tools.lint.detector.api.Intervals.Companion.below
import com.android.tools.lint.detector.api.Intervals.Companion.exactly
import com.android.tools.lint.detector.api.Intervals.Companion.range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.theories.DataPoints
import org.junit.experimental.theories.Theories
import org.junit.experimental.theories.Theory
import org.junit.runner.RunWith

/**
 * Each theory is checked for every assignment of its parameters from the data points ([pool] for `Intervals`, [levels] for `Int`); a
 * failure reports the first assignment that violates it.
 */
@RunWith(Theories::class)
class IntervalsPropertyTest {

  companion object {
    /**
     * Hand-picked generators: bounded/open-ended/exact intervals, with and without minor versions, plus empty and reversed ranges, ranges
     * starting below the lowest level 1, and a set of three intervals (which pairwise combinations of single intervals never reach).
     */
    private val generators: List<Intervals> =
      listOf(
        Intervals.NONE,
        Intervals.ALL,
        exactly(12),
        exactly(15, 1),
        atLeast(12),
        atLeast(15, 2),
        below(5),
        below(16, 2),
        atMost(12),
        range(10, 12),
        range(14, 17),
        range(10, 1, 13, 0),
        range(5, 5), // empty
        range(2, 1), // also empty
        atLeast(0), // same as ALL
        exactly(0), // empty
        range(0, 5), // same as below(5)
        exactly(3) or range(6, 8) or atLeast(16),
      )

    /**
     * The domain: generators, their complements, and all pairwise intersections and unions. The derived values are deduplicated by concrete
     * representation (class + serialized form) rather than by [Intervals.equals], since the latter is itself under test. The generators are
     * kept as is, since a serialized form may conflate distinct non-canonical representations.
     */
    @JvmField
    @DataPoints
    val pool: List<Intervals> =
      generators +
        buildList {
          for (x in generators) add(!x)
          for (x in generators) for (y in generators) {
            add(x and y)
            add(x or y)
          }
        }
          .distinctBy { "${it.javaClass.simpleName}:${it.serialize()}" }

    /** API levels for the theories that take one, covering the whole probe grid plus the level 0 below it. */
    @JvmField @DataPoints val levels = 0..MAX_MAJOR

    /** Bounds of the probe grid. */
    private const val MAX_MAJOR = 34
    private const val MAX_MINOR = 5

    /**
     * Atoms `exactly(major, minor)` with major from 1 to [MAX_MAJOR] and minor from 0 to [MAX_MINOR], starting at the lowest level 1. All
     * endpoints from the [pool] must be on the grid, and the top majors of the grid must lie above all finite endpoints in the [pool]. The
     * [probes] are in ascending order, so positions in the list stand for the lexicographic order of `major.minor`.
     */
    private val probes: List<Intervals> = probesFrom(1).toList()

    /** The probes from `major.0` up, in ascending order. */
    private fun probesFrom(major: Int): Sequence<Intervals> = sequence {
      for (m in maxOf(major, 1)..MAX_MAJOR) for (minor in 0..MAX_MINOR) yield(exactly(m, minor))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // `Intervals` makes a boolean algebra
  //   ⊥     := NONE
  //   ⊤     := ALL
  //   x ⊑ y := y.alwaysAtLeast(x)
  //   ⊔     := or
  //   ⊓     := and
  //   ¬     := not
  // ---------------------------------------------------------------------------------------------

  @Theory fun `order is reflexive`(x: Intervals) = assertTrue(x.alwaysAtLeast(x))

  @Theory
  fun `order is transitive`(x: Intervals, y: Intervals) = assertAll { z ->
    (x.alwaysAtLeast(y) && y.alwaysAtLeast(z)) implies { x.alwaysAtLeast(z) }
  }

  @Theory
  fun `order is anti-symmetric`(x: Intervals, y: Intervals) = assertTrue((y.alwaysAtLeast(x) && x.alwaysAtLeast(y)) implies { x == y })

  @Theory fun `NONE is least`(x: Intervals) = assertTrue(x.alwaysAtLeast(Intervals.NONE))

  @Theory fun `ALL is greatest`(x: Intervals) = assertTrue(Intervals.ALL.alwaysAtLeast(x))

  @Theory
  fun `and gives the greatest lower bound`(x: Intervals, y: Intervals) {
    val meet = x and y
    assertTrue(x.alwaysAtLeast(meet) && y.alwaysAtLeast(meet))
    assertAll { lower -> (x.alwaysAtLeast(lower) && y.alwaysAtLeast(lower)) implies { meet.alwaysAtLeast(lower) } }
  }

  @Theory
  fun `or gives the least upper bound`(x: Intervals, y: Intervals) {
    val join = x or y
    assertTrue(join.alwaysAtLeast(x) && join.alwaysAtLeast(y))
    assertAll { upper -> (upper.alwaysAtLeast(x) && upper.alwaysAtLeast(y)) implies { upper.alwaysAtLeast(join) } }
  }

  @Theory fun `and is commutative`(x: Intervals, y: Intervals) = assertEquals(y and x, x and y)

  @Theory fun `or is commutative`(x: Intervals, y: Intervals) = assertEquals(y or x, x or y)

  @Theory fun `and is associative`(x: Intervals, y: Intervals) = assertAll { z -> x and (y and z) == (x and y) and z }

  @Theory fun `or is associative`(x: Intervals, y: Intervals) = assertAll { z -> x or (y or z) == (x or y) or z }

  @Theory fun `and distributes over or`(x: Intervals, y: Intervals) = assertAll { z -> (x and y) or (x and z) == x and (y or z) }

  @Theory fun `or distributes over and`(x: Intervals, y: Intervals) = assertAll { z -> (x or y) and (x or z) == x or (y and z) }

  @Theory
  fun `NONE is identity of or`(x: Intervals) {
    assertEquals(x, x or Intervals.NONE)
    assertEquals(x, Intervals.NONE or x)
  }

  @Theory
  fun `ALL is identity of and`(x: Intervals) {
    assertEquals(x, x and Intervals.ALL)
    assertEquals(x, Intervals.ALL and x)
  }

  @Theory fun `or with complement gives ALL`(x: Intervals) = assertEquals(Intervals.ALL, x or !x)

  @Theory fun `and with complement gives NONE`(x: Intervals) = assertEquals(Intervals.NONE, x and !x)

  // ---------------------------------------------------------------------------------------------
  // Derived laws. But as tests, they are not redundant.
  // ---------------------------------------------------------------------------------------------

  @Theory fun `and is idempotent`(x: Intervals) = assertEquals(x, x and x)

  @Theory fun `or is idempotent`(x: Intervals) = assertEquals(x, x or x)

  @Theory
  fun `NONE is annihilator of and`(x: Intervals) {
    assertEquals(Intervals.NONE, x and Intervals.NONE)
    assertEquals(Intervals.NONE, Intervals.NONE and x)
  }

  @Theory
  fun `ALL is annihilator of or`(x: Intervals) {
    assertEquals(Intervals.ALL, x or Intervals.ALL)
    assertEquals(Intervals.ALL, Intervals.ALL or x)
  }

  @Theory fun `and absorbs or`(x: Intervals, y: Intervals) = assertEquals(x, x and (x or y))

  @Theory fun `or absorbs and`(x: Intervals, y: Intervals) = assertEquals(x, x or (x and y))

  @Theory fun `double negation is identity`(x: Intervals) = assertEquals(x, !(!x))

  @Theory fun `De Morgan law holds for or`(x: Intervals, y: Intervals) = assertEquals(!x and !y, !(x or y))

  @Theory fun `De Morgan law holds for and`(x: Intervals, y: Intervals) = assertEquals(!x or !y, !(x and y))

  @Test
  fun `NONE and ALL are complementary`() {
    assertEquals(Intervals.ALL, !Intervals.NONE)
    assertEquals(Intervals.NONE, !Intervals.ALL)
  }

  @Theory
  fun `complements are unique`(x: Intervals, y: Intervals) =
    assertTrue((x or y == Intervals.ALL && x and y == Intervals.NONE) implies { !x == y })

  // ---------------------------------------------------------------------------------------------
  // Pointwise tests: each operation agrees with membership of every probe point
  // ---------------------------------------------------------------------------------------------

  @Theory
  fun `or is pointwise disjunction`(x: Intervals, y: Intervals) = assertAllProbes { p ->
    (x or y).alwaysAtLeast(p) == (x.alwaysAtLeast(p) || y.alwaysAtLeast(p))
  }

  @Theory
  fun `and is pointwise conjunction`(x: Intervals, y: Intervals) = assertAllProbes { p ->
    (x and y).alwaysAtLeast(p) == (x.alwaysAtLeast(p) && y.alwaysAtLeast(p))
  }

  @Theory fun `not is pointwise negation`(x: Intervals) = assertAllProbes { p -> (!x).alwaysAtLeast(p) == !x.alwaysAtLeast(p) }

  @Theory
  fun `equality is extensional`(x: Intervals, y: Intervals) =
    assertEquals(probes.all { p -> x.alwaysAtLeast(p) == y.alwaysAtLeast(p) }, x == y)

  @Theory
  fun `equal values have equal hash codes`(x: Intervals, y: Intervals) = assertTrue((x == y) implies { x.hashCode() == y.hashCode() })

  @Theory fun `isEmpty means no members`(x: Intervals) = assertEquals(probes.none(x::alwaysAtLeast), x.isEmpty())

  @Theory fun `isAll means all members`(x: Intervals) = assertEquals(probes.all(x::alwaysAtLeast), x.isAll())

  @Theory
  fun `contains means the atom at the level is a member`(x: Intervals, level: Int) =
    assertTrue((level >= 1) implies { x.alwaysAtLeast(exactly(level, 0)) == x.contains(level) })

  @Theory fun `serialization round-trips`(x: Intervals) = assertEquals(x, Intervals.deserialize(x.serialize()))

  @Theory
  fun `deserialize unions comma-joined serializations`(x: Intervals, y: Intervals) {
    // Serialized data from older versions may be unsorted, overlapping, adjacent, or contain empty components; deserialize must
    // re-canonicalize. Joining two arbitrary serializations with a comma produces exactly such data, denoting the union.
    assertEquals(x or y, Intervals.deserialize("${x.serialize()},${y.serialize()}"))
  }

  @Theory
  fun `everHigher means some member above the level`(x: Intervals, level: Int) =
    // the probe grid must reach above the level
    assertTrue(
      (level in 1..<MAX_MAJOR) implies
        {
          probesFrom(level + 1).any(x::alwaysAtLeast) == x.everHigher(level)
        }
    )

  @Theory
  fun `fromInclusive is the least member`(x: Intervals) {
    assertTrue(
      x.isNotEmpty() implies
        {
          probes.first(x::alwaysAtLeast) == exactly(x.fromInclusive(), x.fromInclusiveMinor())
        }
    )
  }

  @Theory
  fun `toExclusive is a tight upper bound`(x: Intervals) {
    if (x.isEmpty() || x.isOpenEnded()) return
    // Position of the probe at the upper bound; the one before it is the probe right below the upper bound. The pool's endpoint minors
    // stay well below the probe grid's minor range, so this is exact (see [probes]).
    val to = probes.indexOf(exactly(x.toExclusive(), x.toExclusiveMinor()))
    assertTrue("upper bound ${x.toExclusive()}.${x.toExclusiveMinor()} is on the probe grid", to > 0)
    assertTrue(probes.drop(to).none(x::alwaysAtLeast))
    assertTrue(x.alwaysAtLeast(probes[to - 1]))
  }

  @Theory
  fun `alwaysAtLeast means inclusion`(x: Intervals, y: Intervals) =
    assertEquals(probes.all { !y.alwaysAtLeast(it) || x.alwaysAtLeast(it) }, x.alwaysAtLeast(y))

  @Theory
  fun `alwaysAtLeast of a level means all probes from it are members`(x: Intervals, level: Int) =
    assertEquals(probesFrom(level).all(x::alwaysAtLeast), x.alwaysAtLeast(level))

  @Theory
  fun `isAtLeast compares least members`(x: Intervals, y: Intervals) {
    val minX = probes.indexOfFirst(x::alwaysAtLeast)
    val minY = probes.indexOfFirst(y::alwaysAtLeast)
    assertTrue((minX >= 0 && minY >= 0) implies { minX >= minY == x.isAtLeast(y) })
  }

  @Theory fun `isOpenEnded means the top probe is a member`(x: Intervals) = assertEquals(x.alwaysAtLeast(probes.last()), x.isOpenEnded())

  /** Use when we don't care that each element makes a separate test case, or when that would be too expensive */
  private fun assertAll(prop: (Intervals) -> Boolean) = pool.forEach { assertTrue(it.toString(), prop(it)) }

  /** Asserts [prop] at every probe, reporting the first one that violates it. */
  private fun assertAllProbes(prop: (Intervals) -> Boolean) = probes.forEach { assertTrue("at $it", prop(it)) }

  private infix fun Boolean.implies(conclusion: () -> Boolean) = !this || conclusion()
}
