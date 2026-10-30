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

import androidx.compose.animation.tooling.ComposeAnimatedProperty
import androidx.compose.animation.tooling.ComposeAnimation
import androidx.compose.animation.tooling.ComposeAnimationType
import androidx.compose.animation.tooling.TransitionInfo
import com.android.tools.render.ProbeDecision
import com.android.tools.render.RenderThreadTimeoutState
import com.android.tools.render.StaticPreviewCallbackRunner
import com.android.tools.render.common.ComponentBounds
import com.android.tools.render.common.ComponentSourceLocation
import com.android.tools.render.common.ComponentTreeNode
import com.android.tools.render.common.PreviewComponentTree
import com.android.tools.render.common.readComponentTreeFromJson
import com.android.tools.render.common.writeComponentTreeToJson
import com.android.tools.render.extractTimeoutBudgetMs
import com.android.tools.render.findTimeoutException
import com.android.tools.rendering.RenderLogger
import com.google.common.truth.Truth.assertThat
import java.io.StringReader
import java.io.StringWriter
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

class AnimationPreviewRunnerTest {
  private enum class SampleState {
    GONE,
    VISIBLE,
  }

  private class SpringSpec(
    val dampingRatio: Float,
    val stiffness: Float,
  )

  private class CubicBezierEasing(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
  )

  private class TweenSpec(
    val durationMillis: Int,
    val delay: Int,
    val easing: Any,
  )

  private class InfiniteRepeatableSpec(val animation: Any)

  /** Mirrors Compose `androidx.compose.animation.core.Easing`. */
  private fun interface Easing {
    fun transform(fraction: Float): Float
  }

  private class FakeAdapter(private val animated: Boolean) {
    fun hasAnimations(): Boolean = animated
  }

  @Test
  fun injectAnimationClockStartTime_addsNamespaceAndAttribute() {
    val rawXml =
      """
      <androidx.compose.ui.tooling.ComposeViewAdapter
          xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content" />
      """
        .trimIndent()

    val injected = AnimationPreviewRunner.injectAnimationClockStartTime(rawXml, 0L)

    assertThat(injected).contains("""xmlns:tools="http://schemas.android.com/tools"""")
    assertThat(injected).contains("""tools:animationClockStartTime="0"""")
  }

  @Test
  fun injectAnimationClockStartTime_replacesExistingAttributeWithoutDuplicatingNamespace() {
    val existingXml =
      """
      <androidx.compose.ui.tooling.ComposeViewAdapter
          xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools"
          tools:animationClockStartTime="250"
          android:layout_width="wrap_content" />
      """
        .trimIndent()

    val updated = AnimationPreviewRunner.injectAnimationClockStartTime(existingXml, 0L)

    assertThat(updated).contains("""tools:animationClockStartTime="0"""")
    assertThat(updated).doesNotContain("""tools:animationClockStartTime="250"""")
    assertThat(Regex("xmlns:tools=").findAll(updated).count()).isEqualTo(1)
  }

  @Test
  fun injectAnimationClockStartTime_leavesNonComposeXmlUnchanged() {
    val frameXml = """<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android" />"""
    assertThat(AnimationPreviewRunner.injectAnimationClockStartTime(frameXml, 0L)).isEqualTo(frameXml)
  }

  @Test
  fun formatAnimatedValue_handlesPrimitivesAndEnums() {
    val floatFormatted = AnimationPreviewRunner.formatAnimatedValue(0.625f)
    assertThat(floatFormatted.first).isEqualTo("0.625")
    assertThat(floatFormatted.second).isEqualTo("Float")
    assertThat(floatFormatted.third).containsExactly(0.625)

    val boolFormatted = AnimationPreviewRunner.formatAnimatedValue(true)
    assertThat(boolFormatted.first).isEqualTo("true")
    assertThat(boolFormatted.second).isEqualTo("Boolean")
    assertThat(boolFormatted.third).containsExactly(1.0)

    val enumFormatted = AnimationPreviewRunner.formatAnimatedValue(SampleState.VISIBLE)
    assertThat(enumFormatted.first).isEqualTo("VISIBLE")
    assertThat(enumFormatted.second).isEqualTo("Enum")
    assertThat(enumFormatted.third).containsExactly(1.0)
  }

  @Test
  fun summarizeAnimationSpec_extractsSpringTweenAndInfiniteSpecs() {
    val springSummary = AnimationPreviewRunner.summarizeAnimationSpec(SpringSpec(0.6f, 800f))
    assertThat(springSummary.specType).isEqualTo("SpringSpec")
    assertThat(springSummary.dampingRatio).isWithin(0.001f).of(0.6f)
    assertThat(springSummary.stiffness).isWithin(0.01f).of(800f)

    val fastOutSlowInTween =
      TweenSpec(
        durationMillis = 320,
        delay = 40,
        easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f),
      )
    val tweenSummary = AnimationPreviewRunner.summarizeAnimationSpec(fastOutSlowInTween)
    assertThat(tweenSummary.specType).isEqualTo("TweenSpec")
    assertThat(tweenSummary.durationMillis).isEqualTo(320)
    assertThat(tweenSummary.delayMillis).isEqualTo(40)
    assertThat(tweenSummary.easing).isEqualTo("FastOutSlowIn")

    val customBezierTween =
      TweenSpec(
        durationMillis = 250,
        delay = 0,
        easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
      )
    val customSummary = AnimationPreviewRunner.summarizeAnimationSpec(customBezierTween)
    assertThat(customSummary.easing).isEqualTo("cubic-bezier(0.20, 0.00, 0.00, 1.00)")

    val infiniteSummary = AnimationPreviewRunner.summarizeAnimationSpec(InfiniteRepeatableSpec(fastOutSlowInTween))
    assertThat(infiniteSummary.specType).isEqualTo("InfiniteRepeatableSpec")
    assertThat(infiniteSummary.durationMillis).isEqualTo(320)
    assertThat(infiniteSummary.easing).isEqualTo("FastOutSlowIn")
  }

  @Test
  fun describeEasing_probesLambdaEasings() {
    // Equivalent to Compose `val LinearEasing: Easing = Easing { fraction -> fraction }`.
    val linearLambda = Easing { it }
    assertThat(AnimationPreviewRunner.describeEasing(linearLambda)).isEqualTo("Linear")
    assertThat(AnimationPreviewRunner.summarizeAnimationSpec(TweenSpec(300, 0, linearLambda)).easing).isEqualTo("Linear")

    // A non-linear ease-out lambda must not leak its synthetic class name.
    val easeOutLambda = Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) }
    assertThat(AnimationPreviewRunner.describeEasing(easeOutLambda)).isEqualTo("Custom")

    val anonymousLinear =
      object : Easing {
        override fun transform(fraction: Float): Float = fraction
      }
    assertThat(AnimationPreviewRunner.describeEasing(anonymousLinear)).isEqualTo("Linear")

    // Cubic bezier presets keep their names.
    assertThat(AnimationPreviewRunner.describeEasing(CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f))).isEqualTo("FastOutSlowIn")
  }

  @Test
  fun builtInDisplayLabel_namesAnimatedVisibilityTracksAfterTheirContent() {
    val legend = AnimationPreviewRunner.AnimatedContainerInfo("DetailLegend", ComponentSourceLocation("SleepBar.kt", 112))
    assertThat(AnimationPreviewRunner.builtInDisplayLabel("Built-in shrink/expand", legend, "Visible")).isEqualTo("DetailLegend · expand")
    assertThat(AnimationPreviewRunner.builtInDisplayLabel("Built-in alpha", legend, "Visible")).isEqualTo("DetailLegend · fade in")
    assertThat(AnimationPreviewRunner.builtInDisplayLabel("Built-in alpha", legend, "PostExit")).isEqualTo("DetailLegend · fade out")
    assertThat(AnimationPreviewRunner.builtInDisplayLabel("Built-in scale", legend, "PreEnter")).isEqualTo("DetailLegend · scale")
    // Without a composable inside, the call site names it.
    val anonymous = AnimationPreviewRunner.AnimatedContainerInfo(null, ComponentSourceLocation("SleepBar.kt", 112))
    assertThat(AnimationPreviewRunner.builtInDisplayLabel("Built-in alpha", anonymous, null)).isEqualTo("SleepBar.kt:112 · fade")
    // Tracks the code labels, internal tracks and unnamed containers keep their label.
    assertThat(AnimationPreviewRunner.builtInDisplayLabel("height", legend, "Visible")).isNull()
    assertThat(AnimationPreviewRunner.builtInDisplayLabel("Built-in InterruptionHandlingOffset", legend, "Visible")).isNull()
    assertThat(AnimationPreviewRunner.builtInDisplayLabel("Built-in alpha", AnimationPreviewRunner.AnimatedContainerInfo(null, null), null))
      .isNull()
  }

  @Test
  fun detectAnimationsFromAdapter_readsHasAnimationsFlag() {
    val (animated, _) = AnimationPreviewRunner.detectAnimationsFromAdapter(FakeAdapter(true))
    assertThat(animated).isTrue()

    val (notAnimated, summaries) = AnimationPreviewRunner.detectAnimationsFromAdapter(FakeAdapter(false))
    assertThat(notAnimated).isFalse()
    assertThat(summaries).isEmpty()
  }

  @Test
  fun animationManifestJsonRoundTrip_preservesAnimationsTracksAndFrames() {
    val manifest =
      PreviewAnimationManifest(
        previewId = "com.example.JumpToBottomPreview",
        methodFQN = "com.example.JumpToBottomKt.JumpToBottomPreview",
        displayName = "JumpToBottom",
        width = 420,
        height = 180,
        maxDurationMillis = 480L,
        maxDurationPerIterationMillis = 480L,
        effectiveDurationMillis = 480L,
        frameCount = 2,
        animations =
          listOf(
            SubscribedAnimationInfo(
              id = "anim_0",
              type = "TRANSITION_ANIMATION",
              label = "jumpToBottom",
              states = listOf("GONE", "VISIBLE"),
              fromState = "GONE",
              toState = "VISIBLE",
              durationMillis = 480L,
              sourceLocation = ComponentSourceLocation("JumpToBottom.kt", 64),
              spec = AnimationSpecSummary(specType = "SpringSpec", dampingRatio = 0.75f, stiffness = 400f),
              tracks =
                listOf(
                  AnimationTransitionTrack(
                    propertyLabel = "bottomOffset",
                    valueKind = "Dp",
                    specType = "SpringSpec",
                    spec = AnimationSpecSummary(specType = "SpringSpec", dampingRatio = 0.75f, stiffness = 400f),
                    startTimeMillis = 0L,
                    endTimeMillis = 480L,
                    initialValue = "-32.00.dp",
                    targetValue = "32.00.dp",
                    points =
                      listOf(
                        AnimatedPropertyPoint(0L, "-32.00.dp", -32.0, listOf(-32.0)),
                        AnimatedPropertyPoint(480L, "32.00.dp", 32.0, listOf(32.0)),
                      ),
                  )
                ),
            )
          ),
        frames =
          listOf(
            RenderedAnimationFrame(0, 0L, "com/example/JumpToBottomPreview_0_frame_00.png", mapOf("bottomOffset" to "-32.00.dp")),
            RenderedAnimationFrame(1, 480L, "com/example/JumpToBottomPreview_0_frame_01.png", mapOf("bottomOffset" to "32.00.dp")),
          ),
      )

    val writer = StringWriter()
    writeAnimationManifestToJson(writer, manifest)
    val parsed = readAnimationManifestFromJson(StringReader(writer.toString()))

    assertThat(parsed).isEqualTo(manifest)
  }

  @Test
  fun componentTreeSerialization_omitsAnimationFieldsWhenNullAndPreservesWhenPresent() {
    val staticTree =
      PreviewComponentTree(
        previewId = "p1",
        methodFQN = "com.example.FooKt.Bar",
        width = 200,
        height = 100,
        components = listOf(ComponentTreeNode("Button", ComponentBounds(0, 0, 200, 100))),
      )
    val staticJson = StringWriter().also { writeComponentTreeToJson(it, staticTree) }.toString()
    assertThat(staticJson).doesNotContain("hasAnimations")
    assertThat(staticJson).doesNotContain("detectedAnimations")

    val animatedTree =
      staticTree.copy(
        hasAnimations = true,
        animationCount = 1,
        detectedAnimations =
          listOf(
            DetectedAnimationSummary(
              type = "TRANSITION",
              label = "jumpTransition",
              sourceLocation = ComponentSourceLocation("JumpToBottom.kt", 64),
            )
          ),
      )
    val animatedJson = StringWriter().also { writeComponentTreeToJson(it, animatedTree) }.toString()
    assertThat(animatedJson).contains("\"hasAnimations\": true")
    val roundTripped = readComponentTreeFromJson(StringReader(animatedJson))
    assertThat(roundTripped).isEqualTo(animatedTree)
  }

  @Test
  fun shouldRunSizeDependentMeasurePass_runsOnlyOnSizeDependentAnimations() {
    val transitionOnly = listOf(DetectedAnimationSummary(type = "TRANSITION", label = "color"))
    assertThat(AnimationPreviewRunner.shouldRunSizeDependentMeasurePass(transitionOnly)).isFalse()

    val expand = listOf(DetectedAnimationSummary(type = "ANIMATE_CONTENT_SIZE", label = "card"))
    assertThat(AnimationPreviewRunner.shouldRunSizeDependentMeasurePass(expand)).isTrue()

    val visibility = listOf(DetectedAnimationSummary(type = "ANIMATED_VISIBILITY", label = "legend"))
    assertThat(AnimationPreviewRunner.shouldRunSizeDependentMeasurePass(visibility)).isTrue()
  }

  @Test
  fun previewAnimationManifest_serializesDetectedAnimationsAndNormalizesLegacyManifests() {
    val summary = DetectedAnimationSummary(type = "ANIMATE_CONTENT_SIZE", label = "animateContentSize")
    val manifest =
      PreviewAnimationManifest(
        previewId = "CardPreview",
        methodFQN = "pkg.CardKt.CardPreview",
        width = 100,
        height = 50,
        maxDurationMillis = 0L,
        maxDurationPerIterationMillis = 0L,
        effectiveDurationMillis = 500L,
        frameCount = 1,
        animations = emptyList(),
        detectedAnimations = listOf(summary),
        unsupportedAnimations = listOf("decayAnim"),
        frames = emptyList(),
      )
    val writer = StringWriter()
    writeAnimationManifestToJson(writer, manifest)
    assertThat(writer.toString()).contains("\"detectedAnimations\"")
    val roundTripped = readAnimationManifestFromJson(StringReader(writer.toString()))
    assertThat(roundTripped.detectedAnimations).containsExactly(summary)
    assertThat(roundTripped.unsupportedAnimations).containsExactly("decayAnim")

    val legacyJson = """{"previewId":"Old","methodFQN":"pkg.Old","width":10,"height":10,"frameCount":0}"""
    val legacy = readAnimationManifestFromJson(StringReader(legacyJson))
    assertThat(legacy.animations).isEmpty()
    assertThat(legacy.detectedAnimations).isEmpty()
    assertThat(legacy.unsupportedAnimations).isEmpty()
    assertThat(legacy.frames).isEmpty()
  }

  @Test
  fun drainCallbacksAndRender_reportsNonFatalCallbackFailureWithoutAbortingAndRethrowsFatalOrInterrupted() {
    // 1. A non-fatal exception inside a user callback (e.g. LaunchedEffect) is logged to RenderLogger, and the frame still renders.
    val renderLogger = RenderLogger()
    val rendered =
      AnimationPreviewRunner.drainCallbacksAndRender(
        prepareDispatcher = { CompletableFuture.completedFuture(null) },
        executeCallbacks = { CompletableFuture.failedFuture<Boolean>(IllegalStateException("boom in LaunchedEffect")) },
        renderFrame = { CompletableFuture.completedFuture("frame-0") },
        sessionTimeNanos = 500_000_000L,
        renderLogger = renderLogger,
      )
    assertThat(rendered).isEqualTo("frame-0")
    assertThat(renderLogger.hasErrors()).isTrue()

    // 2. OutOfMemoryError wrapped in ExecutionException is rethrown as a fatal VirtualMachineError.
    val oom = runCatching {
      AnimationPreviewRunner.drainCallbacksAndRender(
        prepareDispatcher = null,
        executeCallbacks = { CompletableFuture.failedFuture<Boolean>(OutOfMemoryError("callback OOM")) },
        renderFrame = { CompletableFuture.completedFuture("unreachable") },
        sessionTimeNanos = 500_000_000L,
      )
    }
      .exceptionOrNull()
    assertThat(oom).isInstanceOf(OutOfMemoryError::class.java)

    // 3. InterruptedException wrapped in ExecutionException restores the thread's interrupt flag and rethrows.
    try {
      val interrupted = runCatching {
        AnimationPreviewRunner.drainCallbacksAndRender(
          prepareDispatcher = null,
          executeCallbacks = { CompletableFuture.failedFuture<Boolean>(InterruptedException("watchdog interrupt")) },
          renderFrame = { CompletableFuture.completedFuture("unreachable") },
          sessionTimeNanos = 500_000_000L,
        )
      }
        .exceptionOrNull()
      assertThat(interrupted).isInstanceOf(InterruptedException::class.java)
      assertThat(Thread.currentThread().isInterrupted).isTrue()
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun findTimeoutException_extractsTimeoutAndBudget() {
    val directTimeout = TimeoutException("timed out after (5000ms)")
    assertThat(directTimeout.findTimeoutException()).isSameInstanceAs(directTimeout)
    assertThat(extractTimeoutBudgetMs(directTimeout, 100_000L)).isEqualTo(5000L)

    val nestedTimeout = ExecutionException("wrapper", RuntimeException(TimeoutException("render timed out")))
    val extracted = nestedTimeout.findTimeoutException()
    assertThat(extracted).isNotNull()
    assertThat(extractTimeoutBudgetMs(extracted!!, 100_000L)).isEqualTo(100_000L)

    val nonTimeout = ExecutionException("wrapper", IllegalArgumentException("not a timeout"))
    assertThat(nonTimeout.findTimeoutException()).isNull()
  }

  @Test
  fun staticPreviewCallbackRunner_recordTimeoutMarksRenderThreadState() {
    val timeoutState = RenderThreadTimeoutState()
    val runner = StaticPreviewCallbackRunner(timeoutState = timeoutState)
    runner.recordTimeout("pkg.PreviewKt.TimedOutPreview", 500L)
    val decision = timeoutState.evaluateBeforeRender(probeIfPoisoned = false)
    assertThat(decision).isInstanceOf(ProbeDecision.NeedsProbe::class.java)
  }

  private enum class SamplePhase {
    COLLAPSED,
    EXPANDED,
  }

  private class FakeComposeAnimation(
    override val label: String?,
    override val type: ComposeAnimationType,
    override val states: Set<Any> = emptySet(),
    override val animationObject: Any = Any(),
    val childTransition: Any? = null,
    val animationSpec: Any? = null,
  ) : ComposeAnimation

  private class FakeStatePair(val initial: Any?, val target: Any?)

  private class FakeSubClock(val state: Any?, private val maxDuration: Long = 350L) {
    fun getMaxDuration(): Long = maxDuration
  }

  private class KeyframesConfig(val durationMillis: Int = 450, val delayMillis: Int = 25)

  private class KeyframesSpec(val config: KeyframesConfig = KeyframesConfig())

  private class SnapSpec(val delay: Int = 15)

  private class FakeTransitionState(val label: String, val animationSpec: Any?)

  private class FakeTransitionObject(
    val targetState: String = "Visible",
    val animations: List<Any> = emptyList(),
    val transitions: List<Any> = emptyList(),
  )

  private class UnifiedMapPreviewClock(
    private val unified: Map<ComposeAnimation, Any>,
    val trackedUnsupportedAnimations: Set<ComposeAnimation> = emptySet(),
    private val transitionsByAnim: Map<ComposeAnimation, List<TransitionInfo>> = emptyMap(),
    private val propertiesByAnim: Map<ComposeAnimation, List<ComposeAnimatedProperty>> = emptyMap(),
  ) {
    val updatedFromTo = mutableListOf<Triple<ComposeAnimation, Any?, Any?>>()
    val updatedVisibility = mutableListOf<Pair<ComposeAnimation, Any?>>()
    val clockTimesSet = mutableListOf<Long>()

    @Suppress("FunctionName") fun `getAnimationClocks$ui_tooling`(): Map<ComposeAnimation, Any> = unified

    fun updateFromAndToStates(composeAnimation: ComposeAnimation, fromState: Any?, toState: Any?) {
      updatedFromTo.add(Triple(composeAnimation, fromState, toState))
    }

    fun updateAnimatedVisibilityState(composeAnimation: ComposeAnimation, state: Any?) {
      updatedVisibility.add(composeAnimation to state)
    }

    fun setClockTime(timeMillis: Long) {
      clockTimesSet.add(timeMillis)
    }

    fun getTransitions(composeAnimation: ComposeAnimation, @Suppress("UNUSED_PARAMETER") stepMillis: Long): List<TransitionInfo> =
      transitionsByAnim[composeAnimation].orEmpty()

    fun getAnimatedProperties(composeAnimation: ComposeAnimation): List<ComposeAnimatedProperty> =
      propertiesByAnim[composeAnimation].orEmpty()
  }

  @Test
  fun collectAllClockEntries_fallsBackToUnifiedAnimationClocksMapInUiTooling112() {
    val trAnim = FakeComposeAnimation(label = "card", type = ComposeAnimationType.TRANSITION_ANIMATION)
    val avAnim = FakeComposeAnimation(label = "legend", type = ComposeAnimationType.ANIMATED_VISIBILITY)
    val trClock = FakeSubClock(state = FakeStatePair(false, true))
    val avClock = FakeSubClock(state = "Enter")
    val unifiedClock = UnifiedMapPreviewClock(linkedMapOf(trAnim to trClock, avAnim to avClock))

    val entries = AnimationPreviewRunner.collectAllClockEntries(unifiedClock)

    assertThat(entries.map { it.type })
      .containsExactly(ComposeAnimationType.TRANSITION_ANIMATION, ComposeAnimationType.ANIMATED_VISIBILITY)
      .inOrder()
    assertThat(entries.map { it.composeAnimation }).containsExactly(trAnim, avAnim).inOrder()
  }

  @Test
  fun configureStates_flushesSetClockTimeZeroOnStateChangeAndTopLevelAnimatedVisibility() {
    // 1. Top-level AnimatedVisibility with no explicit fromState/toState still flushes setClockTime(0L) and returns true.
    val avAnim = FakeComposeAnimation(label = "topHidden", type = ComposeAnimationType.ANIMATED_VISIBILITY, states = setOf("Enter", "Exit"))
    val avOnlyClock = UnifiedMapPreviewClock(mapOf(avAnim to FakeSubClock(state = "Enter")))
    assertThat(
        AnimationPreviewRunner.configureStates(
          clock = avOnlyClock,
          options = AnimationRenderOptions(),
        )
      )
      .isTrue()
    assertThat(avOnlyClock.clockTimesSet).containsExactly(0L)

    // 2. Explicit AnimatedVisibility state ("gone" -> "Exit") + Transition auto-toggle (false -> true) + AnimateXAsState default toggle.
    val trAnim = FakeComposeAnimation(label = "expand", type = ComposeAnimationType.TRANSITION_ANIMATION, states = setOf(false, true))
    val xAnim = FakeComposeAnimation(label = "alpha", type = ComposeAnimationType.ANIMATE_X_AS_STATE, states = setOf(0f, 1f))
    val mixedClock =
      UnifiedMapPreviewClock(
        linkedMapOf(
          avAnim to FakeSubClock(state = "Enter"),
          trAnim to FakeSubClock(state = FakeStatePair(false, false)),
          xAnim to FakeSubClock(state = FakeStatePair(1f, 1f)),
        )
      )

    val changed =
      AnimationPreviewRunner.configureStates(
        clock = mixedClock,
        options = AnimationRenderOptions(),
      )
    assertThat(changed).isTrue()
    assertThat(mixedClock.updatedFromTo).containsExactly(Triple(trAnim, false, true), Triple(xAnim, 0f, 1f))
    assertThat(mixedClock.clockTimesSet).containsExactly(0L)

    // Explicit state targeting a single animation label.
    val explicitClock = UnifiedMapPreviewClock(mapOf(avAnim to FakeSubClock(state = "Enter")))
    assertThat(
        AnimationPreviewRunner.configureStates(
          clock = explicitClock,
          options = AnimationRenderOptions(animationLabel = "topHidden", toState = "gone"),
        )
      )
      .isTrue()
    assertThat(explicitClock.updatedVisibility).containsExactly(avAnim to "Exit")
    assertThat(explicitClock.clockTimesSet).containsExactly(0L)
  }

  @Test
  fun extractSubscribedAnimationsAndHelpers_extractsTracksSpecsUnsupportedAndFormatsValues() {
    val childTransition =
      FakeTransitionObject(
        targetState = "Visible",
        animations = listOf(FakeTransitionState("Built-in alpha", TweenSpec(durationMillis = 250, delay = 10, easing = Easing { it }))),
      )
    val parentTransition =
      FakeTransitionObject(
        targetState = "EXPANDED",
        animations = listOf(FakeTransitionState("offset", SpringSpec(dampingRatio = 0.5f, stiffness = 600f))),
        transitions = listOf(childTransition),
      )
    val trAnim =
      FakeComposeAnimation(
        label = "cardTransition",
        type = ComposeAnimationType.TRANSITION_ANIMATION,
        states = setOf(SamplePhase.COLLAPSED, SamplePhase.EXPANDED),
        animationObject = parentTransition,
      )
    val infAnim =
      FakeComposeAnimation(
        label = "shimmer",
        type = ComposeAnimationType.INFINITE_TRANSITION,
        animationObject =
          FakeTransitionObject(
            animations =
              listOf(FakeTransitionState("x", InfiniteRepeatableSpec(TweenSpec(durationMillis = 600, delay = 0, easing = Easing { it }))))
          ),
      )
    val unsupportedAnim = FakeComposeAnimation(label = "decayAnim", type = ComposeAnimationType.DECAY_ANIMATION)
    val clock =
      UnifiedMapPreviewClock(
        unified =
          linkedMapOf(
            trAnim to FakeSubClock(state = FakeStatePair(SamplePhase.COLLAPSED, SamplePhase.EXPANDED), maxDuration = 400L),
            infAnim to FakeSubClock(state = null, maxDuration = 600L),
          ),
        trackedUnsupportedAnimations = setOf(unsupportedAnim),
        transitionsByAnim =
          mapOf(
            trAnim to
              listOf(
                TransitionInfo("offset", "androidx.compose.animation.core.SpringSpec", 0L, 400L, linkedMapOf(0L to 0f, 400L to 24f)),
                TransitionInfo(
                  "Built-in alpha",
                  "androidx.compose.animation.core.TweenSpec",
                  10L,
                  260L,
                  linkedMapOf(10L to 0.0, 260L to 1.0),
                ),
              ),
            infAnim to
              listOf(
                TransitionInfo(
                  "x",
                  "androidx.compose.animation.core.InfiniteRepeatableSpec",
                  0L,
                  600L,
                  linkedMapOf(0L to 0, 600L to 100),
                )
              ),
          ),
        propertiesByAnim =
          mapOf(
            trAnim to listOf(ComposeAnimatedProperty("offset", 12.5f), ComposeAnimatedProperty("expanded", true)),
            infAnim to listOf(ComposeAnimatedProperty("x", 50L)),
          ),
      )

    val containers =
      IdentityHashMap<Any, AnimationPreviewRunner.AnimatedContainerInfo>().apply {
        put(childTransition, AnimationPreviewRunner.AnimatedContainerInfo("Badge", ComponentSourceLocation("Card.kt", 22)))
      }
    val detected =
      listOf(
        DetectedAnimationSummary("TRANSITION", "cardTransition", ComponentSourceLocation("Card.kt", 15)),
        DetectedAnimationSummary("INFINITE_TRANSITION", "shimmer", ComponentSourceLocation("Card.kt", 40)),
      )

    val subscribed = AnimationPreviewRunner.extractSubscribedAnimations(clock, detected, containers)
    assertThat(subscribed).hasSize(2)
    assertThat(subscribed[0].fromState).isEqualTo("COLLAPSED")
    assertThat(subscribed[0].toState).isEqualTo("EXPANDED")
    assertThat(subscribed[0].sourceLocation).isEqualTo(ComponentSourceLocation("Card.kt", 15))
    assertThat(subscribed[0].tracks).hasSize(2)
    assertThat(subscribed[0].tracks[0].spec?.dampingRatio).isWithin(0.001f).of(0.5f)
    assertThat(subscribed[0].tracks[1].displayLabel).isEqualTo("Badge · fade in")
    assertThat(subscribed[1].fromState).isEqualTo("Loop")
    assertThat(subscribed[1].tracks.single().spec?.specType).isEqualTo("InfiniteRepeatableSpec")
    assertThat(subscribed[1].tracks.single().spec?.durationMillis).isEqualTo(600)

    assertThat(AnimationPreviewRunner.extractUnsupportedAnimations(clock)).containsExactly("decayAnim")
    assertThat(AnimationPreviewRunner.sampleCurrentProperties(clock))
      .containsExactly("cardTransition.offset", "12.500", "cardTransition.expanded", "true", "shimmer.x", "50")

    // Additional spec summaries & value formatters.
    assertThat(AnimationPreviewRunner.summarizeAnimationSpec(KeyframesSpec()).durationMillis).isEqualTo(450)
    assertThat(AnimationPreviewRunner.summarizeAnimationSpec(SnapSpec()).delayMillis).isEqualTo(15)
    assertThat(AnimationPreviewRunner.formatAnimatedValue(null).first).isEqualTo("null")
    assertThat(AnimationPreviewRunner.formatAnimatedValue(SamplePhase.EXPANDED).first).isEqualTo("EXPANDED")
  }

  private class FakeSlotLocation(val sourceFile: String, val lineNumber: Int, val packageHash: Int = 42)

  private class Transition(val label: String?, val currentState: Any? = null)

  private class Animatable(val label: String?)

  private class InfiniteTransition(val label: String?)

  /**
   * Backing data for ASM-generated `androidx.compose.ui.tooling.data.Group` instances in [IsolatedUiToolingClassLoader].
   *
   * Counts [metadataReads] (`getLocation`, `getData`, `getModifierInfo`) separately from structural tree traversal (`getName`,
   * `getChildren`) so tests can verify that static previews short-circuit before running `indexSlotTreeMetadata`.
   */
  class UiToolingGroupPayload(
    val name: String?,
    private val rawLocation: Any? = null,
    private val rawData: List<Any> = emptyList(),
    private val rawModifierInfo: List<Any> = emptyList(),
    val children: List<Any> = emptyList(),
  ) {
    var metadataReads: Int = 0
      private set

    fun readLocation(): Any? {
      metadataReads++
      return rawLocation
    }

    fun readData(): List<Any> {
      metadataReads++
      return rawData
    }

    fun readModifierInfo(): List<Any> {
      metadataReads++
      return rawModifierInfo
    }
  }

  /** Thread-local callback invoked by ASM-generated `AnimationSearch$Search.addAnimations(groups)` stubs during unit tests. */
  object UiToolingSearchHook {
    private val activeRule = ThreadLocal<((String, Collection<*>) -> Set<Any>)?>()
    private val invocationCounter = ThreadLocal<Int>()

    val invocations: Int
      get() = invocationCounter.get() ?: 0

    fun resetInvocations() {
      invocationCounter.set(0)
    }

    fun <T> withRule(rule: (String, Collection<*>) -> Set<Any>, block: () -> T): T {
      val prev = activeRule.get()
      val prevCount = invocationCounter.get()
      activeRule.set(rule)
      invocationCounter.set(0)
      return try {
        block()
      } finally {
        activeRule.set(prev)
        invocationCounter.set(prevCount)
      }
    }

    @JvmStatic
    fun find(searchSimpleName: String, groups: Collection<*>): Set<Any> {
      invocationCounter.set((invocationCounter.get() ?: 0) + 1)
      return activeRule.get()?.invoke(searchSimpleName, groups).orEmpty()
    }
  }

  private class FakeModifierInfo(val modifier: Any)

  private class CombinedModifier(val outer: Any, val inner: Any)

  private class FakeSizeElement

  private class FakeTargetBasedAnim

  private class FakeDecayAnim

  /** Mirrors Compose 1.12+ `androidx.compose.ui.tooling.animation.search.*SearchInfo` wrappers. */
  private class FakeSearchInfo(
    val transition: Any? = null,
    val animatable: Any? = null,
    val infiniteTransition: Any? = null,
    val animationObject: Any? = null,
    val label: String? = null,
  )

  @Test
  fun detectAnimationsFromAdapter_extractsSlotTableAnimationsAndContainerContentNames() {
    val env = IsolatedUiToolingClassLoader(compose112Constructor = false)
    val avTransition = Transition(label = "avLabel")
    val acTransition = Transition(label = "acLabel")
    val standaloneTransition = Transition(label = "cardState", currentState = SamplePhase.COLLAPSED)
    val floatAnimatable = Animatable(label = "pulseAlpha")
    val infTransition = InfiniteTransition(label = "shimmerLoop")

    val avGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "AnimatedVisibility",
          rawLocation = FakeSlotLocation("SleepBar.kt", 112),
          children =
            listOf(
              env.newGroup(
                UiToolingGroupPayload(
                  name = "updateTransition",
                  rawLocation = FakeSlotLocation("Transition.kt", 88),
                  children = listOf(env.newGroup(UiToolingGroupPayload(name = "remember", rawData = listOf(avTransition)))),
                )
              ),
              env.newGroup(
                UiToolingGroupPayload(
                  name = "Row",
                  rawLocation = FakeSlotLocation("SleepBar.kt", 113),
                  children =
                    listOf(env.newGroup(UiToolingGroupPayload(name = "DetailLegend", rawLocation = FakeSlotLocation("SleepBar.kt", 114)))),
                )
              ),
            ),
        )
      )
    val acGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "AnimatedContent",
          rawLocation = FakeSlotLocation("SleepBar.kt", 130),
          children =
            listOf(
              env.newGroup(UiToolingGroupPayload(name = "updateTransition", rawData = listOf(acTransition))),
              env.newGroup(UiToolingGroupPayload(name = "Box", rawLocation = FakeSlotLocation("SleepBar.kt", 131))),
            ),
        )
      )
    val trGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "updateTransition",
          rawLocation = FakeSlotLocation("SleepBar.kt", 150),
          rawData = listOf(standaloneTransition),
        )
      )
    val axGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "animateFloatAsState",
          rawLocation = FakeSlotLocation("SleepBar.kt", 160),
          children =
            listOf(
              env.newGroup(
                UiToolingGroupPayload(
                  name = "animateValueAsState",
                  rawLocation = FakeSlotLocation("AnimateAsState.kt", 55),
                  rawData = listOf(floatAnimatable),
                )
              )
            ),
        )
      )
    val infGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "rememberInfiniteTransition",
          rawLocation = FakeSlotLocation("SleepBar.kt", 175),
          rawData = listOf(infTransition),
        )
      )
    val root =
      env.newGroup(
        UiToolingGroupPayload(
          name = "SleepScreen",
          rawLocation = FakeSlotLocation("SleepBar.kt", 100),
          children = listOf(avGroup, acGroup, trGroup, axGroup, infGroup),
        )
      )

    val (hasAnims, summaries) =
      UiToolingSearchHook.withRule({ searchName, _ ->
        when (searchName) {
          "AnimatedVisibilitySearch" -> setOf(avTransition)
          "AnimatedContentSearch" -> setOf(acTransition)
          "TransitionSearch" -> linkedSetOf(avTransition, acTransition, standaloneTransition)
          "AnimateXAsStateSearch" -> setOf(floatAnimatable)
          "InfiniteTransitionSearch" -> setOf(infTransition)
          else -> emptySet()
        }
      }) {
        val adapter = env.newAdapter(listOf(root))
        AnimationPreviewRunner.detectAnimationsFromAdapter(adapter, slotTrees = listOf(root))
      }

    assertThat(hasAnims).isTrue()
    assertThat(summaries.map { "${it.type}:${it.label}:${it.sourceLocation?.lineNumber}" })
      .containsExactly(
        "ANIMATED_VISIBILITY:avLabel:112",
        "ANIMATED_CONTENT:acLabel:130",
        "TRANSITION:cardState:150",
        "ANIMATE_X_AS_STATE:pulseAlpha:160",
        "INFINITE_TRANSITION:shimmerLoop:175",
      )
      .inOrder()

    val containers = AnimationPreviewRunner.animatedContainersByTransition(env.newAdapter(animated = true), slotTrees = listOf(root))
    assertThat(containers[avTransition]?.contentName).isEqualTo("DetailLegend")
    assertThat(containers[acTransition]?.contentName).isEqualTo("Box")
  }

  @Test
  fun detectAnimationsViaUiToolingSearch_delegatesToCompose17SearchWithoutTrackingAndShortCircuitsStaticAdapter() {
    val env = IsolatedUiToolingClassLoader(compose112Constructor = false)

    // 1. Static preview fast path: `ComposeViewAdapter.onLayout` computes `hasAnimations() = AnimationSearch.searchAny(slotTrees)` = false,
    //    so `detectAnimationsFromAdapter` returns `false to emptyList()` immediately with 0 extra `AnimationSearch` runs and 0 metadata
    // reads.
    val staticTextPayload = UiToolingGroupPayload(name = "Text", rawLocation = FakeSlotLocation("Home.kt", 22))
    val staticBoxPayload =
      UiToolingGroupPayload(
        name = "Box",
        rawLocation = FakeSlotLocation("Home.kt", 20),
        children = listOf(env.newGroup(staticTextPayload)),
      )
    val staticRoot = env.newGroup(staticBoxPayload)

    UiToolingSearchHook.withRule({ _, _ -> emptySet() }) {
      val staticAdapter = env.newAdapter(listOf(staticRoot))
      assertThat(UiToolingSearchHook.invocations).isGreaterThan(0)
      UiToolingSearchHook.resetInvocations()

      val (staticHasAnims, staticSummaries) =
        AnimationPreviewRunner.detectAnimationsFromAdapter(staticAdapter, slotTrees = listOf(staticRoot))
      assertThat(staticHasAnims).isFalse()
      assertThat(staticSummaries).isEmpty()
      assertThat(UiToolingSearchHook.invocations).isEqualTo(0)
      assertThat(staticBoxPayload.metadataReads + staticTextPayload.metadataReads).isEqualTo(0)

      // Direct `UiToolingAnimationSearchBridge` call also skips `indexSlotTreeMetadata` when searches return empty sets.
      assertThat(AnimationPreviewRunner.detectAnimationsViaUiToolingSearch(staticAdapter, listOf(staticRoot))).isEmpty()
      assertThat(staticBoxPayload.metadataReads + staticTextPayload.metadataReads).isEqualTo(0)
    }

    // 2. Animated preview: `ComposeViewAdapter.hasAnimations()` delegates to `AnimationSearch.searchAny` -> true, and
    //    `detectAnimationsFromAdapter` delegates discovery to `AnimationSearch` (without calling `Search.track()`, which throws
    //    AssertionError), deduplicating `AnimatedVisibility`'s internal `Transition`, unwrapping `CombinedModifier` for
    //    `animateContentSize`, inheriting outer `animateDpAsState` wrapper metadata, and appending scoped `transition.AnimatedVisibility`.
    val avTransition = Transition(label = "headerVis")
    val cardTransition = Transition(label = "cardExpand", currentState = SamplePhase.EXPANDED)
    val unlabeledDpAnimatable = Animatable(label = null)
    val infTransition = InfiniteTransition(label = "pulse")
    val sizeElement = FakeSizeElement()
    val targetBased = FakeTargetBasedAnim()
    val decay = FakeDecayAnim()

    val avGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "AnimatedVisibility",
          rawLocation = FakeSlotLocation("Dashboard.kt", 40),
          children =
            listOf(
              env.newGroup(
                UiToolingGroupPayload(
                  name = "updateTransition",
                  rawLocation = FakeSlotLocation("Transition.kt", 88),
                  rawData = listOf(avTransition),
                )
              )
            ),
        )
      )
    val cardGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "updateTransition",
          rawLocation = FakeSlotLocation("Dashboard.kt", 55),
          rawData = listOf(cardTransition),
          children =
            listOf(
              // Scoped `transition.AnimatedVisibility` has no child `updateTransition`, so `AnimationSearch` leaves it to parent clock.
              env.newGroup(
                UiToolingGroupPayload(
                  name = "AnimatedVisibility",
                  rawLocation = FakeSlotLocation("Dashboard.kt", 60),
                )
              )
            ),
        )
      )
    val dpGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "animateDpAsState",
          rawLocation = FakeSlotLocation("Dashboard.kt", 72),
          children =
            listOf(
              env.newGroup(
                UiToolingGroupPayload(
                  name = "animateValueAsState",
                  rawLocation = FakeSlotLocation("AnimateAsState.kt", 55),
                  rawData = listOf(unlabeledDpAnimatable),
                )
              )
            ),
        )
      )
    val infGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "rememberInfiniteTransition",
          rawLocation = FakeSlotLocation("Dashboard.kt", 85),
          rawData = listOf(infTransition),
        )
      )
    val sizeGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "Box",
          rawLocation = FakeSlotLocation("Dashboard.kt", 95),
          rawModifierInfo = listOf(FakeModifierInfo(CombinedModifier(outer = Any(), inner = sizeElement))),
        )
      )
    val unsupportedGroup =
      env.newGroup(
        UiToolingGroupPayload(
          name = "remember",
          rawLocation = FakeSlotLocation("Dashboard.kt", 105),
          rawData = listOf(targetBased, decay),
        )
      )
    val animatedRoot =
      env.newGroup(
        UiToolingGroupPayload(
          name = "DashboardScreen",
          rawLocation = FakeSlotLocation("Dashboard.kt", 35),
          children = listOf(avGroup, cardGroup, dpGroup, infGroup, sizeGroup, unsupportedGroup),
        )
      )

    val (hasAnims, summaries) =
      UiToolingSearchHook.withRule({ searchName, _ ->
        when (searchName) {
          "AnimatedVisibilitySearch" -> setOf(avTransition)
          // `TransitionSearch` sees both `avTransition` and `cardTransition`; `avTransition` must be deduplicated as claimed!
          "TransitionSearch" -> linkedSetOf(avTransition, cardTransition)
          "AnimateXAsStateSearch" -> setOf(unlabeledDpAnimatable)
          "InfiniteTransitionSearch" -> setOf(infTransition)
          "AnimateContentSizeSearch" -> setOf(sizeElement)
          "TargetBasedSearch" -> setOf(targetBased)
          "DecaySearch" -> setOf(decay)
          else -> emptySet()
        }
      }) {
        val animatedAdapter = env.newAdapter(listOf(animatedRoot))
        AnimationPreviewRunner.detectAnimationsFromAdapter(animatedAdapter, slotTrees = listOf(animatedRoot))
      }

    assertThat(hasAnims).isTrue()
    assertThat(summaries.map { "${it.type}:${it.label}:${it.sourceLocation?.lineNumber}" })
      .containsExactly(
        "ANIMATED_VISIBILITY:headerVis:40",
        "TRANSITION:cardExpand:55",
        "ANIMATED_VISIBILITY:AnimatedVisibility:60",
        "ANIMATE_X_AS_STATE:animateDpAsState:72",
        "INFINITE_TRANSITION:pulse:85",
        "ANIMATE_CONTENT_SIZE:animateContentSize:95",
        "TARGET_BASED:TargetBasedAnimation:105",
        "DECAY:DecayAnimation:105",
      )
      .inOrder()
  }

  @Test
  fun detectAnimationsViaUiToolingSearch_supportsCompose112SingleArgConstructorSearchInfoWrappersAndTriggerSearch() {
    val env = IsolatedUiToolingClassLoader(compose112Constructor = true)

    val avTransition = Transition(label = null)
    val tabTransition = Transition(label = null, currentState = SamplePhase.EXPANDED)
    val alphaAnim = Animatable(label = null)
    val imperativeAnimatable = Animatable(label = "flingOffset")

    val root =
      env.newGroup(
        UiToolingGroupPayload(
          name = "SheetScreen",
          rawLocation = FakeSlotLocation("Sheet.kt", 20),
          children =
            listOf(
              env.newGroup(
                UiToolingGroupPayload(
                  name = "AnimatedVisibility",
                  rawLocation = FakeSlotLocation("Sheet.kt", 30),
                  children =
                    listOf(
                      env.newGroup(
                        UiToolingGroupPayload(
                          name = "updateTransition",
                          rawLocation = FakeSlotLocation("Transition.kt", 88),
                          rawData = listOf(avTransition),
                        )
                      )
                    ),
                )
              ),
              env.newGroup(
                UiToolingGroupPayload(
                  name = "updateTransition",
                  rawLocation = FakeSlotLocation("Sheet.kt", 44),
                  rawData = listOf(tabTransition),
                )
              ),
              env.newGroup(
                UiToolingGroupPayload(
                  name = "animateFloatAsState",
                  rawLocation = FakeSlotLocation("Sheet.kt", 58),
                  rawData = listOf(alphaAnim),
                )
              ),
              env.newGroup(
                UiToolingGroupPayload(
                  name = "remember",
                  rawLocation = FakeSlotLocation("Sheet.kt", 70),
                  rawData = listOf(imperativeAnimatable),
                )
              ),
            ),
        )
      )

    val (hasAnims, summaries) =
      UiToolingSearchHook.withRule({ searchName, _ ->
        when (searchName) {
          "AnimatedVisibilitySearch" -> setOf(FakeSearchInfo(transition = avTransition, label = "sheetVis"))
          "TransitionSearch" ->
            linkedSetOf(
              FakeSearchInfo(transition = avTransition, label = "sheetVis"),
              FakeSearchInfo(transition = tabTransition, label = "tabSwitch"),
            )
          "AnimateXAsStateSearch" -> setOf(FakeSearchInfo(animatable = alphaAnim, label = "fadeAlpha"))
          "TriggerSearch" -> setOf(FakeSearchInfo(animationObject = imperativeAnimatable, label = "flingOffset"))
          else -> emptySet()
        }
      }) {
        val adapter = env.newAdapter(listOf(root))
        AnimationPreviewRunner.detectAnimationsFromAdapter(adapter, slotTrees = listOf(root))
      }

    assertThat(hasAnims).isTrue()
    assertThat(summaries.map { "${it.type}:${it.label}:${it.sourceLocation?.lineNumber}" })
      .containsExactly(
        "ANIMATED_VISIBILITY:sheetVis:30",
        "TRANSITION:tabSwitch:44",
        "ANIMATE_X_AS_STATE:fadeAlpha:58",
        "ANIMATABLE:flingOffset:70",
      )
      .inOrder()
  }

  /**
   * In-memory isolated [ClassLoader] synthesizing the repackaged `DefaultModuleClassLoader` + `ui-tooling` surface used by
   * [AnimationPreviewRunner.detectAnimationsFromAdapter] and [AnimationPreviewRunner.detectAnimationsViaUiToolingSearch].
   *
   * Generates bytecode equivalent to the following Java/Kotlin declarations:
   * ```kotlin
   * package _layoutlib_._internal_.kotlin.jvm.functions
   * interface Function0 { fun invoke(): Any? }
   *
   * package _layoutlib_._internal_.kotlin
   * object Unit
   * object NoOpFn0 : Function0 { override fun invoke(): Any = Unit }
   *
   * package androidx.compose.ui.tooling
   * class ComposeViewAdapter {
   *   private val animated: Boolean
   *   constructor(animated: Boolean) { this.animated = animated }
   *   constructor(slotTrees: Collection<*>) {
   *     this.animated = AnimationSearch(NoOpFn0 /*, NoOpFn0 */).searchAny(slotTrees)
   *   }
   *   fun hasAnimations(): Boolean = animated
   * }
   *
   * package androidx.compose.ui.tooling.data
   * class Group(private val payload: UiToolingGroupPayload) {
   *   fun getName(): String? = payload.name
   *   fun getChildren(): Collection<*> = payload.children
   *   fun getLocation(): Any? = payload.readLocation()
   *   fun getData(): Iterable<*> = payload.readData()
   *   fun getModifierInfo(): Iterable<*> = payload.readModifierInfo()
   * }
   *
   * package androidx.compose.ui.tooling.animation
   * class AnimationSearch(clock: Function0 /*, onSeek: Function0 in Compose 1.5..1.10 */) {
   *   private val setToSearch: Set<Search> = linkedSetOf(
   *     AnimatedVisibilitySearch(), AnimatedContentSearch(), TransitionSearch(), AnimateXAsStateSearch(),
   *     InfiniteTransitionSearch(), AnimateContentSizeSearch(), TargetBasedSearch(), DecaySearch(),
   *   )
   *   // Present when `compose112Constructor == true`:
   *   private val triggerSearch: Set<Search> = linkedSetOf(TriggerSearch())
   *
   *   fun searchAny(groups: Collection<*>): Boolean =
   *     searchSet(setToSearch, groups) || (/* when compose112Constructor */ searchSet(triggerSearch, groups))
   *
   *   companion object {
   *     @JvmStatic
   *     private fun searchSet(searches: Set<Search>, groups: Collection<*>): Boolean =
   *       searches.any { it.hasAny(groups) }
   *   }
   *
   *   open class Search {
   *     private val animations: MutableSet<Any> = LinkedHashSet()
   *     fun getAnimations(): Set<Any> = animations
   *     open fun addAnimations(groups: Collection<*>) { animations.addAll(UiToolingSearchHook.find(javaClass.simpleName, groups)) }
   *     fun hasAny(groups: Collection<*>): Boolean { addAnimations(groups); return !animations.isEmpty() }
   *     open fun track(): Nothing = throw AssertionError("Search.track() must not be called during static animation detection")
   *   }
   *   class AnimatedVisibilitySearch : Search() // ... plus AnimatedContentSearch, TransitionSearch, etc.
   * }
   * ```
   */
  private class IsolatedUiToolingClassLoader(compose112Constructor: Boolean) :
    ClassLoader(AnimationPreviewRunnerTest::class.java.classLoader) {
    private val definitions: Map<String, ByteArray> = buildClassDefinitions(compose112Constructor)

    override fun findClass(name: String): Class<*> {
      val bytes = definitions[name] ?: throw ClassNotFoundException(name)
      return defineClass(name, bytes, 0, bytes.size)
    }

    fun newAdapter(animated: Boolean): Any =
      loadClass("androidx.compose.ui.tooling.ComposeViewAdapter").getConstructor(Boolean::class.javaPrimitiveType).newInstance(animated)

    fun newAdapter(slotTrees: Collection<*>): Any =
      loadClass("androidx.compose.ui.tooling.ComposeViewAdapter").getConstructor(Collection::class.java).newInstance(slotTrees)

    fun newGroup(payload: UiToolingGroupPayload): Any =
      loadClass("androidx.compose.ui.tooling.data.Group").getConstructor(UiToolingGroupPayload::class.java).newInstance(payload)

    companion object {
      private const val FN0_INTERNAL = "_layoutlib_/_internal_/kotlin/jvm/functions/Function0"
      private const val UNIT_INTERNAL = "_layoutlib_/_internal_/kotlin/Unit"
      private const val NOOP_FN0_INTERNAL = "_layoutlib_/_internal_/kotlin/NoOpFn0"
      private const val ADAPTER_INTERNAL = "androidx/compose/ui/tooling/ComposeViewAdapter"
      private const val GROUP_INTERNAL = "androidx/compose/ui/tooling/data/Group"
      private const val ANIM_SEARCH_INTERNAL = "androidx/compose/ui/tooling/animation/AnimationSearch"
      private const val SEARCH_BASE_INTERNAL = "$ANIM_SEARCH_INTERNAL\$Search"
      private val PAYLOAD_INTERNAL = UiToolingGroupPayload::class.java.name.replace('.', '/')
      private val HOOK_INTERNAL = UiToolingSearchHook::class.java.name.replace('.', '/')

      private val STANDARD_SEARCHES =
        listOf(
          "AnimatedVisibilitySearch",
          "AnimatedContentSearch",
          "TransitionSearch",
          "AnimateXAsStateSearch",
          "InfiniteTransitionSearch",
          "AnimateContentSizeSearch",
          "TargetBasedSearch",
          "DecaySearch",
        )
      private val ALL_SEARCHES = STANDARD_SEARCHES + "TriggerSearch"

      private fun newFrameWriter(): ClassWriter =
        object : ClassWriter(COMPUTE_FRAMES) {
          override fun getCommonSuperClass(type1: String, type2: String): String = runCatching {
            super.getCommonSuperClass(type1, type2)
          }
            .getOrDefault("java/lang/Object")
        }

      private fun buildClassDefinitions(compose112Constructor: Boolean): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        map[FN0_INTERNAL.replace('/', '.')] = generateFunction0()
        map[UNIT_INTERNAL.replace('/', '.')] = generateUnit()
        map[NOOP_FN0_INTERNAL.replace('/', '.')] = generateNoOpFn0()
        map[ADAPTER_INTERNAL.replace('/', '.')] = generateAdapter(compose112Constructor)
        map[GROUP_INTERNAL.replace('/', '.')] = generateGroup()
        map[SEARCH_BASE_INTERNAL.replace('/', '.')] = generateSearchBase()
        for (sub in ALL_SEARCHES) {
          map["$ANIM_SEARCH_INTERNAL\$$sub".replace('/', '.')] = generateSearchSubclass(sub)
        }
        map[ANIM_SEARCH_INTERNAL.replace('/', '.')] = generateAnimationSearch(compose112Constructor)
        return map
      }

      /** Emits `interface _layoutlib_._internal_.kotlin.jvm.functions.Function0` (see class-level KDoc). */
      private fun generateFunction0(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
          Opcodes.V17,
          Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
          FN0_INTERNAL,
          null,
          "java/lang/Object",
          null,
        )
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "invoke", "()Ljava/lang/Object;", null, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
      }

      /** Emits `object _layoutlib_._internal_.kotlin.Unit` with `INSTANCE` singleton field (see class-level KDoc). */
      private fun generateUnit(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, UNIT_INTERNAL, null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "INSTANCE", "L$UNIT_INTERNAL;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
          visitCode()
          visitTypeInsn(Opcodes.NEW, UNIT_INTERNAL)
          visitInsn(Opcodes.DUP)
          visitMethodInsn(Opcodes.INVOKESPECIAL, UNIT_INTERNAL, "<init>", "()V", false)
          visitFieldInsn(Opcodes.PUTSTATIC, UNIT_INTERNAL, "INSTANCE", "L$UNIT_INTERNAL;")
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
      }

      /** Emits `object _layoutlib_._internal_.kotlin.NoOpFn0 : Function0` returning `Unit.INSTANCE` (see class-level KDoc). */
      private fun generateNoOpFn0(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
          Opcodes.V17,
          Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
          NOOP_FN0_INTERNAL,
          null,
          "java/lang/Object",
          arrayOf(FN0_INTERNAL),
        )
        cw
          .visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "INSTANCE", "L$NOOP_FN0_INTERNAL;", null, null)
          .visitEnd()
        cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "()Ljava/lang/Object;", null, null).apply {
          visitCode()
          visitFieldInsn(Opcodes.GETSTATIC, UNIT_INTERNAL, "INSTANCE", "L$UNIT_INTERNAL;")
          visitInsn(Opcodes.ARETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
          visitCode()
          visitTypeInsn(Opcodes.NEW, NOOP_FN0_INTERNAL)
          visitInsn(Opcodes.DUP)
          visitMethodInsn(Opcodes.INVOKESPECIAL, NOOP_FN0_INTERNAL, "<init>", "()V", false)
          visitFieldInsn(Opcodes.PUTSTATIC, NOOP_FN0_INTERNAL, "INSTANCE", "L$NOOP_FN0_INTERNAL;")
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
      }

      /**
       * Emits `class androidx.compose.ui.tooling.ComposeViewAdapter` with `(boolean)` and `(Collection)` constructors, where `(Collection)`
       * computes `hasAnimations` via `AnimationSearch.searchAny(slotTrees)` matching real `ComposeViewAdapter.findAndTrackAnimations()`.
       */
      private fun generateAdapter(compose112Constructor: Boolean): ByteArray {
        val fn0Desc = "L$FN0_INTERNAL;"
        val animSearchCtorDesc = if (compose112Constructor) "($fn0Desc)V" else "($fn0Desc$fn0Desc)V"
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, ADAPTER_INTERNAL, null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "animated", "Z", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Z)V", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
          visitVarInsn(Opcodes.ALOAD, 0)
          visitVarInsn(Opcodes.ILOAD, 1)
          visitFieldInsn(Opcodes.PUTFIELD, ADAPTER_INTERNAL, "animated", "Z")
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/Collection;)V", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
          visitVarInsn(Opcodes.ALOAD, 0)
          visitTypeInsn(Opcodes.NEW, ANIM_SEARCH_INTERNAL)
          visitInsn(Opcodes.DUP)
          visitFieldInsn(Opcodes.GETSTATIC, NOOP_FN0_INTERNAL, "INSTANCE", "L$NOOP_FN0_INTERNAL;")
          if (!compose112Constructor) {
            visitInsn(Opcodes.DUP)
          }
          visitMethodInsn(Opcodes.INVOKESPECIAL, ANIM_SEARCH_INTERNAL, "<init>", animSearchCtorDesc, false)
          visitVarInsn(Opcodes.ALOAD, 1)
          visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIM_SEARCH_INTERNAL, "searchAny", "(Ljava/util/Collection;)Z", false)
          visitFieldInsn(Opcodes.PUTFIELD, ADAPTER_INTERNAL, "animated", "Z")
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "hasAnimations", "()Z", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitFieldInsn(Opcodes.GETFIELD, ADAPTER_INTERNAL, "animated", "Z")
          visitInsn(Opcodes.IRETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
      }

      /** Emits `class androidx.compose.ui.tooling.data.Group(payload: UiToolingGroupPayload)` (see class-level KDoc). */
      private fun generateGroup(): ByteArray {
        val payloadDesc = "L$PAYLOAD_INTERNAL;"
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, GROUP_INTERNAL, null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "payload", payloadDesc, null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "($payloadDesc)V", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
          visitVarInsn(Opcodes.ALOAD, 0)
          visitVarInsn(Opcodes.ALOAD, 1)
          visitFieldInsn(Opcodes.PUTFIELD, GROUP_INTERNAL, "payload", payloadDesc)
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        fun addDelegatingGetter(methodName: String, methodReturnDesc: String, payloadMethod: String, payloadReturnDesc: String) {
          cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()$methodReturnDesc", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, GROUP_INTERNAL, "payload", payloadDesc)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, PAYLOAD_INTERNAL, payloadMethod, "()$payloadReturnDesc", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
          }
        }
        addDelegatingGetter("getName", "Ljava/lang/String;", "getName", "Ljava/lang/String;")
        addDelegatingGetter("getChildren", "Ljava/util/Collection;", "getChildren", "Ljava/util/List;")
        addDelegatingGetter("getLocation", "Ljava/lang/Object;", "readLocation", "Ljava/lang/Object;")
        addDelegatingGetter("getData", "Ljava/lang/Iterable;", "readData", "Ljava/util/List;")
        addDelegatingGetter("getModifierInfo", "Ljava/lang/Iterable;", "readModifierInfo", "Ljava/util/List;")
        cw.visitEnd()
        return cw.toByteArray()
      }

      /** Emits `open class AnimationSearch.Search` whose `track()` throws [AssertionError] (see class-level KDoc). */
      private fun generateSearchBase(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, SEARCH_BASE_INTERNAL, null, "java/lang/Object", null)
        cw.visitInnerClass(SEARCH_BASE_INTERNAL, ANIM_SEARCH_INTERNAL, "Search", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "animations", "Ljava/util/Set;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
          visitVarInsn(Opcodes.ALOAD, 0)
          visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashSet")
          visitInsn(Opcodes.DUP)
          visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedHashSet", "<init>", "()V", false)
          visitFieldInsn(Opcodes.PUTFIELD, SEARCH_BASE_INTERNAL, "animations", "Ljava/util/Set;")
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "getAnimations", "()Ljava/util/Set;", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitFieldInsn(Opcodes.GETFIELD, SEARCH_BASE_INTERNAL, "animations", "Ljava/util/Set;")
          visitInsn(Opcodes.ARETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "addAnimations", "(Ljava/util/Collection;)V", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitFieldInsn(Opcodes.GETFIELD, SEARCH_BASE_INTERNAL, "animations", "Ljava/util/Set;")
          visitVarInsn(Opcodes.ALOAD, 0)
          visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
          visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false)
          visitVarInsn(Opcodes.ALOAD, 1)
          visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_INTERNAL, "find", "(Ljava/lang/String;Ljava/util/Collection;)Ljava/util/Set;", false)
          visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "addAll", "(Ljava/util/Collection;)Z", true)
          visitInsn(Opcodes.POP)
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "hasAny", "(Ljava/util/Collection;)Z", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitVarInsn(Opcodes.ALOAD, 1)
          visitMethodInsn(Opcodes.INVOKEVIRTUAL, SEARCH_BASE_INTERNAL, "addAnimations", "(Ljava/util/Collection;)V", false)
          visitVarInsn(Opcodes.ALOAD, 0)
          visitFieldInsn(Opcodes.GETFIELD, SEARCH_BASE_INTERNAL, "animations", "Ljava/util/Set;")
          visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "isEmpty", "()Z", true)
          visitInsn(Opcodes.ICONST_1)
          visitInsn(Opcodes.IXOR)
          visitInsn(Opcodes.IRETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "track", "()V", null, null).apply {
          visitCode()
          visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError")
          visitInsn(Opcodes.DUP)
          visitLdcInsn("Search.track() must not be called during static animation detection")
          visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false)
          visitInsn(Opcodes.ATHROW)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
      }

      /** Emits `final class AnimationSearch$<subSimpleName> : AnimationSearch.Search()` (see class-level KDoc). */
      private fun generateSearchSubclass(subSimpleName: String): ByteArray {
        val subInternal = "$ANIM_SEARCH_INTERNAL\$$subSimpleName"
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, subInternal, null, SEARCH_BASE_INTERNAL, null)
        cw.visitInnerClass(SEARCH_BASE_INTERNAL, ANIM_SEARCH_INTERNAL, "Search", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        cw.visitInnerClass(
          subInternal,
          ANIM_SEARCH_INTERNAL,
          subSimpleName,
          Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_STATIC,
        )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitMethodInsn(Opcodes.INVOKESPECIAL, SEARCH_BASE_INTERNAL, "<init>", "()V", false)
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
      }

      /**
       * Emits `class AnimationSearch` with 2-arg (Compose 1.5..1.10) or 1-arg + `triggerSearch` (Compose 1.12+) ctor and
       * `searchAny(Collection)` (see class-level KDoc).
       */
      private fun generateAnimationSearch(compose112Constructor: Boolean): ByteArray {
        val fn0Desc = "L$FN0_INTERNAL;"
        val ctorDesc = if (compose112Constructor) "($fn0Desc)V" else "($fn0Desc$fn0Desc)V"
        val cw = newFrameWriter()
        cw.visit(
          Opcodes.V17,
          Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
          ANIM_SEARCH_INTERNAL,
          null,
          "java/lang/Object",
          null,
        )
        cw.visitInnerClass(SEARCH_BASE_INTERNAL, ANIM_SEARCH_INTERNAL, "Search", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        for (sub in ALL_SEARCHES) {
          cw.visitInnerClass(
            "$ANIM_SEARCH_INTERNAL\$$sub",
            ANIM_SEARCH_INTERNAL,
            sub,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_STATIC,
          )
        }
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "setToSearch", "Ljava/util/Set;", null, null).visitEnd()
        if (compose112Constructor) {
          cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "triggerSearch", "Ljava/util/Set;", null, null).visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

          fun populateSetField(fieldName: String, subclassNames: List<String>) {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashSet")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedHashSet", "<init>", "()V", false)
            for (sub in subclassNames) {
              val subInternal = "$ANIM_SEARCH_INTERNAL\$$sub"
              visitInsn(Opcodes.DUP)
              visitTypeInsn(Opcodes.NEW, subInternal)
              visitInsn(Opcodes.DUP)
              visitMethodInsn(Opcodes.INVOKESPECIAL, subInternal, "<init>", "()V", false)
              visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true)
              visitInsn(Opcodes.POP)
            }
            visitFieldInsn(Opcodes.PUTFIELD, ANIM_SEARCH_INTERNAL, fieldName, "Ljava/util/Set;")
          }

          populateSetField("setToSearch", STANDARD_SEARCHES)
          if (compose112Constructor) {
            populateSetField("triggerSearch", listOf("TriggerSearch"))
          }
          visitInsn(Opcodes.RETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw
          .visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
            "searchSet",
            "(Ljava/util/Set;Ljava/util/Collection;)Z",
            null,
            null,
          )
          .apply {
            val loop = Label()
            val done = Label()
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLabel(loop)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
            visitJumpInsn(Opcodes.IFEQ, done)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
            visitTypeInsn(Opcodes.CHECKCAST, SEARCH_BASE_INTERNAL)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, SEARCH_BASE_INTERNAL, "hasAny", "(Ljava/util/Collection;)Z", false)
            visitJumpInsn(Opcodes.IFEQ, loop)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IRETURN)
            visitLabel(done)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
          }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "searchAny", "(Ljava/util/Collection;)Z", null, null).apply {
          visitCode()
          visitVarInsn(Opcodes.ALOAD, 0)
          visitFieldInsn(Opcodes.GETFIELD, ANIM_SEARCH_INTERNAL, "setToSearch", "Ljava/util/Set;")
          visitVarInsn(Opcodes.ALOAD, 1)
          visitMethodInsn(Opcodes.INVOKESTATIC, ANIM_SEARCH_INTERNAL, "searchSet", "(Ljava/util/Set;Ljava/util/Collection;)Z", false)
          if (compose112Constructor) {
            val matched = Label()
            visitJumpInsn(Opcodes.IFNE, matched)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, ANIM_SEARCH_INTERNAL, "triggerSearch", "Ljava/util/Set;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKESTATIC, ANIM_SEARCH_INTERNAL, "searchSet", "(Ljava/util/Set;Ljava/util/Collection;)Z", false)
            visitInsn(Opcodes.IRETURN)
            visitLabel(matched)
            visitInsn(Opcodes.ICONST_1)
          }
          visitInsn(Opcodes.IRETURN)
          visitMaxs(0, 0)
          visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
      }
    }
  }
}
