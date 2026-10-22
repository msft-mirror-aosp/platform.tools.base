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
import com.android.tools.render.common.ComponentTreeNode
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.render.common.ScreenshotError
import com.android.tools.render.common.StandalonePreviewRenderer
import com.android.tools.render.common.readComponentTreeFromJson
import com.android.tools.render.common.readPreviewRenderingJson
import com.android.tools.render.common.readPreviewRenderingResultJson
import com.android.tools.render.common.writePreviewRenderingToJson
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
      "prebuilts/studio/jdk/jbr25/linux",
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

  @Test
  fun testPreviewsExecuteCallbacksWithoutAffectingEachOther() {
    // Previews are rendered like Android Studio renders static previews, executing layoutlib callbacks between renders. All these
    // previews share Compose's main dispatcher (static, per class loader) and layoutlib's Choreographer:
    // - PreviewLaunchedEffect only looks like PreviewSmall once its LaunchedEffect has run.
    // - The infinite animation leaves work scheduled on the dispatcher. That must not stop the coroutines of the previews rendered after
    // it.
    // - The exception thrown by a LaunchedEffect must fail that screenshot only.
    // - A LaunchedEffect that keeps the main thread busy for too long must fail that screenshot with a clear error, and not the previews
    // rendered after it.
    // - The virtual time keeps increasing across previews, but the image of an animated preview must not depend on its position in the run.
    // They are checked in a single test because each test builds the test project with Gradle, which is slow.
    val screenshots =
      listOf(
        smallScreenshot,
        infiniteAnimationScreenshot,
        launchedEffectScreenshot,
        launchedEffectThrowsScreenshot,
        launchedEffectBusyScreenshot,
        twoRoundLaunchedEffectScreenshot,
      )
    val outputFolder = tmpFolder.newFolder()
    val resultsFile = tmpFolder.newFile("results.json")
    val jsonSettings = gradleProject.createSettingsFile(outputFolder, resultsFile, tmpFolder.newFolder(), screenshots)

    runComposeCliRender(
      jsonSettings,
      expectRenderErrors = true,
      jvmArgs = listOf("-Dlayoutlib.callbacks.timeout.ms=1500"),
    )

    val result = readPreviewRenderingResultJson(resultsFile.bufferedReader())
    assertNull(result.globalError)
    val resultsByMethod = result.screenshotResults.associateBy { it.methodFQN }
    assertEquals(screenshots.size, resultsByMethod.size)
    assertErrorReported(resultsByMethod.getValue(launchedEffectThrowsScreenshot.methodFQN).error, "Thrown from LaunchedEffect")
    assertErrorReported(resultsByMethod.getValue(launchedEffectBusyScreenshot.methodFQN).error, "did not complete within 1500ms")
    (screenshots - launchedEffectThrowsScreenshot - launchedEffectBusyScreenshot).forEach {
      assertNull(
        "Unexpected error for ${it.methodFQN}: ${resultsByMethod.getValue(it.methodFQN).error}",
        resultsByMethod.getValue(it.methodFQN).error,
      )
    }
    ImageDiffUtil.assertImageSimilar(
      launchedEffectScreenshot.methodFQN,
      readImage(outputFolder, resultsByMethod.getValue(smallScreenshot.methodFQN)),
      readImage(outputFolder, resultsByMethod.getValue(launchedEffectScreenshot.methodFQN)),
      0.0,
    )
    ImageDiffUtil.assertImageSimilar(
      twoRoundLaunchedEffectScreenshot.methodFQN,
      readImage(outputFolder, resultsByMethod.getValue(smallScreenshot.methodFQN)),
      readImage(outputFolder, resultsByMethod.getValue(twoRoundLaunchedEffectScreenshot.methodFQN)),
      0.0,
    )

    // Render the infinite animation alone, reusing the same settings, and compare it with the image rendered after other previews.
    val aloneOutputFolder = tmpFolder.newFolder()
    val aloneResultsFile = tmpFolder.newFile("results-alone.json")
    val aloneSettings = tmpFolder.newFile("settings-alone.json")
    val aloneRendering =
      readPreviewRenderingJson(jsonSettings.bufferedReader())
        .copy(
          screenshots = listOf(infiniteAnimationScreenshot),
          outputFolder = aloneOutputFolder.absolutePath,
          resultsFilePath = aloneResultsFile.absolutePath,
        )
    aloneSettings.bufferedWriter().use { writePreviewRenderingToJson(it, aloneRendering) }
    runComposeCliRender(aloneSettings)
    val aloneResult = readPreviewRenderingResultJson(aloneResultsFile.bufferedReader())
    assertNull(aloneResult.globalError)
    aloneResult.screenshotResults.forEach { assertNull(it.error) }
    ImageDiffUtil.assertImageSimilar(
      infiniteAnimationScreenshot.methodFQN,
      readImage(aloneOutputFolder, aloneResult.screenshotResults.single()),
      readImage(outputFolder, resultsByMethod.getValue(infiniteAnimationScreenshot.methodFQN)),
      0.0,
    )

    // Stop the daemon otherwise it could keep the lock on the temporary folder
    gradleProject.executeGradleTask("--stop")
  }

  private fun assertErrorReported(error: ScreenshotError?, expectedMessage: String) {
    assertNotNull("Expected a screenshot error containing \"$expectedMessage\"", error)
    assertTrue(
      "Expected \"$expectedMessage\" in the reported problems, but was: $error",
      error!!.problems.any { it.html.contains(expectedMessage) || it.stackTrace.orEmpty().contains(expectedMessage) },
    )
  }

  private val smallScreenshot =
    ComposeScreenshot(
      "com.example.composeapplication.PreviewsKt.PreviewSmall",
      emptyList(),
      emptyMap(),
      "com.example.composeapplication.PreviewsKt.PreviewSmall_screenshot",
    )

  private val launchedEffectScreenshot =
    ComposeScreenshot(
      "com.example.composeapplication.PreviewsKt.PreviewLaunchedEffect",
      emptyList(),
      emptyMap(),
      "com.example.composeapplication.PreviewsKt.PreviewLaunchedEffect_screenshot",
    )

  private val twoRoundLaunchedEffectScreenshot =
    ComposeScreenshot(
      "com.example.composeapplication.PreviewsKt.PreviewTwoRoundLaunchedEffect",
      emptyList(),
      emptyMap(),
      "com.example.composeapplication.PreviewsKt.PreviewTwoRoundLaunchedEffect_screenshot",
    )

  private val launchedEffectThrowsScreenshot =
    ComposeScreenshot(
      "com.example.composeapplication.PreviewsKt.PreviewLaunchedEffectThrows",
      emptyList(),
      emptyMap(),
      "com.example.composeapplication.PreviewsKt.PreviewLaunchedEffectThrows_screenshot",
    )

  private val launchedEffectBusyScreenshot =
    ComposeScreenshot(
      "com.example.composeapplication.PreviewsKt.PreviewLaunchedEffectBusy",
      emptyList(),
      emptyMap(),
      "com.example.composeapplication.PreviewsKt.PreviewLaunchedEffectBusy_screenshot",
    )

  private val infiniteAnimationScreenshot =
    ComposeScreenshot(
      "com.example.composeapplication.PreviewsKt.PreviewInfiniteAnimation",
      emptyList(),
      emptyMap(),
      "com.example.composeapplication.PreviewsKt.PreviewInfiniteAnimation_screenshot",
    )

  private fun readImage(outputFolder: File, screenshotResult: PreviewScreenshotResult): BufferedImage {
    return ImageIO.read(Paths.get(outputFolder.absolutePath, screenshotResult.imagePath).toFile())
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

    // Verify inflate-only component tree extraction against the real Compose runtime
    val treeOutputFolder = tmpFolder.newFolder()
    val previewRendering = readPreviewRenderingJson(jsonSettings.bufferedReader())
    val standaloneRenderer =
      StandalonePreviewRenderer(
        fontsPath = previewRendering.fontsPath,
        resourceApkPath = previewRendering.resourceApkPath,
        namespace = previewRendering.namespace,
        classPath = previewRendering.classPath,
        projectClassPath = previewRendering.projectClassPath,
        layoutlibPath = previewRendering.layoutlibPath,
      )
    val treeRenderResult =
      standaloneRenderer.render(
        screenshots = screenshots,
        outputFolder = treeOutputFolder.absolutePath,
        includeComponentTree = true,
        renderImage = false,
      )
    assertNull(treeRenderResult.globalError)
    assertEquals(screenshots.size, treeRenderResult.screenshotResults.size)
    treeRenderResult.screenshotResults.forEach { screenshotResult ->
      assertNull(screenshotResult.error)
      assertEquals("", screenshotResult.imagePath)
      assertNotNull(screenshotResult.componentTreePath)
      val treeFile = File(treeOutputFolder, screenshotResult.componentTreePath!!)
      assertTrue(treeFile.exists())
      assertFalse(File(treeOutputFolder, screenshotResult.componentTreePath!!.replace(".json", ".png")).exists())
      val tree = treeFile.bufferedReader().use { readComponentTreeFromJson(it) }
      assertEquals(screenshotResult.previewId, tree.previewId)
      assertEquals(screenshotResult.methodFQN, tree.methodFQN)
      assertTrue(tree.components.isNotEmpty())
      fun flattenNodes(nodes: List<ComponentTreeNode>): List<ComponentTreeNode> = nodes.flatMap {
        listOf(it) + flattenNodes(it.children.orEmpty())
      }
      val allNodes = flattenNodes(tree.components)
      assertTrue(
        "Expected Button node in Compose component tree, got: ${allNodes.map { "${it.name}@${it.sourceLocation?.fileName}:${it.sourceLocation?.lineNumber}" }}",
        allNodes.any { it.name == "Button" },
      )
      val textNode = allNodes.firstOrNull { it.name == "Text" }
      assertNotNull("Expected Text node in Compose component tree", textNode)
      assertEquals(
        "Expected 'Ok' on Text node (params=${textNode!!.parameters}, semantics=${textNode.semantics})",
        "Ok",
        textNode.parameters?.get("text") ?: textNode.semantics?.get("text"),
      )
    }
    val warmRenderResult =
      standaloneRenderer.render(
        screenshots = screenshots,
        outputFolder = treeOutputFolder.absolutePath,
        includeComponentTree = true,
        renderImage = false,
      )
    assertNull(warmRenderResult.globalError)
    assertEquals(screenshots.size, warmRenderResult.screenshotResults.size)
    warmRenderResult.screenshotResults.forEach { assertNull(it.error) }
    assertNotNull(StandalonePreviewRenderer::class.java.getMethod("render", List::class.java, String::class.java))
    assertNotNull(
      StandalonePreviewRenderer::class.java.getConstructor(
        String::class.java,
        String::class.java,
        String::class.java,
        List::class.java,
        String::class.java,
        List::class.java,
        List::class.java,
      )
    )

    // Stop the daemon otherwise it could keep the lock on the temporary folder
    StandalonePreviewRenderer.closeWarmRenderer()
    gradleProject.executeGradleTask("--stop")
  }
}
