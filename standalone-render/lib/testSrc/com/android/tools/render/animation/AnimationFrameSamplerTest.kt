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

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

class AnimationFrameSamplerTest {
  private companion object {
    // SleepBar height: spring(dampingRatio = 0.38f, stiffness = 380f) from 24.dp to 110.dp.
    const val SPRING_DURATION_MS = 1233L
    const val INITIAL = 24.0
    const val TARGET = 110.0
    const val DAMPING = 0.38
    const val STIFFNESS = 380.0
    val OMEGA = sqrt(STIFFNESS)
    val DAMPED_OMEGA = OMEGA * sqrt(1 - DAMPING * DAMPING)
    /** Time of the first (largest) overshoot peak of the underdamped spring. */
    val PEAK_MS = PI / DAMPED_OMEGA * 1000.0

    /** Closed-form underdamped spring position (zero initial velocity) at [timeMs]. */
    fun springValue(timeMs: Double): Double {
      val s = timeMs / 1000.0
      val decay = exp(-DAMPING * OMEGA * s)
      return TARGET - (TARGET - INITIAL) * decay * (cos(DAMPED_OMEGA * s) + DAMPING * OMEGA / DAMPED_OMEGA * sin(DAMPED_OMEGA * s))
    }

    /** Curve points as produced by `extractSubscribedAnimations` (step = duration / 30, min 8 ms). */
    fun springPoints(): List<AnimatedPropertyPoint> {
      val step = maxOf(8L, SPRING_DURATION_MS / 30L)
      val times = (0L until SPRING_DURATION_MS step step).toList() + SPRING_DURATION_MS
      return times.map { t ->
        val v = springValue(t.toDouble())
        AnimatedPropertyPoint(timeMillis = t, value = "%.1f.dp".format(v), numericValue = v)
      }
    }

    fun List<Long>.assertValidTimeline(durationMs: Long, budget: Int) {
      assertThat(this).isEqualTo(sorted())
      assertThat(this).containsNoDuplicates()
      assertThat(first()).isEqualTo(0L)
      assertThat(last()).isEqualTo(durationMs)
      assertThat(size).isAtMost(budget)
      zipWithNext().forEach { (a, b) -> assertThat(b - a).isAtLeast(AnimationFrameSampler.MIN_GAP_MS) }
    }
  }

  @Test
  fun springOvershoot_getsMoreFramesNearPeakThanUniform() {
    val budget = 24
    val adaptive = AnimationFrameSampler.sampleTimestamps(SPRING_DURATION_MS, budget, listOf(springPoints()))
    val uniform = AnimationFrameSampler.uniformTimestamps(SPRING_DURATION_MS, budget)

    adaptive.assertValidTimeline(SPRING_DURATION_MS, budget)
    assertThat(adaptive).hasSize(budget)

    fun framesNearPeak(ts: List<Long>) = ts.count { abs(it - PEAK_MS) <= 80.0 }
    assertThat(framesNearPeak(adaptive)).isGreaterThan(framesNearPeak(uniform))

    // The captured maximum is closer to the true overshoot peak than with even spacing.
    val truePeak = springValue(PEAK_MS)
    val adaptiveError = truePeak - adaptive.maxOf { springValue(it.toDouble()) }
    val uniformError = truePeak - uniform.maxOf { springValue(it.toDouble()) }
    assertThat(adaptiveError).isLessThan(uniformError)
    assertThat(adaptiveError).isLessThan(0.5)
  }

  @Test
  fun keyframesPeakAtHalfDuration_getsAFrameAtThePeak() {
    // keyframes { durationMillis = 400; 120.dp at 200 } from 24.dp to 48.dp: linear up to the peak at 50%, then linear down.
    fun keyframesValue(timeMs: Double): Double =
      if (timeMs <= 200.0) 24.0 + 96.0 * timeMs / 200.0 else 120.0 - 72.0 * (timeMs - 200.0) / 200.0
    val step = 400L / 30L
    val points =
      ((0L until 400L step step).toList() + 400L).map { t ->
        val v = keyframesValue(t.toDouble())
        AnimatedPropertyPoint(timeMillis = t, value = "%.1f.dp".format(v), numericValue = v)
      }
    val budget = 24

    val adaptive = AnimationFrameSampler.sampleTimestamps(400L, budget, listOf(points))

    adaptive.assertValidTimeline(400L, budget)
    // The curve points capture the peak, and so does the closest frame (at most 16 ms away, i.e. within 4% of the peak value).
    assertThat(points.maxBy { checkNotNull(it.numericValue) }.timeMillis.toDouble()).isWithin(step.toDouble()).of(200.0)
    assertThat(adaptive.minOf { abs(it - 200L) }).isAtMost(AnimationFrameSampler.MIN_GAP_MS)
    assertThat(adaptive.maxOf { keyframesValue(it.toDouble()) }).isAtLeast(120.0 - 96.0 * AnimationFrameSampler.MIN_GAP_MS / 200.0)
  }

  @Test
  fun noNumericPoints_fallsBackToUniform() {
    val enumTrack =
      listOf(
        AnimatedPropertyPoint(timeMillis = 0L, value = "GONE"),
        AnimatedPropertyPoint(timeMillis = 300L, value = "VISIBLE"),
      )
    assertThat(AnimationFrameSampler.sampleTimestamps(600L, 7, listOf(enumTrack)))
      .containsExactly(0L, 100L, 200L, 300L, 400L, 500L, 600L)
      .inOrder()
    assertThat(AnimationFrameSampler.sampleTimestamps(600L, 7, emptyList()))
      .containsExactly(0L, 100L, 200L, 300L, 400L, 500L, 600L)
      .inOrder()
  }

  @Test
  fun constantSpeedTrack_staysCloseToUniform() {
    // Linear tween of an Offset-like 2-component value: constant speed, so density is uniform.
    val points = (0L..400L step 20L).map { t -> AnimatedPropertyPoint(timeMillis = t, value = "", components = listOf(t * 0.3, t * 0.4)) }
    val adaptive = AnimationFrameSampler.sampleTimestamps(400L, 9, listOf(points))
    val uniform = AnimationFrameSampler.uniformTimestamps(400L, 9)
    assertThat(adaptive).hasSize(uniform.size)
    adaptive.zip(uniform).forEach { (a, u) -> assertThat(abs(a - u)).isAtMost(1L) }
  }

  @Test
  fun shortAnimation_respectsMinGapAndGetsFewerFrames() {
    val points = (0L..100L step 8L).map { t -> AnimatedPropertyPoint(timeMillis = t, value = "", numericValue = (t * t).toDouble()) }
    val timestamps = AnimationFrameSampler.sampleTimestamps(100L, 60, listOf(points))
    timestamps.assertValidTimeline(100L, 60)
    assertThat(timestamps.size).isAtMost(100 / 16 + 1)
  }

  @Test
  fun budgetAndEndpoints_alwaysRespected() {
    for (budget in listOf(2, 3, 16, 60, 120)) {
      val timestamps = AnimationFrameSampler.sampleTimestamps(SPRING_DURATION_MS, budget, listOf(springPoints()))
      timestamps.assertValidTimeline(SPRING_DURATION_MS, budget.coerceIn(2, 60))
    }
    assertThat(AnimationFrameSampler.sampleTimestamps(0L, 16, listOf(springPoints()))).containsExactly(0L)
    assertThat(AnimationFrameSampler.sampleTimestamps(10L, 16, emptyList())).containsExactly(0L, 10L).inOrder()
  }

  @Test
  fun sampling_isDeterministic() {
    val first = AnimationFrameSampler.sampleTimestamps(SPRING_DURATION_MS, 24, listOf(springPoints()))
    val second = AnimationFrameSampler.sampleTimestamps(SPRING_DURATION_MS, 24, listOf(springPoints()))
    assertThat(second).isEqualTo(first)
  }
}
