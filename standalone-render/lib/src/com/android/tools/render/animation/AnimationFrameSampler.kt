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

package com.android.tools.render.animation

import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Chooses the clock timestamps at which an animated preview is captured.
 *
 * Instead of spacing frames evenly across `[0..durationMs]`, frames are distributed by inverting the cumulative distribution of a density
 * made of:
 * * an *activity* term: the sum over all numeric tracks of the normalized speed `|dv/dt| / span` (Euclidean norm for multi-component values
 *   such as `Offset` or `Color`), where `span` is the larger of `|target - initial|` and the maximum excursion from the initial value, so
 *   every track contributes equally regardless of its unit; and
 * * a uniform *floor* ([UNIFORM_FLOOR] of the total mass) so quiet stretches still get frames.
 *
 * Fast motion (e.g. the rise into a spring overshoot) therefore gets denser frames. The first and last timestamps are always `0` and
 * `durationMs`, consecutive timestamps are at least [MIN_GAP_MS] apart (≈60 fps cap, so short animations get fewer frames than the budget),
 * and the result never exceeds the frame budget. When no numeric curve is available, frames fall back to even spacing. The output is
 * deterministic for a given input.
 */
object AnimationFrameSampler {
  /** Fraction of the probability mass spread uniformly across the timeline. */
  const val UNIFORM_FLOOR = 0.3

  /** Minimum distance between consecutive timestamps (≈ one frame at 60 fps). */
  const val MIN_GAP_MS = 16L

  /** Upper bound on the number of histogram bins used to integrate the density. */
  private const val MAX_BINS = 4000

  /** Tracks whose normalized span is below this are treated as static. */
  private const val EPSILON = 1e-9

  /**
   * Returns sorted, distinct timestamps for the numeric curves of all tracks in [animations].
   *
   * @see sampleTimestamps
   */
  fun sampleTimestampsForAnimations(durationMs: Long, frameBudget: Int, animations: List<SubscribedAnimationInfo>): List<Long> =
    sampleTimestamps(durationMs, frameBudget, animations.flatMap { anim -> anim.tracks.map { it.points } })

  /**
   * Returns sorted, distinct timestamps in `[0..durationMs]`.
   *
   * @param durationMs effective animation duration; `<= 0` yields `[0]`.
   * @param frameBudget maximum number of frames (clamped to `2..60`).
   * @param tracks sampled points of each animated property; non-numeric points are ignored.
   */
  fun sampleTimestamps(durationMs: Long, frameBudget: Int, tracks: List<List<AnimatedPropertyPoint>>): List<Long> {
    if (durationMs <= 0L) return listOf(0L)
    // Cap the effective frame count `n` so `(n - 1) * MIN_GAP_MS <= durationMs` holds (for `durationMs >= MIN_GAP_MS`),
    // ensuring `enforceMinGap` always has a non-empty `[lower, upper]` interval for every interior frame.
    val maxByGap = (durationMs / MIN_GAP_MS).toInt().coerceAtLeast(1) + 1
    val n = frameBudget.coerceIn(2, 60).coerceAtMost(maxByGap)
    if (n <= 2) return listOf(0L, durationMs)

    // Discretize `[0..durationMs]` into up to MAX_BINS equal-width histogram bins (~1 ms per bin for durations <= 4 s).
    val binCount = durationMs.coerceAtMost(MAX_BINS.toLong()).toInt()
    val binWidth = durationMs.toDouble() / binCount
    val activity = DoubleArray(binCount)
    for (track in tracks) {
      accumulateTrackActivity(track, durationMs, binWidth, activity)
    }
    val totalActivity = activity.sum()
    val raw =
      if (totalActivity <= EPSILON) {
        uniformInterior(durationMs, n)
      } else {
        invertCdf(activity, totalActivity, binWidth, n)
      }
    return enforceMinGap(raw, durationMs, n)
  }

  /** Evenly spaced timestamps (the pre-adaptive behavior), including both endpoints. */
  fun uniformTimestamps(durationMs: Long, frameCount: Int): List<Long> {
    if (durationMs <= 0L || frameCount <= 1) return listOf(0L)
    return (0 until frameCount).map { idx -> (durationMs.toDouble() * idx / (frameCount - 1)).roundToLong() }.distinct()
  }

  /**
   * Projects a single property track's piecewise-linear motion onto the histogram [activity] array.
   *
   * Each consecutive pair of finite numeric points `(t0, v0) -> (t1, v1)` contributes a dimensionless mass `||v1 - v0|| / span`, where
   * `span = max(||target - initial||, max_i ||v_i - initial||)`. Dividing by `span` makes the curve scale-invariant across units (`0..1`
   * alpha vs `0..400` px offset vs 4D RGBA `Color`), and including `maxExcursion` ensures round-trip curves whose final value equals their
   * start value (e.g. a pulse or keyframes `0 -> 120 -> 0`) still normalize against peak excursion rather than dividing by zero.
   *
   * @param points sampled curve points for a single property track (may be unsorted or contain non-numeric/non-finite entries).
   * @param durationMs total timeline duration in milliseconds (`> 0`); timestamps outside `[0..durationMs]` are clamped.
   * @param binWidth width of each histogram bin in milliseconds (`durationMs.toDouble() / activity.size`).
   * @param activity mutable histogram accumulator of length `binCount` updated in place.
   */
  private fun accumulateTrackActivity(points: List<AnimatedPropertyPoint>, durationMs: Long, binWidth: Double, activity: DoubleArray) {
    // 1. Extract finite numeric vectors (preferring multi-component `components` such as Offset/Color over scalar `numericValue`),
    //    clamp timestamps into `[0..durationMs]`, and sort chronologically so segments run forward in time.
    val numeric =
      points
        .mapNotNull { p ->
          val vector = p.components?.takeIf { it.isNotEmpty() } ?: p.numericValue?.let { listOf(it) }
          vector?.takeIf { v -> v.all { it.isFinite() } }?.let { p.timeMillis.coerceIn(0L, durationMs) to it }
        }
        .sortedBy { it.first }
    // Need at least two valid points of uniform dimensionality to measure segment motion.
    if (numeric.size < 2) return
    val dims = numeric.first().second.size
    if (numeric.any { it.second.size != dims }) return

    // 2. Compute normalization span from both net displacement `|target - initial|` and peak excursion `max |v_i - initial|`.
    //    Using `maxExcursion` handles animations that return to their starting value (where `target == initial`) as well as
    //    underdamped springs whose peak overshoot exceeds `|target - initial|`. Ignore effectively static tracks (`span <= EPSILON`).
    val initial = numeric.first().second
    val target = numeric.last().second
    val maxExcursion = numeric.maxOf { distance(it.second, initial) }
    val span = maxOf(distance(target, initial), maxExcursion)
    if (span <= EPSILON) return

    // 3. Walk each consecutive segment `[t0, t1]` and distribute its normalized distance travelled `mass = ||v1 - v0|| / span`
    //    into the histogram bins overlapped by `[t0, t1]`.
    for (i in 0 until numeric.size - 1) {
      val (t0, v0) = numeric[i]
      val (t1, v1) = numeric[i + 1]
      val mass = distance(v1, v0) / span
      if (mass <= 0.0) continue
      if (t1 <= t0) {
        // Degenerate zero-duration step (e.g. duplicate timestamps after clamping or SnapSpec): attribute all mass to the bin at `t0`
        // instead of dividing by `end - start == 0` in `spreadUniformly`.
        activity[binIndex(t0.toDouble(), binWidth, activity.size)] += mass
        continue
      }
      spreadUniformly(t0.toDouble(), t1.toDouble(), mass, binWidth, activity)
    }
  }

  /**
   * Distributes [mass] at constant rate `density = mass / (end - start)` across every histogram bin in [activity] that overlaps the open
   * time interval `[start, end]`, weighting each bin by its exact temporal overlap `min(end, binEnd) - max(start, binStart)`.
   *
   * Precondition: `end > start` and `binWidth > 0.0`. Conservation invariant: when `0 <= start < end <= activity.size * binWidth`, the sum
   * of increments added across `bin..lastBin` equals [mass] (up to floating-point rounding).
   */
  private fun spreadUniformly(start: Double, end: Double, mass: Double, binWidth: Double, activity: DoubleArray) {
    val density = mass / (end - start)
    var bin = binIndex(start, binWidth, activity.size)
    val lastBin = binIndex(end, binWidth, activity.size)
    while (bin <= lastBin) {
      val binStart = bin * binWidth
      val binEnd = binStart + binWidth
      val overlap = minOf(end, binEnd) - maxOf(start, binStart)
      if (overlap > 0.0) activity[bin] += density * overlap
      bin++
    }
  }

  /**
   * Maps a continuous timestamp [time] (in milliseconds) to its 0-based histogram bin index in `0 until binCount`, clamping the right
   * endpoint `time == durationMs` (`time / binWidth == binCount`) into the final bin `binCount - 1`.
   */
  private fun binIndex(time: Double, binWidth: Double, binCount: Int): Int = (time / binWidth).toInt().coerceIn(0, binCount - 1)

  /**
   * Computes the Euclidean (`L2`) distance `sqrt(sum_i (a[i] - b[i])^2)` between two equal-length component vectors [a] and [b]. For 1D
   * scalar tracks (`size == 1`), this reduces to `|a[0] - b[0]|`.
   */
  private fun distance(a: List<Double>, b: List<Double>): Double {
    var sum = 0.0
    for (i in a.indices) {
      val d = a[i] - b[i]
      sum += d * d
    }
    return sqrt(sum)
  }

  /**
   * Computes [n] continuous timestamps in `[0.0, binCount * binWidth]` by inverting the piecewise-linear cumulative distribution function
   * (CDF) of a mixture density combining [UNIFORM_FLOOR] uniform weight and `1 - UNIFORM_FLOOR` normalized [activity].
   *
   * Endpoints are pinned to `0.0` (`k = 0`) and `binCount * binWidth == durationMs` (`k = n - 1`), while interior frames (`k in 1..n-2`)
   * are placed at exact quantiles `q = k / (n - 1)` in `O(binCount + n)` time via a single monotonic scan.
   *
   * Precondition: `totalActivity > 0.0`, `activity.isNotEmpty()`, and `n >= 3`.
   */
  private fun invertCdf(activity: DoubleArray, totalActivity: Double, binWidth: Double, n: Int): List<Double> {
    val binCount = activity.size
    val uniformPerBin = UNIFORM_FLOOR / binCount
    // Build normalized per-bin probability masses summing to 1.0:
    // `UNIFORM_FLOOR` keeps weight strictly positive even in zero-motion stretches so the CDF is strictly increasing everywhere,
    // while `(1 - UNIFORM_FLOOR) * activity[i] / totalActivity` concentrates frames where tracks change fastest.
    val weights = DoubleArray(binCount) { uniformPerBin + (1.0 - UNIFORM_FLOOR) * activity[it] / totalActivity }
    val result = ArrayList<Double>(n)
    // Pin first frame at t = 0.
    result.add(0.0)
    // Because `q = k / (n - 1)` increases strictly with `k` and `weights` are non-negative, advance `bin` monotonically without resetting:
    // `cumulativeBefore` tracks the exact CDF mass of all bins `< bin`.
    var bin = 0
    var cumulativeBefore = 0.0
    for (k in 1 until n - 1) {
      val q = k.toDouble() / (n - 1)
      // Advance until `bin` is the bin whose cumulative interval `[cumulativeBefore, cumulativeBefore + weights[bin]]` straddles `q`
      // (clamped to `binCount - 1` against floating-point drift near `q -> 1.0`).
      while (bin < binCount - 1 && cumulativeBefore + weights[bin] < q) {
        cumulativeBefore += weights[bin]
        bin++
      }
      // Linearly interpolate within `bin` (guarding `weights[bin] > 0.0` in case `UNIFORM_FLOOR` were ever configured to 0).
      val fraction = if (weights[bin] > 0.0) ((q - cumulativeBefore) / weights[bin]).coerceIn(0.0, 1.0) else 0.0
      result.add((bin + fraction) * binWidth)
    }
    // Pin final frame at `binCount * binWidth == durationMs`.
    result.add(binCount * binWidth)
    return result
  }

  /**
   * Returns [n] evenly spaced continuous timestamps across `[0.0, durationMs.toDouble()]` (`n >= 2`), used as the fallback before
   * [enforceMinGap] when no track has non-zero numeric activity (`totalActivity <= EPSILON`).
   */
  private fun uniformInterior(durationMs: Long, n: Int): List<Double> = (0 until n).map { durationMs.toDouble() * it / (n - 1) }

  /**
   * Rounds continuous quantile timestamps [raw] to integer milliseconds and enforces a minimum spacing of [MIN_GAP_MS] between consecutive
   * frames while keeping `0` and [durationMs] pinned as endpoints.
   *
   * For each interior index `i` (`1 until n - 1`), `lower = max(gap * i, result[i - 1] + gap)` reserves room for all preceding frames and
   * `upper = durationMs - gap * (n - 1 - i)` reserves `(n - 1 - i) * gap` ms of room for all remaining frames up to [durationMs]. When
   * `(n - 1) * MIN_GAP_MS <= durationMs` (guaranteed by [sampleTimestamps] whenever `durationMs >= MIN_GAP_MS`), `lower <= upper` holds at
   * every step; for ultra-short animations (`0 < durationMs < MIN_GAP_MS`, where `n == 2`), the interior loop is skipped and `[0L,
   * durationMs].distinct()` is returned.
   */
  private fun enforceMinGap(raw: List<Double>, durationMs: Long, n: Int): List<Long> {
    val gap = MIN_GAP_MS
    val result = LongArray(n)
    result[0] = 0L
    result[n - 1] = durationMs
    for (i in 1 until n - 1) {
      val lower = maxOf(gap * i, result[i - 1] + gap)
      val upper = durationMs - gap * (n - 1 - i)
      result[i] = raw[i].roundToLong().coerceIn(lower, upper)
    }
    return result.toList().distinct()
  }
}
