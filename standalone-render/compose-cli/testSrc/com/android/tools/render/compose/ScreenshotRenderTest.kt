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
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder

class ScreenshotRenderTest {

  private val tmpFolder = TemporaryFolder()
  private val gradleProject =
    GradleProjectRule(
      tmpFolder,
      "tools/base/standalone-render/compose-cli/testData/compose-application",
      "tools/external/gradle/gradle-9.5.0-bin.zip",
      "prebuilts/studio/jdk/jbr-next/linux",
    )

  @JvmField @Rule val chain: RuleChain = RuleChain.outerRule(tmpFolder).around(gradleProject)

  @Ignore("b/440394932")
  @Test
  fun testCororutinePreview() {
    val screenshots =
      listOf(
        ComposeScreenshot(
          "com.example.composeapplication.PreviewsKt.PreviewCoroutines",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.PreviewsKt.PreviewCoroutines_screenshot",
        )
      )
    commonTest(screenshots, "small")
  }

  @Test
  fun testSmallPreview() {
    val screenshots =
      listOf(
        ComposeScreenshot(
          "com.example.composeapplication.PreviewsKt.PreviewSmall",
          emptyList(),
          emptyMap(),
          "com.example.composeapplication.PreviewsKt.PreviewSmall_screenshot",
        )
      )
    commonTest(screenshots, "small", 2.0)
  }

  @Test
  fun testHelp() {
    val stdout = runComposeCliRender(listOf("--help"))
    assertTrue(stdout.contains("Usage: compose-preview-renderer"))
    assertTrue(stdout.contains("Options:"))
    assertTrue(stdout.contains("JSON Settings File Format:"))
    assertTrue(stdout.contains("Example JSON:"))
  }

  private fun commonTest(screenshots: List<ComposeScreenshot>, goldenName: String, maxPercentDifferent: Double = 0.0) {
    val outputFolder = tmpFolder.newFolder()
    val metaDatafolder = tmpFolder.newFolder()
    val resultsFile = tmpFolder.newFile("results.json")
    val jsonSettings = gradleProject.createSettingsFile(outputFolder, resultsFile, metaDatafolder, screenshots)

    val stdout = runComposeCliRender(jsonSettings)

    screenshots.forEach { screenshot ->
      val imageName = "${screenshot.previewId.substringAfterLast(".")}_0.png"
      val relativeImagePath = (screenshot.methodFQN.substringBeforeLast(".").replace(".", "/")) + "/" + imageName
      val expectedAbsolutePath = File(outputFolder, relativeImagePath).absolutePath
      assertTrue("Expected stdout to contain $expectedAbsolutePath, but was:\n$stdout", stdout.contains(expectedAbsolutePath))
    }

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
        maxPercentDifferent,
      )
    }

    // Stop the daemon otherwise it could keep the lock on the temporary folder
    gradleProject.executeGradleTask("--stop")
  }
}
