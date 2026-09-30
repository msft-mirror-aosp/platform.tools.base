/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.lint.detector.api

/**
 * Represents a series of half-open intervals, such as `[10, 12), [14, 25), [30,∞)`, and operations for taking intersections of these,
 * checking for membership, and so on. Note that the lowest possible starting point is 1 rather than negative infinity. This is because of
 * its primary intended use case: representing API constraints.
 */
sealed class Intervals {
  override fun toString(): String {
    return toString(compact = false)
  }

  /**
   * The starting point of the interval, inclusive. Note that this is only the major part of the number; the minor part can be looked up via
   * [fromInclusiveMinor]. So an interval starting at "42.5" would return "42" from this method, and "5" from the other.
   */
  abstract fun fromInclusive(): Int

  /**
   * The *minor part* of the starting point of the interval, inclusive. Note that this is only the minor part of the number; the major part
   * can be looked up via [fromInclusive]. So an interval starting at "42.5" would return "5" from this method, and "42" from the other.
   */
  abstract fun fromInclusiveMinor(): Int

  /**
   * The ending point of the interval, exclusive. Note that this is only the major part of the number; the minor part can be looked up via
   * [toExclusiveMinor]. So an interval ending at "42.5" would return "42" from this method, and "5" from the other.
   */
  abstract fun toExclusive(): Int

  /**
   * The *minor part* of the ending point of the interval, exclusive. Note that this is only the minor part of the number; the major part
   * can be looked up via [toExclusive]. So an interval ending at "42.5" would return "5" from this method, and "42" from the other.
   */
  abstract fun toExclusiveMinor(): Int

  /**
   * Represents this interval as a string. For example, the interval starting at 3 and ending after 6 would be shown as "x ≥ 10 and x < 12"
   * , or "10 ≤ x < 12" in [compact] mode. The [variable] can be customized, for example, [ApiConstraint] uses "API level" instead.
   */
  abstract fun toString(variable: String = "x", compact: Boolean = false): String

  /** Intersects two intervals */
  infix fun and(other: Intervals): Intervals =
    when {
      isEmpty() -> NONE
      other.isEmpty() -> NONE
      this is Span && other is Span -> {
        // Fast path for the overwhelmingly common single-interval operands, skipping the general sweep
        val start = kotlin.math.max(this.from.bits, other.from.bits)
        val end = kotlin.math.min(this.to.bits, other.to.bits)
        when {
          start >= end -> NONE
          start == this.from.bits && end == this.to.bits -> this
          start == other.from.bits && end == other.to.bits -> other
          else -> Span(MajorMinor(start), MajorMinor(end))
        }
      }
      else -> combine(boundsArray(), other.boundsArray(), minCoverage = 2)
    }

  /** Returns the union of two intervals */
  infix fun or(other: Intervals): Intervals =
    when {
      isEmpty() -> other
      other.isEmpty() -> this
      this is Span && other is Span ->
        // Fast path for the overwhelmingly common single-interval operands, skipping the general sweep
        when {
          this.from.bits <= other.to.bits && other.from.bits <= this.to.bits -> {
            // Overlapping or touching intervals merge into a single one
            val start = kotlin.math.min(this.from.bits, other.from.bits)
            val end = kotlin.math.max(this.to.bits, other.to.bits)
            when {
              start == this.from.bits && end == this.to.bits -> this
              start == other.from.bits && end == other.to.bits -> other
              else -> Span(MajorMinor(start), MajorMinor(end))
            }
          }
          this.from < other.from -> Spans(intArrayOf(this.from.bits, this.to.bits, other.from.bits, other.to.bits))
          else -> Spans(intArrayOf(other.from.bits, other.to.bits, this.from.bits, this.to.bits))
        }
      else -> combine(boundsArray(), other.boundsArray(), minCoverage = 1)
    }

  /** Returns the inverse of the current interval */
  abstract operator fun not(): Intervals

  /** Returns true if this interval is equal to another. */
  abstract override fun equals(other: Any?): Boolean

  /** Returns the hashcode of this interval. */
  abstract override fun hashCode(): Int

  /** Returns true if this interval is empty. */
  abstract fun isEmpty(): Boolean

  /** Returns true if this interval is **not** empty. */
  fun isNotEmpty() = !isEmpty()

  /** Returns true if this interval includes everything (e.g. from lowest possible starting point up to infinity.) */
  abstract fun isAll(): Boolean

  /** Returns true if the given value is included within this interval */
  abstract fun contains(value: Int): Boolean

  /** Serializes this constraint into a String, which can later be retrieved by calling [deserialize]. */
  abstract fun serialize(): String

  /** Is this [Intervals] at least as high as the given [other] intervals ? */
  fun isAtLeast(other: Intervals): Boolean = lowestBound() >= other.lowestBound()

  /** The lower endpoint of the first interval; an empty set acts as starting at [ZERO]. */
  private fun lowestBound(): MajorMinor =
    when (this) {
      is Span -> from
      is Spans -> if (bounds.isEmpty()) ZERO else MajorMinor(bounds[0])
    }

  /** This set as a boundary array in the [Spans] encoding, for the general [combine] sweep; [Span] materializes it on demand. */
  private fun boundsArray(): IntArray =
    when (this) {
      is Span -> intArrayOf(from.bits, to.bits)
      is Spans -> bounds
    }

  /**
   * Will this API level or anything higher always match this constraint?
   *
   * For example, if we know from minSdkVersion that SDK_INT >= 32, and we see a check if SDK_INT is >= 21, that check will always be true.
   * That's what this method is for; this [ApiConstraint] is the SDK_INT check, and the passed in [minSdk] represents the minimum value of
   * SDK_INT (the known constraint from minSdkVersion).
   */
  fun alwaysAtLeast(minSdk: Intervals): Boolean = containsAll(minSdk)

  fun alwaysAtLeast(apiLevel: Int): Boolean {
    // Same as `containsAll(atLeast(apiLevel))` without allocating: the last interval must be open-ended and start no later than the level
    val version = MajorMinor(apiLevel, 0)
    val level = if (version < ZERO) ZERO else version
    return when (this) {
      is Span -> to == INFINITY && from <= level
      is Spans -> bounds.isNotEmpty() && bounds.last() == INFINITY.bits && MajorMinor(bounds[bounds.size - 2]) <= level
    }
  }

  /** Is every value in [other] also in this set? Both are canonical, so this is a single pass over the intervals of each. */
  private fun containsAll(other: Intervals): Boolean {
    if (other.isEmpty()) return true
    if (this is Span && other is Span) return from <= other.from && other.to <= to
    val outer = boundsArray()
    val inner = other.boundsArray()
    var i = 0
    var j = 0
    while (j < inner.size) {
      // Skip the intervals of this set that end before the current interval of the other starts
      while (i < outer.size && outer[i + 1] <= inner[j]) i += 2
      if (i == outer.size || inner[j] < outer[i] || outer[i + 1] < inner[j + 1]) return false
      j += 2
    }
    return true
  }

  /** Returns true if this interval contains any numbers higher than the given level. */
  fun everHigher(apiLevel: Int): Boolean {
    val threshold = MajorMinor(apiLevel + 1, 0)
    return when (this) {
      is Span -> to > threshold
      is Spans -> bounds.isNotEmpty() && MajorMinor(bounds.last()) > threshold
    }
  }

  /**
   * Returns true if this is an "open ended" constraint, e.g. it goes up to infinity. True for something like "SDK_INT >= 31", and false for
   * "SDK_INT < 24".
   */
  fun isOpenEnded(): Boolean {
    return isInfinity(toExclusive())
  }

  /** Encoding of `major.minor` integer numbers into a single primitive integer. This simplifies various range checking etc. */
  @JvmInline
  private value class MajorMinor(val bits: Int) : Comparable<MajorMinor> {
    constructor(major: Int, minor: Int) : this((major shl MAJOR_SHIFT) + minor)

    val major: Int
      get() = if (this == INFINITY) Integer.MAX_VALUE else bits shr MAJOR_SHIFT

    val minor: Int
      get() = bits and MINOR_MASK

    override fun compareTo(other: MajorMinor): Int {
      return bits.compareTo(other.bits)
    }

    override fun toString(): String {
      return if (minor > 0) {
        "$major.$minor"
      } else {
        major.toString()
      }
    }
  }

  /** Represents a single non-empty half-open interval from `[fromInclusive.fromInclusiveMinor, toExclusive.toExclusiveMinor)`. */
  private class Span(
    /**
     * The start of the span, encoded as the major version, left shifted [MAJOR_SHIFT] bits and then the minor version added in. From is
     * inclusive, and to is exclusive.
     */
    val from: MajorMinor,
    val to: MajorMinor,
  ) : Intervals() {
    override fun fromInclusive(): Int = from.major

    override fun fromInclusiveMinor(): Int = from.minor

    override fun toExclusive(): Int = to.major

    override fun toExclusiveMinor(): Int = to.minor

    override fun isEmpty(): Boolean = false

    override fun contains(value: Int): Boolean {
      return MajorMinor(value, 0) in from..<to
    }

    override fun serialize() = buildString { appendInterval(from, to) }

    override fun not(): Intervals {
      return when {
        this == ALL -> NONE
        from == ZERO -> Span(to, INFINITY)
        to == INFINITY -> Span(ZERO, from)
        else -> Spans(intArrayOf(ZERO.bits, from.bits, to.bits, INFINITY.bits))
      }
    }

    override fun toString(variable: String, compact: Boolean): String = intervalToString(from, to, variable, compact)

    override fun isAll(): Boolean {
      return from == ZERO && to == INFINITY
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Span

      return this.from == other.from && this.to == other.to
    }

    override fun hashCode(): Int {
      return 31 * from.bits + to.bits
    }
  }

  /**
   * 0 or 2+ intervals: sorted, non-empty, non-overlapping and non-adjacent. The [IntArray] encodes the [MajorMinor] bits: elements at even
   * indices are interval starts, and odd indices (exclusive) ends.
   */
  private class Spans(val bounds: IntArray) : Intervals() {
    override fun fromInclusive(): Int {
      if (bounds.isEmpty()) {
        return -1
      }
      return MajorMinor(bounds[0]).major
    }

    override fun fromInclusiveMinor(): Int {
      if (bounds.isEmpty()) {
        return 0
      }
      return MajorMinor(bounds[0]).minor
    }

    override fun toExclusive(): Int {
      if (bounds.isEmpty()) {
        return ZERO.major
      }
      return MajorMinor(bounds.last()).major
    }

    override fun toExclusiveMinor(): Int {
      if (bounds.isEmpty()) {
        return 0
      }
      return MajorMinor(bounds.last()).minor
    }

    override fun isEmpty(): Boolean {
      return bounds.isEmpty()
    }

    override fun isAll(): Boolean {
      // A single interval is always represented as a [Span], so a [Spans] can never cover everything
      return false
    }

    override fun contains(value: Int): Boolean {
      val index = bounds.binarySearch(MajorMinor(value, 0).bits)
      // Matching a start (even index) means inclusion, and matching an end (odd index) exclusion; otherwise the value is inside an
      // interval exactly when the insertion point is past a start but before its end, i.e. odd.
      return if (index >= 0) index % 2 == 0 else (-index - 1) % 2 == 1
    }

    override fun toString(variable: String, compact: Boolean): String {
      if (bounds.isEmpty()) {
        return "No ${variable}s"
      }
      // Special case: if these intervals cover `{[1, X), [X+1,∞)}`, meaning everything except X, show just "x ≠ X" instead. See
      // [adjacent] for how "everything except X" is identified.
      if (
        bounds.size == 4 && bounds[0] == ZERO.bits && bounds[3] == INFINITY.bits && adjacent(MajorMinor(bounds[1]), MajorMinor(bounds[2]))
      ) {
        return "$variable ≠ ${MajorMinor(bounds[1])}"
      }
      return (bounds.indices step 2).joinToString(" or ") { index ->
        intervalToString(MajorMinor(bounds[index]), MajorMinor(bounds[index + 1]), variable, compact)
      }
    }

    override fun not(): Intervals {
      // The complement within `[ZERO, ∞)` just toggles the presence of ZERO at the front and INFINITY at the back
      val hasZero = bounds.isNotEmpty() && bounds.first() == ZERO.bits
      val hasInfinity = bounds.isNotEmpty() && bounds.last() == INFINITY.bits
      val from = if (hasZero) 1 else 0
      val to = if (hasInfinity) bounds.size - 1 else bounds.size
      val inverse = IntArray((if (hasZero) 0 else 1) + (to - from) + (if (hasInfinity) 0 else 1))
      var next = 0
      if (!hasZero) inverse[next++] = ZERO.bits
      for (index in from until to) inverse[next++] = bounds[index]
      if (!hasInfinity) inverse[next] = INFINITY.bits
      return create(inverse)
    }

    override fun serialize(): String {
      if (bounds.isEmpty()) {
        return "0"
      }
      return buildString {
        for (index in bounds.indices step 2) {
          if (index > 0) append(',')
          appendInterval(MajorMinor(bounds[index]), MajorMinor(bounds[index + 1]))
        }
      }
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Spans

      return bounds.contentEquals(other.bounds)
    }

    override fun hashCode(): Int {
      return bounds.contentHashCode()
    }
  }

  companion object {
    // number of bits to reserve for the minor part
    private const val MAJOR_SHIFT = 6

    // Not using Integer.MAX_VALUE since we're shifting this thing around
    private val INFINITY = MajorMinor(Integer.MAX_VALUE)

    /** Zero (smallest value) */
    private val ZERO = MajorMinor(1, 0)

    /** The increment from one major API level to the next in the combined bitmasks. */
    private const val API_LEVEL_DELTA = 1 shl MAJOR_SHIFT

    /** The mask covering the bits for the minor version. */
    private const val MINOR_MASK = (1 shl MAJOR_SHIFT) - 1

    /**
     * Creates a single interval, clamped to the domain `[ZERO, ∞)`, normalizing all empty ranges to [NONE] so that representations stay
     * canonical.
     */
    private fun spanOrNone(from: MajorMinor, to: MajorMinor): Intervals {
      val start = if (from < ZERO) ZERO else from
      return if (start >= to) NONE else Span(start, to)
    }

    /** This value, all the way up to the next value. */
    fun exactly(major: Int): Intervals = range(major, 0, major + 1, 0)

    /*
     * When you specify a minor version, it's picking just this minor version, not other minor versions
     * for the same API level.
     */
    fun exactly(major: Int, minor: Int): Intervals = range(major, minor, major, minor + 1)

    fun atLeast(major: Int, minor: Int = 0): Intervals = spanOrNone(MajorMinor(major, minor), INFINITY)

    fun atMost(toExclusive: Int): Intervals = range(1, 0, toExclusive + 1, 0)

    fun below(toExclusive: Int, minor: Int = 0): Intervals = range(1, 0, toExclusive, minor)

    fun range(fromInclusive: Int, toExclusive: Int): Intervals = range(fromInclusive, 0, toExclusive, 0)

    fun range(fromInclusive: Int, fromInclusiveMinor: Int, toExclusive: Int, toExclusiveMinor: Int): Intervals =
      spanOrNone(MajorMinor(fromInclusive, fromInclusiveMinor), MajorMinor(toExclusive, toExclusiveMinor))

    fun deserialize(s: String): Intervals {
      if (s.contains(',')) {
        // Union the components one by one: serialized data from older versions may not be canonical (sorted, disjoint, non-adjacent)
        return s.split(',').fold(NONE) { acc, component -> acc or deserialize(component) }
      }
      if (s == "0") {
        return NONE
      } else if (s.endsWith("-∞")) {
        val fromDot = s.indexOf('.')
        val from: Int
        val fromMinor: Int
        if (fromDot != -1) {
          from = s.substring(0, fromDot).toInt()
          fromMinor = s.substring(fromDot + 1, s.length - 2).toInt()
        } else {
          from = s.substring(0, s.length - 2).toInt()
          fromMinor = 0
        }
        return spanOrNone(MajorMinor(from, fromMinor), INFINITY)
      }
      val separator = s.indexOf('-')
      val fromInclusive: Int
      val fromInclusiveMinor: Int
      val toExclusive: Int
      val toExclusiveMinor: Int
      val fromDot = s.indexOf('.')
      val toDot = s.indexOf('.', separator + 1)

      if (fromDot != -1 && fromDot < separator) {
        fromInclusive = s.substring(0, fromDot).toInt()
        fromInclusiveMinor = s.substring(fromDot + 1, separator).toInt()
      } else {
        fromInclusive = s.substring(0, separator).toInt()
        fromInclusiveMinor = 0
      }
      if (toDot != -1) {
        toExclusive = s.substring(separator + 1, toDot).toInt()
        toExclusiveMinor = s.substring(toDot + 1).toInt()
      } else {
        toExclusive = s.substring(separator + 1).toInt()
        toExclusiveMinor = 0
      }
      return range(fromInclusive, fromInclusiveMinor, toExclusive, toExclusiveMinor)
    }

    val NONE: Intervals = Spans(IntArray(0))
    val ALL: Intervals = Span(ZERO, INFINITY)

    private fun create(bounds: IntArray): Intervals =
      when (bounds.size) {
        0 -> NONE
        2 -> Span(MajorMinor(bounds[0]), MajorMinor(bounds[1]))
        else -> Spans(bounds)
      }

    /**
     * Combines two sets of intervals, returning their union when [minCoverage] is 1 and their intersection when it is 2.
     *
     * Each of [bounds1] and [bounds2] is a strictly increasing array of boundaries, where even indices start an interval and odd indices
     * end it, so crossing any boundary toggles membership.
     *
     * We sweep over the boundaries of both arrays in increasing order while counting how many intervals cover the current position (0, 1 or
     * 2): passing a start boundary adds 1; passing an end boundary subtracts 1. Whenever the coverage crosses [minCoverage], we emit the
     * current position as a result boundary. A boundary value may appear in both arrays (as two starts, two ends, or one of each); we
     * process both before deciding whether a crossing happened, so touching intervals coalesce in unions and zero-width regions vanish in
     * intersections, keeping the result canonical. The sweep stops as soon as one array runs out, since the rest of the other one is then
     * copied verbatim by a union and dropped by an intersection.
     */
    private fun combine(bounds1: IntArray, bounds2: IntArray, minCoverage: Int): Intervals {
      val result = IntArray(bounds1.size + bounds2.size)
      var resultLength = 0
      var i1 = 0
      var i2 = 0
      var coverage = 0
      while (i1 < bounds1.size && i2 < bounds2.size) {
        val nextBound = minOf(bounds1[i1], bounds2[i2])
        val wasCovered = coverage >= minCoverage
        if (bounds1[i1] == nextBound) {
          coverage += if (i1 % 2 == 0) 1 else -1
          i1++
        }
        if (bounds2[i2] == nextBound) {
          coverage += if (i2 % 2 == 0) 1 else -1
          i2++
        }
        if (wasCovered != coverage >= minCoverage) {
          result[resultLength++] = nextBound
        }
      }
      // One operand is exhausted and contributes no coverage from here on, so every remaining boundary of the other crosses a threshold
      // of 1 and none reaches a threshold of 2
      if (minCoverage == 1) {
        bounds1.copyInto(result, destinationOffset = resultLength, startIndex = i1)
        resultLength += bounds1.size - i1
        bounds2.copyInto(result, destinationOffset = resultLength, startIndex = i2)
        resultLength += bounds2.size - i2
      }
      return when (resultLength) {
        0 -> NONE
        2 -> Span(MajorMinor(result[0]), MajorMinor(result[1]))
        else -> Spans(if (resultLength == result.size) result else result.copyOf(resultLength))
      }
    }

    /** Appends the single interval `[from, to)` in its serialized form; see [serialize] and [deserialize]. */
    private fun StringBuilder.appendInterval(from: MajorMinor, to: MajorMinor) {
      append(from.toString())
      append('-')
      if (to == INFINITY) append('∞') else append(to.toString())
    }

    /** Represents the single interval `[from, to)` as a string; see [Intervals.toString]. */
    private fun intervalToString(from: MajorMinor, to: MajorMinor, variable: String, compact: Boolean): String {
      return when {
        adjacent(from, to) -> "$variable = $from"
        from == ZERO ->
          when (to) {
            INFINITY -> "All ${variable}s"
            else -> "$variable < $to"
          }
        to == INFINITY -> "$variable ≥ $from"
        compact -> "$from ≤ $variable < $to"
        else -> "$variable ≥ $from and $variable < $to"
      }
    }

    /**
     * Returns true if the given level (typically returned from [fromInclusive] or [toExclusive] represents infinity, e.g. it's an
     * open-ended interval such as `x >= 10`, represented as the interval `[x, ∞)`.
     */
    fun isInfinity(level: Int): Boolean {
      return level == INFINITY.bits
    }

    /**
     * Returns true if these version numbers are right next to each other. Normally, this is as simple as "is the value one less than the
     * neighbor?". But with minor versions, there is some ambiguity. Consider the expression "x = 12". If we take inverse of this, we have
     * the spans 0 up to and not including 12. But for the other side, do we take 12.1 and up, or do we take 13 and up? For now, we
     * generally operate on whole numbers, unless the version is specifically constructed with a minor version.
     *
     * Sometimes we want to reverse this, for example, when we have the intervals corresponding to every value except 12, we want to display
     * this as just "x != 12". This method handles checking for this adjacency.
     */
    private fun adjacent(left: MajorMinor, right: MajorMinor): Boolean {
      // It's unclear whether  !(x < N && x > N) should be limited to just the minor version .0 or
      // the entire range of N to N+1. For now simplify both to "x != N".
      return (left.bits + 1 == right.bits || left.bits + API_LEVEL_DELTA == right.bits && left.minor == 0 && right.minor == 0)
    }
  }
}
