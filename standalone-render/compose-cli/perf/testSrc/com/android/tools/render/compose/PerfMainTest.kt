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

package com.android.tools.render.compose

import com.android.testutils.ImageDiffUtil
import com.android.testutils.TestUtils
import com.android.tools.render.common.StandalonePreviewRenderer
import com.android.tools.render.common.readPreviewRenderingJson
import com.android.tools.render.common.readPreviewRenderingResultJson
import java.nio.file.Paths
import javax.imageio.ImageIO
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder

class PerfMainTest {
  private val tmpFolder = TemporaryFolder()
  private val gradleProject =
    GradleProjectRule(
      tmpFolder,
      "tools/base/standalone-render/compose-cli/testData/compose-application",
      "tools/external/gradle/gradle-9.5.0-bin.zip",
      "prebuilts/studio/jdk/jbr25/linux",
    )

  @JvmField @Rule val chain: RuleChain = RuleChain.outerRule(tmpFolder).around(gradleProject)

  @Test
  fun testSingleSmall() {
    val screenshots =
      listOf(
        ComposeScreenshot(
          "com.example.composeapplication.PreviewsKt.PreviewSmall",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.PreviewsKt.PreviewSmall_screenshot",
        )
      )
    commonTest(screenshots, "compose_cli_render_time_1_small", "compose_cli_render_memory_1_small", "small")
  }

  @Test
  fun test10Small() {
    val screenshots =
      (0..9).map {
        ComposeScreenshot(
          "com.example.composeapplication.PreviewsKt.PreviewSmall",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.PreviewsKt.PreviewSmall_screenshot${it}",
        )
      }
    commonTest(screenshots, "compose_cli_render_time_10_small", "compose_cli_render_memory_10_small", "small")
  }

  @Test
  fun test40Small() {
    val screenshots =
      (0..39).map {
        ComposeScreenshot(
          "com.example.composeapplication.PreviewsKt.PreviewSmall",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.PreviewsKt.PreviewSmall_screenshot${it}",
        )
      }
    commonTest(screenshots, "compose_cli_render_time_40_small", "compose_cli_render_memory_40_small", "small")
  }

  @Test
  fun testSingleLarge() {
    val screenshots =
      listOf(
        ComposeScreenshot(
          "com.example.composeapplication.PreviewsKt.PreviewLarge",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.PreviewsKt.PreviewLarge_screenshot",
        )
      )
    commonTest(screenshots, "compose_cli_render_time_1_large", "compose_cli_render_memory_1_large", "large")
  }

  @Test
  fun test10Large() {
    val screenshots =
      (0..9).map {
        ComposeScreenshot(
          "com.example.composeapplication.PreviewsKt.PreviewLarge",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.PreviewsKt.PreviewLarge_screenshot${it}",
        )
      }
    commonTest(screenshots, "compose_cli_render_time_10_large", "compose_cli_render_memory_10_large", "large")
  }

  @Test
  fun test40Large() {
    val screenshots =
      (0..39).map {
        ComposeScreenshot(
          "com.example.composeapplication.PreviewsKt.PreviewLarge",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.PreviewsKt.PreviewLarge_screenshot${it}",
        )
      }
    commonTest(screenshots, "compose_cli_render_time_40_large", "compose_cli_render_memory_40_large", "large")
  }

  /**
   * 10 large previews rendered with their component trees, as `android preview render --component-tree` does on a warm daemon. In this
   * process (unlike the CLI of [commonTest]), so 10 previews per render keep it within the heap of the test.
   */
  @Test
  fun test10LargeComponentTree() {
    componentTreeTest(
      largeScreenshots(10),
      detectAnimations = false,
      "compose_cli_component_tree_time_10_large",
      "compose_cli_component_tree_memory_10_large",
    )
  }

  /** The same with `--detect-animations`: the component trees also report the animations found in the slot table. */
  @Test
  fun test10LargeComponentTreeDetectAnimations() {
    componentTreeTest(
      largeScreenshots(10),
      detectAnimations = true,
      "compose_cli_component_tree_detect_animations_time_10_large",
      "compose_cli_component_tree_detect_animations_memory_10_large",
    )
  }

  /** 10 animated previews rendered with their component trees (`--detect-animations` off vs on). */
  @Test
  fun test10AnimatedComponentTree() {
    componentTreeTest(
      animatedScreenshots(10),
      detectAnimations = false,
      "compose_cli_component_tree_time_10_animated",
      "compose_cli_component_tree_memory_10_animated",
    )
  }

  @Test
  fun test10AnimatedComponentTreeDetectAnimations() {
    componentTreeTest(
      animatedScreenshots(10),
      detectAnimations = true,
      "compose_cli_component_tree_detect_animations_time_10_animated",
      "compose_cli_component_tree_detect_animations_memory_10_animated",
    )
  }

  private fun largeScreenshots(count: Int) =
    (0 until count).map {
      ComposeScreenshot(
        "com.example.composeapplication.PreviewsKt.PreviewLarge",
        emptyList(),
        emptyMap(),
        "com.example.composeapplication.PreviewsKt.PreviewLarge_screenshot${it}",
      )
    }

  private fun animatedScreenshots(count: Int) =
    (0 until count).map {
      ComposeScreenshot(
        "com.example.composeapplication.AnimationsKt.PreviewExpandVertically",
        emptyList(),
        emptyMap(),
        "com.example.composeapplication.AnimationsKt.PreviewExpandVertically_screenshot${it}",
      )
    }

  /**
   * Renders [screenshots] with their component trees in this process, with the warm [StandalonePreviewRenderer] the renderer daemon uses,
   * so that the metric measures the renders (and the component tree extraction) rather than the startup of the CLI.
   */
  private fun componentTreeTest(
    screenshots: List<ComposeScreenshot>,
    detectAnimations: Boolean,
    timeMetricName: String,
    memoryMetricName: String,
  ) {
    val outputFolder = tmpFolder.newFolder()
    val metaDataFolder = tmpFolder.newFolder()
    val resultsFile = tmpFolder.newFile("results.json")
    val rendering =
      readPreviewRenderingJson(gradleProject.createSettingsFile(outputFolder, resultsFile, metaDataFolder, screenshots).bufferedReader())
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
    try {
      computeAndRecordMetric(timeMetricName, memoryMetricName) {
        val metric = ComposeRenderingMetric()
        metric.beforeTest()
        val result =
          renderer.render(
            rendering.screenshots,
            outputFolder.absolutePath,
            includeComponentTree = true,
            renderImage = true,
            reuseEnvironment = true,
            detectAnimations = detectAnimations,
          )
        metric.afterTest()
        assertNull(result.globalError)
        result.screenshotResults.forEach {
          assertNull(it.error)
          assertNotNull(it.componentTreePath)
        }
        metric
      }
    } finally {
      // Not StandalonePreviewRenderer.disposeFramework(): the framework is shared by the other tests of this process.
      // Stop the daemon otherwise it could keep the lock on the temporary folder
      gradleProject.executeGradleTask("--stop")
    }
  }

  private fun commonTest(screenshots: List<ComposeScreenshot>, timeMetricName: String, memoryMetricName: String, goldenName: String) {
    val outputFolder = tmpFolder.newFolder()
    val metaDatafolder = tmpFolder.newFolder()
    val resultsFile = tmpFolder.newFile("results.json")
    val jsonSettings = gradleProject.createSettingsFile(outputFolder, resultsFile, metaDatafolder, screenshots)

    computeAndRecordMetric(timeMetricName, memoryMetricName) {
      val metric = ComposeRenderingMetric()
      metric.beforeTest()
      runComposeCliRender(jsonSettings)
      metric.afterTest()
      val result = readPreviewRenderingResultJson(resultsFile.bufferedReader())
      assertNull(result.globalError)
      result.screenshotResults.forEach { assertNull(it.error) }
      screenshots.forEach { screenshot ->
        val imageName = "${screenshot.previewId.substringAfterLast(".")}_0.png"
        val relativeImagePath = (screenshot.methodFQN.substringBeforeLast(".").replace(".", "/")) + "/" + imageName
        val imageFile = Paths.get(outputFolder.absolutePath, relativeImagePath).toFile()
        val img = ImageIO.read(imageFile)
        ImageDiffUtil.assertImageSimilar(
          TestUtils.resolveWorkspacePathUnchecked("tools/base/standalone-render/compose-cli/testData/goldens/$goldenName.png"),
          img,
          2.0,
        )
      }
      metric
    }

    // Stop the daemon otherwise it could keep the lock on the temporary folder
    gradleProject.executeGradleTask("--stop")
  }
}
