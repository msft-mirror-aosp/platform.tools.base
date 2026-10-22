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

package com.android.build.gradle.integration.testing.screenshot

import com.android.build.gradle.integration.common.fixture.project.GradleProject
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import javax.imageio.ImageIO
import kotlin.io.path.listDirectoryEntries
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test as GradleTest
import org.junit.Rule
import org.junit.Test

/**
 * Tests that screenshot test suites render previews like the Android Studio Compose Preview renders static previews, executing the work
 * that the initial composition schedules on the main thread (layoutlib callbacks) before the screenshot is taken.
 *
 * The previews draw solid color boxes, and the tests check the color at the center of the reference images:
 * - [EFFECT_APPLIED_COLOR] once the `LaunchedEffect` or animation of a preview has run (`FF0000` if it had not).
 * - [STATIC_COLOR] for a preview without effects.
 */
class ScreenshotExecuteCallbacksTestSuiteTest {

  @get:Rule
  val rule =
    GradleRule.configure().withProfileOutput().from {
      androidApplication {
        setupScreenshotTestSuiteProjectNoScreenshotTestSource()
        files {
          add("src/main/java/com/CallbacksComposables.kt", CALLBACKS_COMPOSABLES_SOURCE)
          add("src/screenshotTest/java/com/CallbacksTest.kt", CALLBACKS_TEST_SOURCE)
        }
      }
      gradleProperties {
        add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
        add(BooleanOption.TEST_SUITE_SUPPORT, true)
      }
    }

  @Test
  fun launchedEffectsAreRendered() {
    val build = rule.build
    val appProject = build.androidApplication()

    build.updateReferenceImageWithTestSuite()

    // The infinite animation is rendered between the two LaunchedEffect previews, so the second one checks that the work it leaves
    // scheduled on the main thread does not stop the effects of the previews rendered after it.
    assertThat(appProject.referenceImageCenterColor(CALLBACKS_TEST, "aLaunchedEffectTest")).isEqualTo(EFFECT_APPLIED_COLOR)
    assertThat(appProject.referenceImageCenterColor(CALLBACKS_TEST, "cStaticTest")).isEqualTo(STATIC_COLOR)
    assertThat(appProject.referenceImageCenterColor(CALLBACKS_TEST, "dLaunchedEffectTest")).isEqualTo(EFFECT_APPLIED_COLOR)
    assertThat(appProject.referenceImageCenterColor(CALLBACKS_TEST, "eFocusAnimationTest")).isEqualTo(EFFECT_APPLIED_COLOR)
    assertThat(appProject.referenceImageCenterColor(CALLBACKS_TEST, "fTwoRoundSizeEffectTest")).isEqualTo(EFFECT_APPLIED_COLOR)
    assertThat(appProject.referenceImageCenterColor(CALLBACKS_TEST, "gPopupTest")).isEqualTo(EFFECT_APPLIED_COLOR)

    // The images, including the one of the infinite animation, must not change from one run to the next.
    build.validateScreenshotTestWithTestSuite()
  }

  @Test
  fun launchedEffectExceptionsAndBusyMainThreadWorkFailOnlyThoseScreenshots() {
    val build = rule.build {
      androidApplication {
        pluginCallbacks += ShortCallbacksTimeoutCallback::class.java
        files {
          add(THROWING_CALLBACKS_TEST_PATH, THROWING_CALLBACKS_TEST_SOURCE)
          add(BUSY_CALLBACKS_TEST_PATH, BUSY_CALLBACKS_TEST_SOURCE)
        }
      }
    }
    val appProject = build.androidApplication()

    val result = build.updateReferenceImageWithTestSuite(expectFailure = true)

    result.assertErrorContains("Screenshot rendering failed:")
    result.assertErrorContains(THROWN_FROM_LAUNCHED_EFFECT)
    result.assertErrorContains("did not complete within 1500ms")
    // Only the preview whose LaunchedEffect throws or blocks the main thread fails. The previews rendered before and after it are not
    // affected.
    assertThat(appProject.referenceImageNames(THROWING_CALLBACKS_TEST).filter { it.startsWith("bLaunchedEffectThrowsTest_") }).isEmpty()
    assertThat(appProject.referenceImageCenterColor(THROWING_CALLBACKS_TEST, "aLaunchedEffectTest")).isEqualTo(EFFECT_APPLIED_COLOR)
    assertThat(appProject.referenceImageCenterColor(THROWING_CALLBACKS_TEST, "cLaunchedEffectTest")).isEqualTo(EFFECT_APPLIED_COLOR)
    assertThat(appProject.referenceImageNames(BUSY_CALLBACKS_TEST).filter { it.startsWith("bBusyLaunchedEffectTest_") }).isEmpty()
    assertThat(appProject.referenceImageCenterColor(BUSY_CALLBACKS_TEST, "aLaunchedEffectTest")).isEqualTo(EFFECT_APPLIED_COLOR)
    assertThat(appProject.referenceImageCenterColor(BUSY_CALLBACKS_TEST, "cLaunchedEffectTest")).isEqualTo(EFFECT_APPLIED_COLOR)
    assertThat(appProject.referenceImageCenterColor(CALLBACKS_TEST, "aLaunchedEffectTest")).isEqualTo(EFFECT_APPLIED_COLOR)
    assertThat(appProject.referenceImageCenterColor(CALLBACKS_TEST, "cStaticTest")).isEqualTo(STATIC_COLOR)
    assertThat(appProject.referenceImageCenterColor(CALLBACKS_TEST, "dLaunchedEffectTest")).isEqualTo(EFFECT_APPLIED_COLOR)
  }

  class ShortCallbacksTimeoutCallback : GenericCallback {
    override fun handleProject(project: Project) {
      project.tasks.withType(GradleTest::class.java) {
        it.systemProperty("layoutlib.callbacks.timeout.ms", "1500")
      }
    }
  }

  private fun GradleProject<*>.referenceImageNames(testClassFqcn: String): List<String> =
    getTestSuiteReferenceDir(this, testClassFqcn).listDirectoryEntries("*.png").map { it.fileName.toString() }

  /** Returns the RGB color, as a hexadecimal `RRGGBB` string, at the center of the reference image of the preview [methodName]. */
  private fun GradleProject<*>.referenceImageCenterColor(testClassFqcn: String, methodName: String): String {
    val imageFile = getTestSuiteReferenceDir(this, testClassFqcn).listDirectoryEntries("${methodName}_*.png").single()
    val image = ImageIO.read(imageFile.toFile())
    return String.format("%06X", image.getRGB(image.width / 2, image.height / 2) and 0xFFFFFF)
  }

  private companion object {
    const val EFFECT_APPLIED_COLOR = "00FF00"
    const val STATIC_COLOR = "0000FF"
    const val THROWN_FROM_LAUNCHED_EFFECT = "Thrown from LaunchedEffect"

    const val CALLBACKS_TEST = "pkg.name.CallbacksTest"
    const val THROWING_CALLBACKS_TEST = "pkg.name.ThrowingCallbacksTest"
    const val THROWING_CALLBACKS_TEST_PATH = "src/screenshotTest/java/com/ThrowingCallbacksTest.kt"
    const val BUSY_CALLBACKS_TEST = "pkg.name.BusyCallbacksTest"
    const val BUSY_CALLBACKS_TEST_PATH = "src/screenshotTest/java/com/BusyCallbacksTest.kt"

    val CALLBACKS_COMPOSABLES_SOURCE =
      """
      package pkg.name

      import androidx.compose.animation.animateColorAsState
      import androidx.compose.animation.core.tween
      import androidx.compose.foundation.background
      import androidx.compose.foundation.focusable
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.runtime.LaunchedEffect
      import androidx.compose.runtime.getValue
      import androidx.compose.runtime.mutableStateOf
      import androidx.compose.runtime.remember
      import androidx.compose.runtime.setValue
      import androidx.compose.ui.Alignment
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.focus.FocusRequester
      import androidx.compose.ui.focus.focusRequester
      import androidx.compose.ui.focus.onFocusChanged
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.layout.layout
      import androidx.compose.ui.unit.dp
      import androidx.compose.ui.window.Popup

      var infiniteAnimationActive = false
      var infiniteAnimationDisposed = true

      @Composable
      fun ColorBox(color: Color) {
          Box(Modifier.size(40.dp).background(color))
      }

      /**
       * FF0000 (red) until its LaunchedEffect runs, $EFFECT_APPLIED_COLOR (green) after.
       *
       * Note: `dLaunchedEffectTest` is ordered after `bInfiniteAnimationTest` via method-name letter prefixes, so clearing
       * `infiniteAnimationActive` during composition causes any leaked coroutine or undisposed effect from `bInfiniteAnimationTest` to
       * fail `check(infiniteAnimationActive)` if it executes again.
       */
      @Composable
      fun LaunchedEffectBox() {
          infiniteAnimationActive = false
          var color by remember { mutableStateOf(Color.Red) }
          LaunchedEffect(Unit) {
              check(infiniteAnimationDisposed) { "Previous preview composition was not disposed" }
              color = Color.Green
          }
          ColorBox(color)
      }

      /** Requests focus from a LaunchedEffect and animates from red to green over 300ms once focused. */
      @Composable
      fun FocusAnimatedBox() {
          val focusRequester = remember { FocusRequester() }
          var focused by remember { mutableStateOf(false) }
          val color by animateColorAsState(
              targetValue = if (focused) Color.Green else Color.Red,
              animationSpec = tween(durationMillis = 300),
          )
          LaunchedEffect(Unit) { focusRequester.requestFocus() }
          Box(
              Modifier
                  .size(40.dp)
                  .focusRequester(focusRequester)
                  .onFocusChanged { focused = it.isFocused }
                  .focusable()
                  .background(color)
          )
      }

      /**
       * Requires both callback rounds: Round 1's LaunchedEffect attaches a layout modifier, Round 1's render pass measures the layout
       * and triggers that modifier, and Round 2's LaunchedEffect reacts to the measured layout to turn the box green.
       */
      @Composable
      fun TwoRoundLaunchedEffectBox() {
          var readyForLayout by remember { mutableStateOf(false) }
          var layoutMeasured by remember { mutableStateOf(false) }
          var color by remember { mutableStateOf(Color.Red) }
          LaunchedEffect(Unit) { readyForLayout = true }
          LaunchedEffect(layoutMeasured) {
              if (layoutMeasured) {
                  color = Color.Green
              }
          }
          Box(
              if (readyForLayout) {
                  Modifier.layout { measurable, constraints ->
                      val placeable = measurable.measure(constraints)
                      layoutMeasured = true
                      layout(placeable.width, placeable.height) {
                          placeable.place(0, 0)
                      }
                  }
              } else {
                  Modifier
              }
          ) {
              ColorBox(color)
          }
      }

      /** Renders a Popup window over a red box so the center is green only if multi-window Popup roots are rendered and positioned. */
      @Composable
      fun PopupBox() {
          Box(Modifier.size(40.dp).background(Color.Red)) {
              Popup(alignment = Alignment.Center) {
                  Box(Modifier.size(40.dp).background(Color.Green))
              }
          }
      }
      """
        .trimIndent()

    // The methods are prefixed with letters so that they are rendered in the same order whether the engine sorts them by name or keeps the
    // declaration order.
    val CALLBACKS_TEST_SOURCE =
      """
      package pkg.name

      import androidx.compose.material.CircularProgressIndicator
      import androidx.compose.runtime.Composable
      import androidx.compose.runtime.DisposableEffect
      import androidx.compose.runtime.LaunchedEffect
      import androidx.compose.runtime.remember
      import androidx.compose.runtime.withFrameNanos
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.platform.AndroidUiDispatcher
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest
      import kotlinx.coroutines.CoroutineScope
      import kotlinx.coroutines.launch

      class CallbacksTest {
          @PreviewTest
          @Preview
          @Composable
          fun aLaunchedEffectTest() {
              LaunchedEffectBox()
          }

          @PreviewTest
          @Preview
          @Composable
          fun bInfiniteAnimationTest() {
              DisposableEffect(Unit) {
                  infiniteAnimationActive = true
                  infiniteAnimationDisposed = false
                  onDispose {
                      infiniteAnimationDisposed = true
                  }
              }
              remember {
                  CoroutineScope(AndroidUiDispatcher.Main).launch {
                      while (true) {
                          withFrameNanos {}
                          check(infiniteAnimationActive) { "Unscoped coroutine was not cleared when preview finished" }
                      }
                  }
              }
              LaunchedEffect(Unit) {
                  while (true) {
                      withFrameNanos {}
                      check(infiniteAnimationActive) { "LaunchedEffect was not disposed when preview finished" }
                  }
              }
              CircularProgressIndicator()
          }

          @PreviewTest
          @Preview
          @Composable
          fun cStaticTest() {
              ColorBox(Color.Blue)
          }

          @PreviewTest
          @Preview
          @Composable
          fun dLaunchedEffectTest() {
              LaunchedEffectBox()
          }

          @PreviewTest
          @Preview
          @Composable
          fun eFocusAnimationTest() {
              FocusAnimatedBox()
          }

          @PreviewTest
          @Preview
          @Composable
          fun fTwoRoundSizeEffectTest() {
              TwoRoundLaunchedEffectBox()
          }

          @PreviewTest
          @Preview
          @Composable
          fun gPopupTest() {
              PopupBox()
          }
      }
      """
        .trimIndent()

    val THROWING_CALLBACKS_TEST_SOURCE =
      """
      package pkg.name

      import androidx.compose.runtime.Composable
      import androidx.compose.runtime.LaunchedEffect
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest

      class ThrowingCallbacksTest {
          @PreviewTest
          @Preview
          @Composable
          fun aLaunchedEffectTest() {
              LaunchedEffectBox()
          }

          @PreviewTest
          @Preview
          @Composable
          fun bLaunchedEffectThrowsTest() {
              LaunchedEffect(Unit) { throw IllegalStateException("$THROWN_FROM_LAUNCHED_EFFECT") }
              ColorBox(Color.Blue)
          }

          @PreviewTest
          @Preview
          @Composable
          fun cLaunchedEffectTest() {
              LaunchedEffectBox()
          }
      }
      """
        .trimIndent()

    // bBusyLaunchedEffectTest waits until the last callback execution of the first round and then keeps the main thread busy, ignoring
    // interruptions, for longer than the renderer allows the main thread work of a preview to run (configured to 1.5s via
    // -Dlayoutlib.callbacks.timeout.ms=1500).
    val BUSY_CALLBACKS_TEST_SOURCE =
      """
      package pkg.name

      import androidx.compose.runtime.Composable
      import androidx.compose.runtime.LaunchedEffect
      import androidx.compose.runtime.withFrameNanos
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest

      class BusyCallbacksTest {
          @PreviewTest
          @Preview
          @Composable
          fun aLaunchedEffectTest() {
              LaunchedEffectBox()
          }

          @PreviewTest
          @Preview
          @Composable
          fun bBusyLaunchedEffectTest() {
              LaunchedEffect(Unit) {
                  // Advance through the first 9 of StaticPreviewCallbackRunner.CALLBACK_EXECUTIONS_PER_ROUND (10) steps so the busy wait
                  // runs on the 10th and final callback step of the first round.
                  repeat(9) { withFrameNanos {} }
                  val timeoutMs = java.lang.Long.getLong("layoutlib.callbacks.timeout.ms", 10_000L)
                  val end = System.nanoTime() + (timeoutMs + 1_500L) * 1_000_000L
                  while (System.nanoTime() < end) {
                      // Busy wait.
                  }
                  repeat(2) { withFrameNanos {} }
                  throw IllegalStateException("LaunchedEffect of timed-out preview was not disposed")
              }
              ColorBox(Color.Blue)
          }

          @PreviewTest
          @Preview
          @Composable
          fun cLaunchedEffectTest() {
              LaunchedEffectBox()
          }
      }
      """
        .trimIndent()
  }
}
