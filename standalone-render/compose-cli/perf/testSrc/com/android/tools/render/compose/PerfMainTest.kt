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
import com.android.tools.render.common.readPreviewRenderingResultJson
import java.nio.file.Paths
import javax.imageio.ImageIO
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder

@Ignore("b/441854224")
class PerfMainTest {
  private val tmpFolder = TemporaryFolder()
  private val gradleProject =
    GradleProjectRule(
      tmpFolder,
      "tools/base/standalone-render/compose-cli/testData/compose-application",
      "tools/external/gradle/gradle-8.2-bin.zip",
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
        )
      }
      metric
    }

    // Stop the daemon otherwise it could keep the lock on the temporary folder
    gradleProject.executeGradleTask("--stop")
  }
}
