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

import com.android.tools.render.common.ComponentSourceLocation
import com.android.tools.render.common.ScreenshotError
import com.google.gson.GsonBuilder
import java.io.Reader
import java.io.Serializable
import java.io.Writer

/**
 * Lightweight summary of a Compose animation discovered in a rendered preview's slot table (via `ui-tooling`'s `AnimationSearch` plus
 * scoped `Transition.AnimatedVisibility` / `Transition.AnimatedContent` containers), used both to badge static previews (`Animate (N)`)
 * without mutating live preview state and to pair subscribed clock animations with their user `.kt` [sourceLocation].
 */
data class DetectedAnimationSummary(
  val type: String,
  val label: String,
  val sourceLocation: ComponentSourceLocation? = null,
) : Serializable

/** Configuration for a multi-frame `PreviewAnimationClock` render session. */
data class AnimationRenderOptions(
  /**
   * Frame budget across `[0..effectiveDurationMillis]` (clamped to `2..60`). Frames are placed adaptively by [AnimationFrameSampler]:
   * always including both endpoints, denser where the animated values change fastest, and at least 16 ms apart (so short animations may get
   * fewer frames); evenly spaced when no numeric curve is available.
   */
  val frameCount: Int = 16,
  /** Optional explicit total duration override in milliseconds. */
  val durationMillisOverride: Long? = null,
  /** Optional initial state string (e.g. `"GONE"`, `"false"`, `"Enter"`, `"0.0"`). */
  val fromState: String? = null,
  /** Optional target state string (e.g. `"VISIBLE"`, `"true"`, `"Exit"`, `"1.0"`). */
  val toState: String? = null,
  /** Optional label of the specific animation whose `fromState`/`toState` is being targeted (`null` targets all applicable). */
  val animationLabel: String? = null,
) : Serializable

/** Serialized parameters of a Compose `AnimationSpec` (`SpringSpec`, `TweenSpec`, `KeyframesSpec`, etc.). */
data class AnimationSpecSummary(
  val specType: String,
  val dampingRatio: Float? = null,
  val stiffness: Float? = null,
  val durationMillis: Int? = null,
  val delayMillis: Int? = null,
  val easing: String? = null,
) : Serializable

/** A sampled value along an animated property's timeline curve. */
data class AnimatedPropertyPoint(
  val timeMillis: Long,
  val value: String,
  val numericValue: Double? = null,
  val components: List<Double>? = null,
) : Serializable

/** Curve and metadata for a single animated property belonging to a [SubscribedAnimationInfo]. */
data class AnimationTransitionTrack(
  val propertyLabel: String,
  val valueKind: String,
  val specType: String,
  val spec: AnimationSpecSummary? = null,
  val startTimeMillis: Long,
  val endTimeMillis: Long,
  val initialValue: String,
  val targetValue: String,
  val points: List<AnimatedPropertyPoint>,
  /**
   * A readable name for a built-in track of an `AnimatedVisibility` or `AnimatedContent` (e.g. `DetailLegend · expand` for its "Built-in
   * shrink/expand"), from the composable it shows or its call site, or `null` if the [propertyLabel] is the name.
   */
  val displayLabel: String? = null,
) : Serializable

/**
 * Metadata, state endpoints, and sampled property [tracks] for a single declarative Compose animation subscribed to and stepped by
 * `PreviewAnimationClock` (`updateTransition`, `AnimatedVisibility`, `AnimatedContent`, `animate*AsState`, or
 * `rememberInfiniteTransition`).
 */
data class SubscribedAnimationInfo(
  val id: String,
  val type: String,
  val label: String,
  val states: List<String>,
  val fromState: String? = null,
  val toState: String? = null,
  val durationMillis: Long,
  val sourceLocation: ComponentSourceLocation? = null,
  val spec: AnimationSpecSummary? = null,
  val tracks: List<AnimationTransitionTrack> = emptyList(),
) : Serializable

/** Metadata for a single rendered frame PNG produced during `PreviewAnimationClock` stepping. */
data class RenderedAnimationFrame(
  val index: Int,
  val timeMillis: Long,
  val imagePath: String,
  val properties: Map<String, String> = emptyMap(),
) : Serializable

/**
 * Complete JSON manifest (`<resultId>.animation.json`) emitted alongside the rendered frame PNGs of an animated `@Preview` session.
 *
 * This manifest gives humans, CLI callers (`android preview animate`), and UI tools a self-contained record of what moved, how it was
 * configured in code, and which PNG corresponds to each timestamp on the timeline. Specifically, it distinguishes three levels of animation
 * coverage within a composable:
 *
 * 1. **Clock-scrubbed animations ([animations]):** Declarative Compose animations that `ComposeViewAdapter` subscribed to
 *    `PreviewAnimationClock` and that were deterministically stepped across [frames] — `updateTransition` (including child transitions such
 *    as `transition.AnimatedVisibility` and `transition.AnimatedContent`), standalone `AnimatedVisibility`, standalone `AnimatedContent`,
 *    `animate*AsState` (`animateFloatAsState`, `animateDpAsState`, `animateColorAsState`, etc.), and `rememberInfiniteTransition` (or
 *    `Animatable` when supported as `TriggerComposeAnimation` in newer `ui-tooling`). Each entry reports its state space, active
 *    `fromState` -> `toState` segment, user `.kt` `sourceLocation`, and per-property [AnimationTransitionTrack] curves and
 *    [AnimationSpecSummary] parameters.
 * 2. **Detected & unsupported slot-table animations ([detectedAnimations] and [unsupportedAnimations]):** All Compose animation objects
 *    present in the composed slot table (discovered via `ui-tooling`'s `AnimationSearch` plus scoped `AnimatedVisibility` /
 *    `AnimatedContent` containers). In addition to the clock-scrubbed animations above, this includes layout or imperative Compose
 *    animations remembered in composition — such as `Modifier.animateContentSize()`, `TargetBasedAnimation`, `DecayAnimation`, and
 *    `Animatable` — which `PreviewAnimationClock` records in [unsupportedAnimations] because they require imperative coroutine or layout
 *    size-change triggers rather than pure state/clock scrubbing.
 * 3. **Animations NOT detected:** This manifest reflects what was instantiated in Compose's slot table during initial and state-toggled
 *    composition; it does **not** detect:
 *     - Animations inside conditional branches (`if (cond) { ... }`) that never executed in either the initial or toggled preview state
 *       (uncomposed branches emit no slot-table groups).
 *     - Imperative coroutine animations (`suspend fun animate(...)` inside an untriggered event lambda or `LaunchedEffect` without a
 *       remembered animation object), raw `withFrameNanos` / Canvas loops, or classic Android `View` / `Animator` animations outside
 *       Compose's slot table.
 */
data class PreviewAnimationManifest(
  val previewId: String,
  val methodFQN: String,
  val displayName: String? = null,
  val width: Int,
  val height: Int,
  val maxDurationMillis: Long,
  val maxDurationPerIterationMillis: Long,
  val effectiveDurationMillis: Long,
  val frameCount: Int,
  val animations: List<SubscribedAnimationInfo>,
  val detectedAnimations: List<DetectedAnimationSummary> = emptyList(),
  val unsupportedAnimations: List<String> = emptyList(),
  val frames: List<RenderedAnimationFrame>,
) : Serializable

/** Outcome of rendering an animated preview across multiple clock frames. */
data class PreviewAnimationResult(
  val previewId: String,
  val methodFQN: String,
  val manifestPath: String,
  val framePaths: List<String>,
  val manifest: PreviewAnimationManifest? = null,
  val error: ScreenshotError? = null,
  val displayName: String? = null,
) : Serializable

/** Batch outcome returned by `StandalonePreviewRenderer.renderAnimations`. */
data class PreviewAnimationRenderingResult(
  val globalError: String?,
  val animationResults: List<PreviewAnimationResult>,
) : Serializable {
  /**
   * Class name of a fatal [VirtualMachineError] (other than [StackOverflowError], such as `"OutOfMemoryError"`) caught during animation
   * rendering, or `null` when no fatal JVM error occurred.
   */
  var fatalJvmError: String? = null
    private set

  /**
   * `true` when animation rendering failed due to a fatal [VirtualMachineError] (other than [StackOverflowError]), indicating that the
   * hosting JVM should retire instead of serving further requests.
   */
  val isFatalJvmError: Boolean
    get() = fatalJvmError != null

  /**
   * Non-null reason when this request found Layoutlib's single render thread still blocked by the timed-out work of a previous request
   * after the recovery probe window, or `null` when the render thread was available or recovered in place.
   */
  var renderThreadPoisonedReason: String? = null
    private set

  /**
   * `true` when Layoutlib's single render thread remains poisoned after rendering this batch, indicating that a long-running daemon hosting
   * this JVM should retire after delivering the response.
   */
  val isRenderThreadPoisoned: Boolean
    get() = renderThreadPoisonedReason != null

  constructor(
    globalError: String?,
    animationResults: List<PreviewAnimationResult>,
    fatalJvmError: String?,
  ) : this(globalError, animationResults) {
    this.fatalJvmError = fatalJvmError
  }

  constructor(
    globalError: String?,
    animationResults: List<PreviewAnimationResult>,
    fatalJvmError: String?,
    renderThreadPoisonedReason: String?,
  ) : this(globalError, animationResults) {
    this.fatalJvmError = fatalJvmError
    this.renderThreadPoisonedReason = renderThreadPoisonedReason
  }
}

private val animationGson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

/** Serializes [PreviewAnimationManifest] to JSON. */
fun writeAnimationManifestToJson(writer: Writer, manifest: PreviewAnimationManifest) {
  animationGson.toJson(manifest, writer)
}

/** Deserializes [PreviewAnimationManifest] from JSON, normalizing optional list defaults when reading older manifests. */
@Suppress("SENSELESS_COMPARISON")
fun readAnimationManifestFromJson(reader: Reader): PreviewAnimationManifest {
  val parsed =
    animationGson.fromJson(reader, PreviewAnimationManifest::class.java)
      ?: throw IllegalArgumentException("Animation manifest JSON is empty or null")
  return parsed.copy(
    animations = if (parsed.animations != null) parsed.animations else emptyList(),
    detectedAnimations = if (parsed.detectedAnimations != null) parsed.detectedAnimations else emptyList(),
    unsupportedAnimations = if (parsed.unsupportedAnimations != null) parsed.unsupportedAnimations else emptyList(),
    frames = if (parsed.frames != null) parsed.frames else emptyList(),
  )
}
