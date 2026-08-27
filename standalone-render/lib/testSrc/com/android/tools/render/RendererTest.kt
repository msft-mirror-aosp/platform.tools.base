/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tools.render

import com.android.ide.common.rendering.api.Result
import com.android.testutils.TestUtils
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.render.discovery.SamplePreviewTarget
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.image.BufferedImage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.absolutePathString
import kotlin.math.abs
import kotlin.math.max
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RendererTest {
  @JvmField @Rule val tmpFolder = TemporaryFolder()

  @JvmField @Rule val renderTest = RenderTestRule()

  companion object {
    @AfterClass
    @JvmStatic
    fun stopExecutor() {
      // Make sure the queue is empty
      AppExecutorUtil.getAppScheduledExecutorService().submit {}.get(60, TimeUnit.SECONDS)
      AppExecutorUtil.shutdownApplicationScheduledExecutorService()
    }

    private const val TEST_DATA_DIR = "tools/base/standalone-render/lib/testData/rendered_images"
    private const val THRESHOLD = 1
  }

  @Test
  fun testSimpleLayoutRendering() {
    // language=xml
    val layout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:orientation="vertical" >
          <TextView
              android:layout_width="match_parent"
              android:layout_height="wrap_content"
              android:gravity="top"
              android:text="Hello!" />
          <Button
              android:layout_width="100dp"
              android:layout_height="wrap_content"
              android:layout_gravity="end"
              android:text="Press me!" />
      </LinearLayout>
      """
        .trimIndent()

    val request = RenderRequest({}) { sequenceOf(layout) }

    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")

    var outputImage: BufferedImage? = null
    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    bootstrapper.bootstrap().use {
      val (_, result) = it.render(request).single()
      assertNull("A single RenderResult is expected", outputImage)
      outputImage = result.renderedImage.copy
      result.dispose()
    }

    assertNotNull(outputImage)

    val goldenImagePath = TestUtils.resolveWorkspacePath("$TEST_DATA_DIR/img.png")
    val goldenImage = ImageIO.read(goldenImagePath.toFile())

    assertEquals(goldenImage.width, outputImage!!.width)
    assertEquals(goldenImage.height, outputImage!!.height)
    var lInfDiff = 0
    (0 until goldenImage.height).forEach { j ->
      (0 until goldenImage.width).forEach { i ->
        val goldenCol = goldenImage.getRGB(i, j)
        val imgCol = outputImage!!.getRGB(i, j)
        lInfDiff = max(lInfDiff, abs((goldenCol and 0xFF) - (imgCol and 0xFF)))
        lInfDiff = max(lInfDiff, abs(((goldenCol shl 8) and 0xFF) - ((imgCol shl 8) and 0xFF)))
        lInfDiff = max(lInfDiff, abs(((goldenCol shl 16) and 0xFF) - ((imgCol shl 16) and 0xFF)))
      }
    }
    assertTrue("The L-infinity image diff is $lInfDiff, higher than the threshold $THRESHOLD", lInfDiff <= THRESHOLD)
  }

  @Test
  fun testIncorrectLayoutlibPath() {
    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = "",
      )
    val renderResults =
      bootstrapper.bootstrap().use {
        val invalidRequest = RenderRequest({}) { sequenceOf("") }
        it.render(invalidRequest).map { it.second }.toList()
      }

    assertEquals(1, renderResults.size)
    val renderResult = renderResults[0]
    assertEquals(Result.Status.ERROR_RENDER_TASK, renderResult.renderResult.status)
    assertTrue(renderResult.renderResult.exception is ExecutionException)
  }

  @Test
  fun testMissingResource() {
    // language=xml
    val layout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:orientation="vertical" >
          <TextView
              android:layout_width="match_parent"
              android:layout_height="wrap_content"
              android:gravity="top"
              android:text="@string/hello" />
      </LinearLayout>
      """
        .trimIndent()

    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    val renderResults = bootstrapper.bootstrap().use { it.render(RenderRequest({}) { sequenceOf(layout) }).map { it.second }.toList() }

    assertEquals(1, renderResults.size)
    val renderResult = renderResults[0]
    assertEquals(Result.Status.SUCCESS, renderResult.renderResult.status)
    val messages = renderResult.logger.messages
    assertEquals(1, messages.size)
    assertEquals("Couldn't resolve resource @string/hello", messages[0].html)
  }

  @Test
  fun testRenderComposeScreenshotWithEmptyPreviewParamsDiscoversAnnotations() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    val outputDir = tmpFolder.newFolder("output_screenshots").absolutePath
    val screenshot =
      ComposeScreenshot(
        previewId = "preview_auto_discover",
        methodFQN = "${SamplePreviewTarget::class.java.name}.SampleAnnotatedPreview",
        previewParams = emptyMap(),
        methodParams = emptyList(),
      )

    val results = bootstrapper.bootstrap().use { renderer -> renderer.render(screenshot, outputDir) }

    assertEquals(1, results.size)
    val result = results[0]
    assertEquals("preview_auto_discover", result.previewId)
    assertEquals("${SamplePreviewTarget::class.java.name}.SampleAnnotatedPreview", result.methodFQN)
  }

  @Test
  fun testRenderComposeScreenshotWithInvalidPreviewParamsReturnsValidationError() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    val outputDir = tmpFolder.newFolder("output_screenshots_invalid").absolutePath
    val screenshot =
      ComposeScreenshot(
        previewId = "preview_invalid",
        methodFQN = "${SamplePreviewTarget::class.java.name}.SampleInvalidPreviewMethod",
        previewParams = emptyMap(),
        methodParams = emptyList(),
      )

    val results = bootstrapper.bootstrap().use { renderer -> renderer.render(screenshot, outputDir) }
    assertEquals(1, results.size)
    val result = results[0]
    assertNotNull("ScreenshotError should be present for validation errors", result.error)
    assertEquals("VALIDATION_ERROR", result.error?.status)
    assertTrue(result.error?.message?.contains("widthDp") == true)
  }

  @Test
  fun testRenderComposeScreenshotWithMultiPreviewRendersAllPreviews() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    val outputDir = tmpFolder.newFolder("output_screenshots_multi").absolutePath
    val screenshot =
      ComposeScreenshot(
        previewId = "multi_preview",
        methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMultiPreviewMethod",
        previewParams = emptyMap(),
        methodParams = emptyList(),
      )

    val results = bootstrapper.bootstrap().use { renderer -> renderer.render(screenshot, outputDir) }

    assertEquals(2, results.size)
    assertEquals("multi_preview", results[0].previewId)
    assertEquals("multi_preview", results[1].previewId)
  }

  @Test
  fun testRenderMixedMultiPreviewContinuesRenderingValidScreenshots() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    val outputDir = tmpFolder.newFolder("output_screenshots_mixed").absolutePath
    val screenshot =
      ComposeScreenshot(
        previewId = "mixed_preview",
        methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMixedMultiPreviewMethod",
        previewParams = emptyMap(),
        methodParams = emptyList(),
      )

    val results = bootstrapper.bootstrap().use { renderer -> renderer.render(screenshot, outputDir) }

    assertEquals(2, results.size)

    // First preview is valid: should render through layoutlib without validation error
    val validResult = results[0]
    assertEquals("mixed_preview", validResult.previewId)
    assertNotEquals("VALIDATION_ERROR", validResult.error?.status)

    // Second preview is invalid: should have VALIDATION_ERROR
    val invalidResult = results[1]
    assertEquals("mixed_preview", invalidResult.previewId)
    assertNotNull("Invalid preview should produce a validation error", invalidResult.error)
    assertEquals("VALIDATION_ERROR", invalidResult.error?.status)
    assertTrue(invalidResult.error?.message?.contains("widthDp") == true)
  }

  @Test
  fun testRenderCustomMultiPreviewClassAnnotationRendersAllPreviews() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    val outputDir = tmpFolder.newFolder("output_screenshots_multipreview_class").absolutePath
    val screenshot =
      ComposeScreenshot(
        previewId = "multipreview_class",
        methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMultiPreviewAnnotatedMethod",
        previewParams = emptyMap(),
        methodParams = emptyList(),
      )

    val results = bootstrapper.bootstrap().use { renderer -> renderer.render(screenshot, outputDir) }

    assertEquals(2, results.size)
    assertEquals("multipreview_class", results[0].previewId)
    assertEquals("multipreview_class", results[1].previewId)
    assertTrue(results[0].imagePath.endsWith("multipreview_class_0.png"))
    assertTrue(results[1].imagePath.endsWith("multipreview_class_1.png"))
  }

  @Test
  fun testRenderNonComposablePreviewProducesValidationError() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    val outputDir = tmpFolder.newFolder("output_screenshots_non_composable").absolutePath
    val screenshot =
      ComposeScreenshot(
        previewId = "non_composable_preview",
        methodFQN = "${SamplePreviewTarget::class.java.name}.SampleNonComposablePreviewMethod",
        previewParams = emptyMap(),
        methodParams = emptyList(),
      )

    val results = bootstrapper.bootstrap().use { renderer -> renderer.render(screenshot, outputDir) }

    assertEquals(1, results.size)
    val result = results[0]
    assertEquals("non_composable_preview", result.previewId)
    assertEquals("VALIDATION_ERROR", result.error?.status)
    assertTrue(result.error?.message?.contains("must be annotated with @Composable") == true)
  }
}
