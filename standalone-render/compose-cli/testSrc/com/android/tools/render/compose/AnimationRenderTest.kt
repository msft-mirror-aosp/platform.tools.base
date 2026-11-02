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

package com.android.tools.render.compose

import com.android.tools.render.animation.AnimationRenderOptions
import com.android.tools.render.animation.readAnimationManifestFromJson
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.StandalonePreviewRenderer
import com.android.tools.render.common.readComponentTreeFromJson
import com.android.tools.render.common.readPreviewRenderingJson
import com.android.tools.render.common.readPreviewRenderingResultJson
import com.android.tools.render.common.writePreviewRenderingToJson
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder

/**
 * Steps the `PreviewAnimationClock` of real previews of `testData/compose-application` with [StandalonePreviewRenderer.renderAnimations].
 */
class AnimationRenderTest {
  private val tmpFolder = TemporaryFolder()
  private val gradleProject =
    GradleProjectRule(
      tmpFolder,
      "tools/base/standalone-render/compose-cli/testData/compose-application",
      "tools/external/gradle/gradle-9.5.0-bin.zip",
      "prebuilts/studio/jdk/jbr25/linux",
    )

  @JvmField @Rule val chain: RuleChain = RuleChain.outerRule(tmpFolder).around(gradleProject)

  @After
  fun tearDown() {
    // Release the warm Renderer / ModuleClassLoader for this test's temporary project without disposing the shared framework.
    try {
      StandalonePreviewRenderer.closeWarmRenderer()
    } finally {
      // Stop the daemon otherwise it could keep the lock on the temporary folder
      gradleProject.executeGradleTask("--stop")
    }
  }

  /**
   * Steps and detects the animations of real previews, in a single Gradle build of the test project:
   * - The size animation of an `AnimatedVisibility(expandVertically)` child depends on the size of its content, which is only measured once
   *   the content is composed: its curve must go from an empty height to the measured one, not stay empty all along.
   * - What an agent needs without an IDE or a device (see [checkAnimationsAreDetectedAndKeyframesPeakIsCaptured]).
   */
  @Test
  fun stepsAndDetectsTheAnimationsOfRealPreviews() {
    val screenshots =
      listOf(
        ComposeScreenshot(
          "com.example.composeapplication.AnimationsKt.PreviewExpandVertically",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.AnimationsKt.PreviewExpandVertically_screenshot",
        ),
        ComposeScreenshot(
          "com.example.composeapplication.AnimationsKt.PreviewTransitionAnimatedVisibility",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.AnimationsKt.PreviewTransitionAnimatedVisibility_screenshot",
        ),
        ComposeScreenshot(
          "com.example.composeapplication.AnimationsKt.PreviewHiddenAnimatedVisibility",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.AnimationsKt.PreviewHiddenAnimatedVisibility_screenshot",
        ),
        ComposeScreenshot(
          "com.example.composeapplication.AnimationsKt.PreviewKeyframesPeak",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.AnimationsKt.PreviewKeyframesPeak_screenshot",
        ),
        ComposeScreenshot(
          "com.example.composeapplication.PreviewsKt.PreviewSmall",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.PreviewsKt.PreviewSmall_screenshot",
        ),
      )
    val outputFolder = tmpFolder.newFolder()
    val rendering =
      readPreviewRenderingJson(
        gradleProject
          .createSettingsFile(outputFolder, tmpFolder.newFile("results.json"), tmpFolder.newFolder(), screenshots)
          .bufferedReader()
      )
    val renderer =
      StandalonePreviewRenderer(
        fontsPath = rendering.fontsPath,
        resourceApkPath = rendering.resourceApkPath,
        namespace = rendering.namespace,
        classPath = rendering.classPath,
        projectClassPath = rendering.projectClassPath,
        layoutlibPath = rendering.layoutlibPath,
        resourceDirs = rendering.resourceDirs,
        rClassJars = rendering.rClassJars,
      )

    val result =
      renderer.renderAnimations(
        listOf(rendering.screenshots[0]),
        outputFolder.absolutePath,
        AnimationRenderOptions(frameCount = 8, fromState = "false", toState = "true"),
      )

    assertThat(result.globalError).isNull()
    val animation = result.animationResults.single()
    assertThat(animation.error).isNull()
    val manifest = checkNotNull(animation.manifest)
    assertThat(manifest.detectedAnimations.map { it.type }).contains("ANIMATED_VISIBILITY")
    val expand = manifest.animations.flatMap { it.tracks }.single { it.propertyLabel == "Built-in shrink/expand" }
    val (initialWidth, initialHeight) = expand.initialValue.split("x").map { it.toInt() }
    val (targetWidth, targetHeight) = expand.targetValue.split("x").map { it.toInt() }
    assertThat(initialHeight).isEqualTo(0)
    assertWithMessage("The expanded height must be measured: ${expand.targetValue}").that(targetHeight).isGreaterThan(0)
    assertThat(targetWidth).isEqualTo(initialWidth)
    // Its spec is its own tween, not one of another track with the same label.
    assertThat(expand.spec?.specType).isEqualTo("TweenSpec")
    assertThat(expand.spec?.durationMillis).isEqualTo(500)
    // The built-in tracks of the AnimatedVisibility are named after the composable it shows.
    assertThat(expand.displayLabel).isEqualTo("Legend · expand")
    val fade = manifest.animations.flatMap { it.tracks }.single { it.propertyLabel == "Built-in alpha" }
    assertThat(fade.displayLabel).isEqualTo("Legend · fade in")
    assertThat(manifest.animations.flatMap { it.tracks }.single { it.propertyLabel == "height" }.displayLabel).isNull()

    // Both a Transition that only drives a child AnimatedVisibility and a top-level AnimatedVisibility(visible = false)
    // must flush snapshot state via setClockTime(0L) and compose/measure their child transition tracks without explicit fromState/toState.
    val implicitStateFolder = tmpFolder.newFolder()
    val implicitResult =
      renderer.renderAnimations(
        rendering.screenshots.subList(1, 3),
        implicitStateFolder.absolutePath,
        AnimationRenderOptions(frameCount = 6),
      )
    assertThat(implicitResult.globalError).isNull()
    assertThat(implicitResult.animationResults).hasSize(2)
    for (animRes in implicitResult.animationResults) {
      assertThat(animRes.error).isNull()
      val m = checkNotNull(animRes.manifest)
      val childExpand = m.animations.flatMap { it.tracks }.single { it.propertyLabel == "Built-in shrink/expand" }
      val (_, measuredHeight) = childExpand.targetValue.split("x").map { it.toInt() }
      assertWithMessage("Child AnimatedVisibility height must be measured for ${animRes.previewId}").that(measuredHeight).isGreaterThan(0)
    }

    checkAnimationsAreDetectedAndKeyframesPeakIsCaptured(renderer, rendering, rendering.screenshots.subList(3, 5))
  }

  /**
   * What an agent needs without an IDE or a device, for [screenshots] (`PreviewKeyframesPeak` and the static `PreviewSmall`):
   * - Component trees with `detectAnimations` tell animated previews (`hasAnimations: true` and their `detectedAnimations`) from static
   *   ones (`hasAnimations: false`), e.g. to find the animations or animation opportunities of a project.
   * - The height of `PreviewKeyframesPeak` peaks at 50% of its 400 ms `keyframes` animation: both its curve (`tracks[].points`) and the
   *   adaptively placed frames capture that peak, e.g. to verify "make the height peak at 50% of the animation".
   * - Explicit `atMillis` / `--animation-at-ms` captures frame PNGs at exact requested timestamps (sorted and deduplicated) while keeping
   *   full `tracks[].points` curves populated.
   */
  private fun checkAnimationsAreDetectedAndKeyframesPeakIsCaptured(
    renderer: StandalonePreviewRenderer,
    baseRendering: com.android.tools.render.common.PreviewRendering,
    screenshots: List<PreviewScreenshot>,
  ) {
    val outputFolder = tmpFolder.newFolder()
    val treeFolder = tmpFolder.newFolder()
    val trees =
      renderer.render(
        screenshots = screenshots,
        outputFolder = treeFolder.absolutePath,
        includeComponentTree = true,
        renderImage = false,
        detectAnimations = true,
      )
    assertThat(trees.globalError).isNull()
    val treesByPreview =
      trees.screenshotResults.associate { result ->
        result.methodFQN.substringAfterLast('.') to
          File(treeFolder, checkNotNull(result.componentTreePath)).bufferedReader().use { readComponentTreeFromJson(it) }
      }
    val animatedTree = treesByPreview.getValue("PreviewKeyframesPeak")
    assertThat(animatedTree.hasAnimations).isTrue()
    assertThat(animatedTree.animationCount).isEqualTo(1)
    val detected = checkNotNull(animatedTree.detectedAnimations).single()
    assertThat(detected.type).isEqualTo("TRANSITION")
    assertThat(detected.label).isEqualTo("peak")
    assertThat(detected.sourceLocation?.fileName).isEqualTo("Animations.kt")
    val staticTree = treesByPreview.getValue("PreviewSmall")
    assertThat(staticTree.hasAnimations).isFalse()
    assertThat(staticTree.animationCount).isEqualTo(0)
    assertThat(staticTree.detectedAnimations).isEmpty()
    assertThat(staticTree.components).isNotEmpty()

    val result =
      renderer.renderAnimations(
        screenshots.filter { it.methodFQN.endsWith(".PreviewKeyframesPeak") },
        outputFolder.absolutePath,
        AnimationRenderOptions(frameCount = 24, fromState = "false", toState = "true"),
      )

    assertThat(result.globalError).isNull()
    val animation = result.animationResults.single()
    assertThat(animation.error).isNull()
    val manifest = checkNotNull(animation.manifest)
    assertThat(manifest.effectiveDurationMillis).isEqualTo(400L)
    val height = manifest.animations.flatMap { it.tracks }.single { it.propertyLabel == "height" }
    assertThat(height.spec?.specType).isEqualTo("KeyframesSpec")
    assertThat(height.spec?.durationMillis).isEqualTo(400)
    // The curve peaks at 120.dp, at 50% of the animation (its points are about 13 ms apart).
    val peakPoint = height.points.maxBy { checkNotNull(it.numericValue) }
    assertThat(peakPoint.timeMillis.toDouble()).isWithin(14.0).of(200.0)
    assertThat(peakPoint.numericValue).isWithin(7.0).of(120.0)
    assertThat(height.points.last().numericValue).isWithin(0.5).of(48.0)

    // The frames are at most 16 ms away from the peak, and the tallest rendered frame (the bar is the whole preview) is the one at the
    // peak.
    assertThat(manifest.frames.map { it.timeMillis }.minOf { abs(it - 200L) }).isAtMost(16L)
    val frameHeights = manifest.frames.associate { it.timeMillis to ImageIO.read(File(outputFolder, it.imagePath)).height }
    val tallestFrameTime = frameHeights.maxBy { it.value }.key
    assertThat(tallestFrameTime.toDouble()).isWithin(16.0).of(200.0)
    assertThat(frameHeights.getValue(tallestFrameTime)).isGreaterThan(frameHeights.getValue(400L))
    assertThat(frameHeights.getValue(400L)).isGreaterThan(frameHeights.getValue(0L))

    // Explicit `atMillis` via both `StandalonePreviewRenderer.renderAnimations` and CLI `--animation-at-ms`:
    // captures exact requested timestamps (sorted & deduplicated) while still populating full `tracks[].points` curves.
    val peakScreenshots = screenshots.filter { it.methodFQN.endsWith(".PreviewKeyframesPeak") }
    val explicitApiFolder = tmpFolder.newFolder()
    val explicitApiResult =
      renderer.renderAnimations(
        peakScreenshots,
        explicitApiFolder.absolutePath,
        AnimationRenderOptions(atMillis = listOf(400L, 0L, 200L, 200L), fromState = "false", toState = "true"),
      )
    assertThat(explicitApiResult.globalError).isNull()
    val explicitManifest = checkNotNull(explicitApiResult.animationResults.single().manifest)
    assertThat(explicitManifest.frames.map { it.timeMillis }).containsExactly(0L, 200L, 400L).inOrder()
    assertThat(explicitManifest.frameCount).isEqualTo(3)
    val explicitCurvePoints = explicitManifest.animations.flatMap { it.tracks }.single { it.propertyLabel == "height" }.points
    assertThat(explicitCurvePoints.size).isGreaterThan(10)
    val explicitHeights = explicitManifest.frames.associate { it.timeMillis to ImageIO.read(File(explicitApiFolder, it.imagePath)).height }
    assertThat(explicitHeights.getValue(200L)).isGreaterThan(explicitHeights.getValue(400L))
    assertThat(explicitHeights.getValue(400L)).isGreaterThan(explicitHeights.getValue(0L))

    val cliOutputFolder = tmpFolder.newFolder()
    val cliResultsFile = tmpFolder.newFile("cli-anim-results.json")
    val cliSettingsFile = tmpFolder.newFile("cli-anim-settings.json")
    cliSettingsFile.bufferedWriter().use {
      writePreviewRenderingToJson(
        it,
        baseRendering.copy(
          screenshots = peakScreenshots,
          outputFolder = cliOutputFolder.absolutePath,
          resultsFilePath = cliResultsFile.absolutePath,
        ),
      )
    }
    val cliStdout = runComposeCliRender(listOf("--animation-at-ms=400,0,200,200", cliSettingsFile.absolutePath))
    val cliLines = cliStdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val cliManifestFile = File(cliLines.first { it.endsWith(".animation.json") })
    assertThat(cliManifestFile.isFile).isTrue()
    val cliManifest = cliManifestFile.bufferedReader().use { readAnimationManifestFromJson(it) }
    assertThat(cliManifest.frames.map { it.timeMillis }).containsExactly(0L, 200L, 400L).inOrder()
    assertThat(cliManifest.animations.flatMap { it.tracks }.single { it.propertyLabel == "height" }.points.size).isGreaterThan(10)
    val cliResultSummary = cliResultsFile.bufferedReader().use { readPreviewRenderingResultJson(it) }
    assertThat(cliResultSummary.globalError).isNull()
    assertThat(cliResultSummary.screenshotResults.single().error).isNull()
  }
}
