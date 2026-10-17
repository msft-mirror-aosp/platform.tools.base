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
import com.android.tools.render.common.StandalonePreviewRenderer
import com.android.tools.render.common.readComponentTreeFromJson
import com.android.tools.render.common.readPreviewRenderingJson
import com.android.tools.render.common.readPreviewRenderingResultJson
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
