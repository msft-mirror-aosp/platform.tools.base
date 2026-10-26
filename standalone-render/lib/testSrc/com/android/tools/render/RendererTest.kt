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
import com.android.tools.configurations.Wallpaper
import com.android.tools.preview.ConfigurablePreviewElement
import com.android.tools.render.common.ComponentTreeExtractor
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.render.common.ResolvedScreenshotLayout
import com.android.tools.render.common.ScreenshotPreviewElement
import com.android.tools.render.common.readComponentTreeFromJson
import com.android.tools.render.common.toScreenshotError
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.render.discovery.SamplePreviewTarget
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.compose.ComposeViewInfo
import com.android.tools.rendering.compose.PxBounds
import com.android.tools.rendering.compose.SourceLocationImpl
import com.android.tools.res.FrameworkOverlay
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.tools.ToolProvider
import kotlin.io.path.absolutePathString
import kotlin.math.abs
import kotlin.math.max
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    val request = RenderRequest({}, { sequenceOf(layout) })

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
  fun testInflateOnlyRenderingAndComponentTreeExtraction() {
    // language=xml
    val layout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="wrap_content"
          android:orientation="vertical" >
          <TextView
              android:layout_width="match_parent"
              android:layout_height="wrap_content"
              android:text="Inflate only!" />
      </LinearLayout>
      """
        .trimIndent()

    val request = RenderRequest({}, { sequenceOf(layout) })
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
    bootstrapper.bootstrap().use { renderer ->
      val (config, result) = renderer.render(request, renderImage = false).single()
      try {
        assertEquals(Result.Status.SUCCESS, result.renderResult.status)
        assertTrue(result.rootViews.isNotEmpty())

        val error = result.toScreenshotError(imageRendered = null, expectImage = false)
        assertNull(error)

        val tree =
          ComponentTreeExtractor.extract(
            previewId = "test_preview",
            methodFQN = "com.example.TestKt.Preview",
            displayName = "InflateOnly",
            config = config,
            renderResult = result,
          )
        assertEquals("test_preview", tree.previewId)
        assertTrue(tree.components.isNotEmpty())
        assertTrue(tree.width > 0)
        assertTrue(tree.height > 0)
      } finally {
        result.dispose()
      }
    }
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
        val invalidRequest = RenderRequest({}, { sequenceOf("") })
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
    val renderResults = bootstrapper.bootstrap().use { it.render(RenderRequest({}, { sequenceOf(layout) })).map { it.second }.toList() }

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

  @Test
  fun testRenderMethodWithoutPreviewProducesValidationError() {
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
    val outputDir = tmpFolder.newFolder("output_screenshots_no_preview").absolutePath
    val screenshot =
      ComposeScreenshot(
        previewId = "no_preview_method",
        methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMethodWithoutAnnotation",
        previewParams = emptyMap(),
        methodParams = emptyList(),
      )

    val results = bootstrapper.bootstrap().use { renderer -> renderer.render(screenshot, outputDir) }

    assertEquals(1, results.size)
    val result = results[0]
    assertEquals("no_preview_method", result.previewId)
    assertEquals("VALIDATION_ERROR", result.error?.status)
    assertTrue(result.error?.message?.contains("No @Preview annotations found") == true)
  }

  @Test
  fun testRenderComposeScreenshotWithMultiPreviewAndBrokenParameterFailsEarlyWithSingleError() {
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
    val outputDir = tmpFolder.newFolder("output_screenshots_multi_broken_param").absolutePath
    val screenshot =
      ComposeScreenshot(
        previewId = "multi_preview_broken_param",
        methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMultiPreviewWithBrokenPreviewParameter",
        previewParams = emptyMap(),
        methodParams = emptyList(),
      )

    val results = bootstrapper.bootstrap().use { renderer -> renderer.render(screenshot, outputDir) }

    // Even though the method has multiple @Preview annotations (which normally expand to 4 preview images),
    // because @PreviewParameter is validated at the method level, discovery fails fast and only 1 error result is emitted.
    assertEquals("Should emit exactly 1 error result instead of duplicating across previews", 1, results.size)
    val result = results[0]
    assertEquals("multi_preview_broken_param", result.previewId)
    assertNotNull("ScreenshotError should be present for broken @PreviewParameter", result.error)
    assertEquals("VALIDATION_ERROR", result.error?.status)
    assertTrue(result.error?.message?.contains("limit") == true)
  }

  @Test
  fun testBaseConfigurationDefaults_withoutMaterial3InResources_fallsBackToPlatformTheme() {
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

    bootstrapper.bootstrap().use { renderer ->
      val config = renderer.baseConfiguration
      // Without Material 3 styles in resources, defaults safely to the built-in platform framework theme.
      assertEquals("@android:style/Theme.Material.Light.NoActionBar", config.theme)
      assertEquals(Wallpaper.GREEN.resourcePath, config.wallpaperPath)
      assertTrue(config.isGestureNav)
      assertTrue(config.isEdgeToEdge)
      assertEquals(FrameworkOverlay.CUTOUT_NONE, config.cutoutOverlay)
      assertEquals(1.0f, config.fontScale, 0.001f)
      assertEquals("medium_phone", config.device?.id)
    }
  }

  @Test
  fun testBaseConfigurationDefaults_withMaterial3InResources_usesMaterial3Theme() {
    val resFolder = tmpFolder.newFolder("res")
    val valuesFolder = File(resFolder, "values").apply { mkdirs() }
    File(valuesFolder, "styles.xml")
      .writeText(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <style name="Theme.Material3.DayNight.NoActionBar" parent="" />
        </resources>
        """
          .trimIndent()
      )

    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        resourceDirs = listOf(resFolder.absolutePath),
        namespace = "com.example.test",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )

    bootstrapper.bootstrap().use { renderer ->
      val config = renderer.baseConfiguration
      assertEquals("@style/Theme.Material3.DayNight.NoActionBar", config.theme)
      assertEquals(Wallpaper.GREEN.resourcePath, config.wallpaperPath)
      assertTrue(config.isGestureNav)
      assertTrue(config.isEdgeToEdge)
      assertEquals(FrameworkOverlay.CUTOUT_NONE, config.cutoutOverlay)
      assertEquals(1.0f, config.fontScale, 0.001f)
      assertEquals("medium_phone", config.device?.id)
    }
  }

  @Test
  fun testSaveComponentTreeWritesCompanionJsonAndSerializesResult() {
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
    val childInfo =
      ComposeViewInfo(
        sourceLocation = SourceLocationImpl("HomeScreen.kt", 42, 999),
        bounds = PxBounds(10, 20, 110, 70),
        children = emptyList(),
        name = "Button",
      )
    val rootInfo =
      ComposeViewInfo(
        sourceLocation = SourceLocationImpl("HomeScreen.kt", 30, 999),
        bounds = PxBounds(0, 0, 300, 600),
        children = listOf(childInfo),
        name = "Column",
      )
    val outputDir = tmpFolder.newFolder("output_component_tree_test")
    val relativeImagePath = "pkg/Preview_0.png"
    val expectedTreePath = "pkg/Preview_0.json"

    bootstrapper.bootstrap().use { renderer ->
      val saved =
        renderer.saveComponentTree(
          previewId = "preview_id",
          methodFQN = "pkg.Preview",
          displayName = "Preview",
          outputFolderPath = outputDir.absolutePath,
          relativeComponentTreePath = expectedTreePath,
          viewInfos = listOf(rootInfo),
        )
      assertThat(saved).isTrue()
    }

    val expectedTreeFile = Paths.get(outputDir.absolutePath, expectedTreePath)
    assertThat(Files.exists(expectedTreeFile)).isTrue()
    // Verify that NO .bounds.json companion file was created
    val boundsFile = Paths.get(outputDir.absolutePath, "pkg/Preview_0.bounds.json")
    assertThat(Files.exists(boundsFile)).isFalse()

    val parsedTree =
      Files.newBufferedReader(expectedTreeFile, StandardCharsets.UTF_8).use {
        readComponentTreeFromJson(it)
      }
    assertThat(parsedTree.previewId).isEqualTo("preview_id")
    assertThat(parsedTree.methodFQN).isEqualTo("pkg.Preview")
    assertThat(parsedTree.displayName).isEqualTo("Preview")
    assertThat(parsedTree.width).isEqualTo(300)
    assertThat(parsedTree.height).isEqualTo(600)
    assertThat(parsedTree.deviceId).isEqualTo("medium_phone")
    assertThat(parsedTree.fontScale).isEqualTo(1.0f)
    assertThat(parsedTree.components).hasSize(1)
    assertThat(parsedTree.components[0].name).isEqualTo("Column")
    assertThat(parsedTree.components[0].children).hasSize(1)
    assertThat(parsedTree.components[0].children?.get(0)?.name).isEqualTo("Button")

    val screenshotResult =
      PreviewScreenshotResult(
        "preview_id",
        "pkg.Preview",
        relativeImagePath,
        null,
        "Preview",
        listOf(rootInfo),
        expectedTreePath,
      )
    val serializedBytes =
      ByteArrayOutputStream().use { baos ->
        ObjectOutputStream(baos).use { it.writeObject(screenshotResult) }
        baos.toByteArray()
      }
    val deserializedResult =
      ByteArrayInputStream(serializedBytes).use { bais ->
        ObjectInputStream(bais).use {
          it.readObject() as PreviewScreenshotResult
        }
      }
    assertThat(deserializedResult.viewInfos).containsExactly(rootInfo)
    assertThat(deserializedResult.componentTreePath).isEqualTo(expectedTreePath)
  }

  @Test
  fun testRenderScreenshotEndToEndWithComponentTreeAndNoImage() {
    // language=xml
    val xmlLayout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="wrap_content"
          android:orientation="vertical" >
          <TextView
              android:layout_width="match_parent"
              android:layout_height="wrap_content"
              android:text="End-to-end component tree!" />
      </LinearLayout>
      """
        .trimIndent()

    val xmlScreenshot =
      object : PreviewScreenshot {
        override val methodFQN = "com.example.XmlPreviewKt.SampleXmlPreview"
        override val previewParams = emptyMap<String, String>()
        override val previewId = "com.example.XmlPreviewKt.SampleXmlPreview_screenshot"
        override val previewWrapperFqn: String? = null

        override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement {
          val delegate = ComposeScreenshot(methodFQN, emptyList(), previewParams, previewId).toPreviewElement(module)
          return object : ScreenshotPreviewElement, ConfigurablePreviewElement<Unit> by delegate {
            override fun resolveXmlLayouts(): Sequence<String> = sequenceOf(xmlLayout)
          }
        }
      }

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
    val outputDir = tmpFolder.newFolder("output_e2e_no_image")
    val results =
      bootstrapper.bootstrap().use { renderer ->
        renderer.render(
          xmlScreenshot,
          outputDir.absolutePath,
          includeComponentTree = true,
          renderImage = false,
        )
      }

    assertThat(results).hasSize(1)
    val result = results[0]
    assertThat(result.error).isNull()
    assertThat(result.imagePath).isEmpty()
    val expectedRelativeTreePath =
      listOf("com", "example", "XmlPreviewKt", "SampleXmlPreview_screenshot_0.json").joinToString(File.separator)
    assertThat(result.componentTreePath).isEqualTo(expectedRelativeTreePath)

    val pngFile = Paths.get(outputDir.absolutePath, expectedRelativeTreePath.replace(".json", ".png"))
    assertThat(Files.exists(pngFile)).isFalse()

    val treeFile = Paths.get(outputDir.absolutePath, expectedRelativeTreePath)
    assertThat(Files.exists(treeFile)).isTrue()
    val parsedTree = Files.newBufferedReader(treeFile, StandardCharsets.UTF_8).use { readComponentTreeFromJson(it) }
    assertThat(parsedTree.previewId).isEqualTo(xmlScreenshot.previewId)
    assertThat(parsedTree.methodFQN).isEqualTo(xmlScreenshot.methodFQN)
    assertThat(parsedTree.components).isNotEmpty()
    assertThat(parsedTree.components[0].name).isEqualTo("android.widget.LinearLayout")
    assertThat(parsedTree.components[0].children?.get(0)?.semantics?.get("text")).isEqualTo("End-to-end component tree!")
  }

  @Test
  fun testSaveComponentTreeRejectsPathTraversal() {
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
    val outputDir = tmpFolder.newFolder("output_traversal_check")
    bootstrapper.bootstrap().use { renderer ->
      val saved =
        renderer.saveComponentTree(
          previewId = "preview_id",
          methodFQN = "pkg.Preview",
          displayName = "Preview",
          outputFolderPath = outputDir.absolutePath,
          relativeComponentTreePath = "../escaped.json",
        )
      assertThat(saved).isFalse()
    }
    assertThat(Files.exists(outputDir.toPath().parent.resolve("escaped.json"))).isFalse()
  }

  @Test
  fun testRenderScreenshotPreservesSpacesInPreviewId() {
    // language=xml
    val xmlLayout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="wrap_content"
          android:orientation="vertical" >
          <TextView
              android:layout_width="match_parent"
              android:layout_height="wrap_content"
              android:text="Tile preview with spaces" />
      </LinearLayout>
      """
        .trimIndent()

    val xmlScreenshot =
      object : PreviewScreenshot {
        override val methodFQN = "com.example.TilePreviewKt.simpleTilePreview"
        override val previewParams = emptyMap<String, String>()
        override val previewId = "com.example.TilePreviewKt.simpleTilePreview_simple tile_7cfb9daa"
        override val previewWrapperFqn: String? = null

        override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement {
          val delegate = ComposeScreenshot(methodFQN, emptyList(), previewParams, previewId).toPreviewElement(module)
          return object : ScreenshotPreviewElement, ConfigurablePreviewElement<Unit> by delegate {
            override fun resolveXmlLayouts(): Sequence<String> = sequenceOf(xmlLayout)
          }
        }
      }

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
    val outputDir = tmpFolder.newFolder("output_spaces_in_preview_id")
    val results =
      bootstrapper.bootstrap().use { renderer ->
        renderer.render(
          xmlScreenshot,
          outputDir.absolutePath,
          includeComponentTree = true,
          renderImage = true,
        )
      }

    assertThat(results).hasSize(1)
    val result = results[0]
    assertThat(result.error).isNull()
    val expectedRelativeImagePath =
      listOf("com", "example", "TilePreviewKt", "simpleTilePreview_simple tile_7cfb9daa_0.png").joinToString(File.separator)
    val expectedRelativeTreePath =
      listOf("com", "example", "TilePreviewKt", "simpleTilePreview_simple tile_7cfb9daa_0.json").joinToString(File.separator)
    assertThat(result.imagePath).isEqualTo(expectedRelativeImagePath)
    assertThat(result.componentTreePath).isEqualTo(expectedRelativeTreePath)
    assertThat(Files.exists(Paths.get(outputDir.absolutePath, expectedRelativeImagePath))).isTrue()
    assertThat(Files.exists(Paths.get(outputDir.absolutePath, expectedRelativeTreePath))).isTrue()
  }

  @Test
  fun testBinaryCompatibilityOverloadsExist() {
    assertNotNull(Renderer::class.java.getMethod("render", PreviewScreenshot::class.java, String::class.java))
    assertNotNull(
      Renderer::class.java.getMethod("render", PreviewScreenshot::class.java, String::class.java, Boolean::class.javaPrimitiveType)
    )
    assertNotNull(
      Renderer::class
        .java
        .getMethod(
          "render",
          PreviewScreenshot::class.java,
          String::class.java,
          Boolean::class.javaPrimitiveType,
          Boolean::class.javaPrimitiveType,
        )
    )
    assertNotNull(Renderer::class.java.getMethod("renderResolvedLayouts", RenderRequest::class.java))
    assertNotNull(Renderer::class.java.getMethod("renderResolvedLayouts", RenderRequest::class.java, Boolean::class.javaPrimitiveType))
    assertNotNull(
      Renderer::class
        .java
        .getMethod(
          "renderResolvedLayouts\$default",
          Renderer::class.java,
          RenderRequest::class.java,
          Boolean::class.javaPrimitiveType,
          Int::class.javaPrimitiveType,
          Any::class.java,
        )
    )
  }

  @Test
  fun testRenderReturnsErrorWhenPreviewElementCreationFails() {
    val result =
      renderFailingScreenshot(
        FailingPreviewScreenshot(methodFQN = "com.example.BrokenKt.BrokenPreview", previewId = "com.example.BrokenKt.BrokenPreview") {
          IllegalStateException("Cannot create preview element")
        }
      )

    assertEquals("com.example.BrokenKt.BrokenPreview", result.previewId)
    assertEquals("com${File.separator}example${File.separator}BrokenKt${File.separator}BrokenPreview_0.png", result.imagePath)
    assertNotNull(result.error)
    assertTrue(result.error?.message?.contains("Cannot create preview element") == true)
  }

  @Test
  fun testRenderReturnsErrorWhenPreviewElementCreationThrowsError() {
    val result =
      renderFailingScreenshot(
        FailingPreviewScreenshot(methodFQN = "com.example.BrokenKt.BrokenPreview", previewId = "com.example.BrokenKt.BrokenPreview") {
          NoClassDefFoundError("com/example/MissingClass")
        }
      )

    assertEquals("com.example.BrokenKt.BrokenPreview", result.previewId)
    assertNotNull(result.error)
    assertTrue(result.error?.message?.contains("com/example/MissingClass") == true)
  }

  @Test
  fun testRenderExecutingCallbacks_excludesRenderTimeFromTimeoutAndRunsSecondRoundForHandlerWorkPostedDuringRender() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val simulatedRenderNanos = AtomicLong(0L)
    val simulatedRenderClockKey = "com.android.tools.render.test.simulatedRenderNanos.handler"
    System.getProperties()[simulatedRenderClockKey] = simulatedRenderNanos
    try {
      val handlerViewDir =
        compileCustomView(
          "com.example",
          "SlowDrawHandlerTextView",
          """
          package com.example;
          import android.content.Context;
          import android.graphics.Canvas;
          import android.util.AttributeSet;
          import android.widget.TextView;
          import java.util.concurrent.TimeUnit;
          import java.util.concurrent.atomic.AtomicLong;
          public class SlowDrawHandlerTextView extends TextView {
            private int drawCount = 0;
            public SlowDrawHandlerTextView(Context context, AttributeSet attrs) {
              super(context, attrs);
              setText("Initial");
            }
            @Override
            protected void onDraw(Canvas canvas) {
              super.onDraw(canvas);
              drawCount++;
              // renderTask.inflate() does not draw, so drawCount == 1 is Round 1's renderTask.render().
              if (drawCount == 1) {
                AtomicLong clock = (AtomicLong) System.getProperties().get("$simulatedRenderClockKey");
                clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
                post(() -> setText("Updated by Handler in round 2"));
              }
            }
          }
          """
            .trimIndent(),
        )
      // language=xml
      val handlerXmlLayout =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" >
            <com.example.SlowDrawHandlerTextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </LinearLayout>
        """
          .trimIndent()

      val bootstrapper =
        RenderEnvironmentBootstrapper(
          fontsPath = null,
          resourceApkPath = null,
          namespace = "com.example",
          classPath = emptyList(),
          projectClassPath = listOf(handlerViewDir.absolutePath),
          layoutlibPath = layoutlibPath.absolutePathString(),
        )
      val outputDir = tmpFolder.newFolder("output_handler_round2")
      val results =
        bootstrapper.bootstrap().use { renderer ->
          // Generous 5s wall-clock budget for callback steps, while onDraw advances nanoTime by 10s during round 1's render().
          renderer.callbackRunner.callbacksTimeoutMs = 5_000L
          renderer.callbackRunner.nanoTime = { System.nanoTime() + simulatedRenderNanos.get() }
          renderer.render(
            xmlPreviewScreenshot("com.example.PreviewKt.HandlerRound2", handlerXmlLayout),
            outputDir.absolutePath,
            includeComponentTree = true,
            renderImage = true,
          )
        }

      assertThat(results).hasSize(1)
      val handlerResult = results[0]
      assertThat(handlerResult.error).isNull()
      val handlerTreeFile = Paths.get(outputDir.absolutePath, handlerResult.componentTreePath!!)
      val parsedHandlerTree = Files.newBufferedReader(handlerTreeFile, StandardCharsets.UTF_8).use { readComponentTreeFromJson(it) }
      assertThat(parsedHandlerTree.components[0].children?.get(0)?.semantics?.get("text")).isEqualTo("Updated by Handler in round 2")
    } finally {
      System.getProperties().remove(simulatedRenderClockKey)
    }
  }

  @Test
  fun testRenderExecutingCallbacks_excludesRenderTimeFromTimeoutForInfiniteChoreographerAnimationAcrossTwoRounds() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val simulatedRenderNanos = AtomicLong(0L)
    val simulatedRenderClockKey = "com.android.tools.render.test.simulatedRenderNanos.infiniteAnim"
    System.getProperties()[simulatedRenderClockKey] = simulatedRenderNanos
    try {
      val infiniteAnimViewDir =
        compileCustomView(
          "com.example",
          "SlowDrawInfiniteAnimationTextView",
          """
          package com.example;
          import android.content.Context;
          import android.graphics.Canvas;
          import android.util.AttributeSet;
          import android.view.Choreographer;
          import android.widget.TextView;
          import java.util.concurrent.TimeUnit;
          import java.util.concurrent.atomic.AtomicLong;
          public class SlowDrawInfiniteAnimationTextView extends TextView implements Choreographer.FrameCallback {
            private boolean started = false;
            private int drawCount = 0;
            private int frameCount = 0;
            public SlowDrawInfiniteAnimationTextView(Context context, AttributeSet attrs) {
              super(context, attrs);
              setText("Initial");
            }
            @Override
            protected void onAttachedToWindow() {
              super.onAttachedToWindow();
              if (!started) {
                started = true;
                Choreographer.getInstance().postFrameCallbackDelayed(this, 1);
              }
            }
            @Override
            public void doFrame(long frameTimeNanos) {
              frameCount++;
              setText("Frame " + frameCount);
              Choreographer.getInstance().postFrameCallbackDelayed(this, 1);
            }
            @Override
            protected void onDraw(Canvas canvas) {
              super.onDraw(canvas);
              drawCount++;
              // renderTask.inflate() does not draw, so drawCount == 1 is Round 1's renderTask.render()
              // after 10 animation frames.
              if (drawCount == 1) {
                AtomicLong clock = (AtomicLong) System.getProperties().get("$simulatedRenderClockKey");
                clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
              }
            }
          }
          """
            .trimIndent(),
        )
      // language=xml
      val infiniteAnimXmlLayout =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" >
            <com.example.SlowDrawInfiniteAnimationTextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </LinearLayout>
        """
          .trimIndent()

      val bootstrapper =
        RenderEnvironmentBootstrapper(
          fontsPath = null,
          resourceApkPath = null,
          namespace = "com.example",
          classPath = emptyList(),
          projectClassPath = listOf(infiniteAnimViewDir.absolutePath),
          layoutlibPath = layoutlibPath.absolutePathString(),
        )
      val outputDir = tmpFolder.newFolder("output_infinite_anim_round2")
      val results =
        bootstrapper.bootstrap().use { renderer ->
          // Generous 5s wall-clock budget for callback steps, while onDraw advances nanoTime by 10s during round 1's render().
          renderer.callbackRunner.callbacksTimeoutMs = 5_000L
          renderer.callbackRunner.nanoTime = { System.nanoTime() + simulatedRenderNanos.get() }
          renderer.render(
            xmlPreviewScreenshot("com.example.PreviewKt.InfiniteAnimRound2", infiniteAnimXmlLayout),
            outputDir.absolutePath,
            includeComponentTree = true,
            renderImage = true,
          )
        }

      assertThat(results).hasSize(1)
      val infiniteAnimResult = results[0]
      assertThat(infiniteAnimResult.error).isNull()
      val infiniteAnimTreeFile = Paths.get(outputDir.absolutePath, infiniteAnimResult.componentTreePath!!)
      val parsedInfiniteAnimTree =
        Files.newBufferedReader(infiniteAnimTreeFile, StandardCharsets.UTF_8).use { readComponentTreeFromJson(it) }
      val expectedTotalFrames = CALLBACK_EXECUTIONS_PER_ROUND * CALLBACK_ROUNDS
      assertThat(parsedInfiniteAnimTree.components[0].children?.get(0)?.semantics?.get("text")).isEqualTo("Frame $expectedTotalFrames")
    } finally {
      System.getProperties().remove(simulatedRenderClockKey)
    }
  }

  @Test
  fun testRenderExecutingCallbacks_runsSecondRoundForChoreographerCallbackPostedDuringRender() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val customViewDir =
      compileCustomView(
        "com.example",
        "ChoreographerDrawTextView",
        """
        package com.example;
        import android.content.Context;
        import android.graphics.Canvas;
        import android.util.AttributeSet;
        import android.view.Choreographer;
        import android.widget.TextView;
        public class ChoreographerDrawTextView extends TextView {
          private int drawCount = 0;
          public ChoreographerDrawTextView(Context context, AttributeSet attrs) {
            super(context, attrs);
            setText("Initial");
          }
          @Override
          protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawCount++;
            // renderTask.inflate() does not draw, so drawCount == 1 is Round 1's renderTask.render().
            if (drawCount == 1) {
              Choreographer.getInstance().postFrameCallback(frameTimeNanos -> setText("Updated by Choreographer in round 2"));
            }
          }
        }
        """
          .trimIndent(),
      )
    // language=xml
    val xmlLayout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="wrap_content"
          android:orientation="vertical" >
          <com.example.ChoreographerDrawTextView
              android:layout_width="match_parent"
              android:layout_height="wrap_content" />
      </LinearLayout>
      """
        .trimIndent()

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "com.example",
        classPath = emptyList(),
        projectClassPath = listOf(customViewDir.absolutePath),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    val outputDir = tmpFolder.newFolder("output_choreographer_round2")
    val results =
      bootstrapper.bootstrap().use { renderer ->
        renderer.render(
          xmlPreviewScreenshot("com.example.PreviewKt.ChoreographerRound2", xmlLayout),
          outputDir.absolutePath,
          includeComponentTree = true,
          renderImage = true,
        )
      }

    assertThat(results).hasSize(1)
    val result = results[0]
    assertThat(result.error).isNull()
    val treeFile = Paths.get(outputDir.absolutePath, result.componentTreePath!!)
    val parsedTree = Files.newBufferedReader(treeFile, StandardCharsets.UTF_8).use { readComponentTreeFromJson(it) }
    assertThat(parsedTree.components[0].children?.get(0)?.semantics?.get("text")).isEqualTo("Updated by Choreographer in round 2")
  }

  @Test
  fun testPlainRenderRequest_doesNotExecuteCallbacksAndRestoresRenderThreadUncaughtExceptionHandler() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val customViewDir =
      compileCustomView(
        "com.example",
        "AttachCallbackTextView",
        """
        package com.example;
        import android.content.Context;
        import android.util.AttributeSet;
        import android.view.Choreographer;
        import android.widget.TextView;
        public class AttachCallbackTextView extends TextView {
          public AttachCallbackTextView(Context context, AttributeSet attrs) {
            super(context, attrs);
            setText("Initial");
          }
          @Override
          protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(() -> setText("Updated by callback"));
            Choreographer.getInstance().postFrameCallback(frameTimeNanos -> setText("Updated by callback"));
          }
        }
        """
          .trimIndent(),
      )
    // language=xml
    val xmlLayout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="wrap_content"
          android:orientation="vertical" >
          <com.example.AttachCallbackTextView
              android:layout_width="match_parent"
              android:layout_height="wrap_content" />
      </LinearLayout>
      """
        .trimIndent()

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "com.example",
        classPath = emptyList(),
        projectClassPath = listOf(customViewDir.absolutePath),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    val outputDir = tmpFolder.newFolder("output_plain_vs_preview")
    val inflateOnlyOutputDir = tmpFolder.newFolder("output_preview_inflate_only")
    bootstrapper.bootstrap().use { renderer ->
      val executor = RenderService.getRenderAsyncActionExecutor()
      val handlerBefore = executor.runAsyncAction(Callable { Thread.currentThread().uncaughtExceptionHandler }).get(10, TimeUnit.SECONDS)

      // 1. Plain RenderRequest must render once without executing layoutlib callbacks.
      val plainRequest = RenderRequest(configurationModifier = {}, xmlLayoutsProvider = { sequenceOf(xmlLayout) })
      val (plainConfig, plainResult) = renderer.render(plainRequest, renderImage = true).single()
      try {
        assertThat(plainResult.renderResult.status).isEqualTo(Result.Status.SUCCESS)
        val plainTree =
          ComponentTreeExtractor.extract(
            previewId = "plain",
            methodFQN = "com.example.Plain",
            displayName = null,
            config = plainConfig,
            renderResult = plainResult,
            viewInfos = emptyList(),
          )
        assertThat(plainTree.components[0].children?.get(0)?.semantics?.get("text")).isEqualTo("Initial")
      } finally {
        Disposer.dispose(plainResult)
      }

      // 2. Preview rendering in component-tree-only mode (renderImage = false) only inflates without executing layoutlib callbacks.
      val inflateOnlyResults =
        renderer.render(
          xmlPreviewScreenshot("com.example.PreviewKt.AttachCallbackInflateOnly", xmlLayout),
          inflateOnlyOutputDir.absolutePath,
          includeComponentTree = true,
          renderImage = false,
        )
      assertThat(inflateOnlyResults).hasSize(1)
      assertThat(inflateOnlyResults[0].error).isNull()
      assertThat(inflateOnlyResults[0].imagePath).isEmpty()
      val inflateOnlyTreeFile = Paths.get(inflateOnlyOutputDir.absolutePath, inflateOnlyResults[0].componentTreePath!!)
      val parsedInflateOnlyTree = Files.newBufferedReader(inflateOnlyTreeFile, StandardCharsets.UTF_8).use { readComponentTreeFromJson(it) }
      assertThat(parsedInflateOnlyTree.components[0].children?.get(0)?.semantics?.get("text")).isEqualTo("Initial")

      // 3. Preview rendering with renderImage = true executes callbacks and restores the render thread's uncaughtExceptionHandler
      // afterwards.
      val previewResults =
        renderer.render(
          xmlPreviewScreenshot("com.example.PreviewKt.AttachCallback", xmlLayout),
          outputDir.absolutePath,
          includeComponentTree = true,
          renderImage = true,
        )
      assertThat(previewResults).hasSize(1)
      assertThat(previewResults[0].error).isNull()
      val previewTreeFile = Paths.get(outputDir.absolutePath, previewResults[0].componentTreePath!!)
      val parsedPreviewTree = Files.newBufferedReader(previewTreeFile, StandardCharsets.UTF_8).use { readComponentTreeFromJson(it) }
      assertThat(parsedPreviewTree.components[0].children?.get(0)?.semantics?.get("text")).isEqualTo("Updated by callback")

      val handlerAfter = executor.runAsyncAction(Callable { Thread.currentThread().uncaughtExceptionHandler }).get(10, TimeUnit.SECONDS)
      assertSame(handlerBefore, handlerAfter)
    }
  }

  @Test
  fun testPoisonedRenderThread_failsRemainingPreviewsFastNamesCulpritWithDisplayNameAndRecoversAfterRefreshClassPath() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val blockLatch = CountDownLatch(1)
    val latchKey = "com.android.tools.render.test.poisonLatch"
    System.getProperties()[latchKey] = blockLatch
    try {
      val blockingViewDir =
        compileCustomView(
          "com.example",
          "BlockingCallbackTextView",
          """
          package com.example;
          import android.content.Context;
          import android.util.AttributeSet;
          import android.widget.TextView;
          import java.util.concurrent.CountDownLatch;
          import java.util.concurrent.TimeUnit;
          public class BlockingCallbackTextView extends TextView {
            public BlockingCallbackTextView(Context context, AttributeSet attrs) {
              super(context, attrs);
              setText("Initial");
            }
            @Override
            protected void onAttachedToWindow() {
              super.onAttachedToWindow();
              post(() -> {
                CountDownLatch latch = (CountDownLatch) System.getProperties().get("$latchKey");
                long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (latch.getCount() > 0 && System.nanoTime() < deadlineNanos) {
                  try {
                    latch.await(50, TimeUnit.MILLISECONDS);
                  } catch (InterruptedException ignored) {
                    // Ignore RenderTask's 500ms per-step interruption to simulate non-interruptible main-thread work.
                  }
                }
              });
            }
          }
          """
            .trimIndent(),
        )
      // language=xml
      val blockingXmlLayout =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" >
            <com.example.BlockingCallbackTextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </LinearLayout>
        """
          .trimIndent()
      // language=xml
      val simpleXmlLayout =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" >
            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="After" />
        </LinearLayout>
        """
          .trimIndent()

      val bootstrapper =
        RenderEnvironmentBootstrapper(
          fontsPath = null,
          resourceApkPath = null,
          namespace = "com.example",
          classPath = emptyList(),
          projectClassPath = listOf(blockingViewDir.absolutePath),
          layoutlibPath = layoutlibPath.absolutePathString(),
        )
      val outputDir = tmpFolder.newFolder("output_poisoned_thread")
      bootstrapper.bootstrap().use { renderer ->
        try {
          renderer.callbackRunner.callbacksTimeoutMs = 150L
          renderer.callbackRunner.renderThreadRecoveryTimeoutMs = 100L

          val culpritResult =
            renderer.render(
              xmlPreviewScreenshot(
                fqn = "com.example.PreviewKt.BlockingPreview",
                xmlLayout = blockingXmlLayout,
                displayName = "param <0> & 1",
              ),
              outputDir.absolutePath,
            )
          val afterResult1 =
            renderer.render(
              xmlPreviewScreenshot("com.example.PreviewKt.AfterPreview1", simpleXmlLayout),
              outputDir.absolutePath,
            )
          val afterResult2 =
            renderer.render(
              xmlPreviewScreenshot("com.example.PreviewKt.AfterPreview2", simpleXmlLayout),
              outputDir.absolutePath,
            )

          assertThat(culpritResult).hasSize(1)
          assertThat(culpritResult[0].error).isNotNull()
          assertThat(culpritResult[0].error!!.problems.map { it.html }.joinToString("\n")).contains("did not complete within 150ms")

          // When previewId starts with methodFQN, previewId is used directly without repeating methodFQN, followed by [displayName],
          // with XML/HTML entities (< and &) escaped by RenderLogger.error -> HtmlBuilder.add.
          val expectedCulpritName = "com.example.PreviewKt.BlockingPreview_screenshot [param &lt;0> &amp; 1]"
          assertThat(afterResult1).hasSize(1)
          assertThat(afterResult1[0].error).isNotNull()
          assertThat(afterResult1[0].error!!.problems.map { it.html }.joinToString("\n")).contains(expectedCulpritName)

          assertThat(afterResult2).hasSize(1)
          assertThat(afterResult2[0].error).isNotNull()
          assertThat(afterResult2[0].error!!.problems.map { it.html }.joinToString("\n")).contains(expectedCulpritName)

          // Release the blocked render thread and verify that once the uncancelled pendingProbe completes on the render thread, the next
          // preview in the SAME batch recovers automatically even before refreshClassPath() is called.
          blockLatch.countDown()
          RenderService.getRenderAsyncActionExecutor().runAsyncAction {}.get(10, TimeUnit.SECONDS)

          val recoveredInBatchResult =
            renderer.render(
              xmlPreviewScreenshot("com.example.PreviewKt.RecoveredInBatch", simpleXmlLayout),
              outputDir.absolutePath,
              includeComponentTree = true,
              renderImage = true,
            )
          assertThat(recoveredInBatchResult).hasSize(1)
          assertThat(recoveredInBatchResult[0].error).isNull()

          // Also verify previewName formatting when previewId does not start with methodFQN ("methodFQN (previewId) [displayName]") and
          // recovery after refreshClassPath().
          val secondLatch = CountDownLatch(1)
          System.getProperties()[latchKey] = secondLatch
          try {
            val customIdCulprit =
              renderer.render(
                xmlPreviewScreenshot(
                  fqn = "com.example.PreviewKt.BlockingPreview",
                  xmlLayout = blockingXmlLayout,
                  previewId = "custom_preview_id",
                  displayName = "param 1",
                ),
                outputDir.absolutePath,
              )
            assertThat(customIdCulprit).hasSize(1)
            assertThat(customIdCulprit[0].error).isNotNull()

            val afterCustomId =
              renderer.render(
                xmlPreviewScreenshot("com.example.PreviewKt.AfterCustomId", simpleXmlLayout),
                outputDir.absolutePath,
              )
            assertThat(afterCustomId).hasSize(1)
            assertThat(afterCustomId[0].error).isNotNull()
            assertThat(afterCustomId[0].error!!.problems.map { it.html }.joinToString("\n"))
              .contains("com.example.PreviewKt.BlockingPreview (custom_preview_id) [param 1]")

            renderer.refreshClassPath(emptyList(), listOf(blockingViewDir.absolutePath))
            secondLatch.countDown()
            RenderService.getRenderAsyncActionExecutor().runAsyncAction {}.get(10, TimeUnit.SECONDS)

            val recoveredAfterRefreshResult =
              renderer.render(
                xmlPreviewScreenshot("com.example.PreviewKt.AfterRefresh", simpleXmlLayout),
                outputDir.absolutePath,
                includeComponentTree = true,
                renderImage = true,
              )
            assertThat(recoveredAfterRefreshResult).hasSize(1)
            assertThat(recoveredAfterRefreshResult[0].error).isNull()
          } finally {
            secondLatch.countDown()
          }
        } finally {
          blockLatch.countDown()
          runCatching { RenderService.getRenderAsyncActionExecutor().runAsyncAction {}.get(10, TimeUnit.SECONDS) }
        }
      }
    } finally {
      blockLatch.countDown()
      System.getProperties().remove(latchKey)
      runCatching { RenderService.getRenderAsyncActionExecutor().runAsyncAction {}.get(10, TimeUnit.SECONDS) }
    }
  }

  @Test
  fun testReleaseRender_disposesComposeViewAdapterAndClearsDispatcherWorkEvenWhenRenderFailsOrNoAdapterRecorded() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val simulatedClockNanos = AtomicLong(0L)
    val disposedAdapters = AtomicInteger(0)
    val leakedTasksEnqueued = AtomicInteger(0)
    val leakedTasksExecuted = AtomicInteger(0)
    val disposedBeforeHealthyInflate = AtomicInteger(-1)
    val queuedBeforeHealthyInflate = AtomicInteger(-1)
    val lastDispatcherClassLoader = AtomicReference<ClassLoader?>(null)
    val renderThreadEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
    val clockKey = "com.android.tools.render.test.releaseRenderClockNanos"
    val disposedKey = "com.android.tools.render.test.disposedAdapters"
    val enqueuedKey = "com.android.tools.render.test.leakedTasksEnqueued"
    val leakedKey = "com.android.tools.render.test.leakedTasksExecuted"
    val disposedAtInflateKey = "com.android.tools.render.test.disposedBeforeHealthyInflate"
    val queuedAtInflateKey = "com.android.tools.render.test.queuedBeforeHealthyInflate"
    val classLoaderKey = "com.android.tools.render.test.lastDispatcherClassLoader"
    val eventsKey = "com.android.tools.render.test.renderThreadEvents"
    System.getProperties()[clockKey] = simulatedClockNanos
    System.getProperties()[disposedKey] = disposedAdapters
    System.getProperties()[enqueuedKey] = leakedTasksEnqueued
    System.getProperties()[leakedKey] = leakedTasksExecuted
    System.getProperties()[disposedAtInflateKey] = disposedBeforeHealthyInflate
    System.getProperties()[queuedAtInflateKey] = queuedBeforeHealthyInflate
    System.getProperties()[classLoaderKey] = lastDispatcherClassLoader
    System.getProperties()[eventsKey] = renderThreadEvents
    try {
      val customDir =
        compileCustomClasses(
          Triple(
            "androidx.compose.ui.platform",
            "AndroidUiDispatcher",
            """
            package androidx.compose.ui.platform;
            import android.os.Handler;
            import android.os.Looper;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.atomic.AtomicInteger;
            import java.util.concurrent.atomic.AtomicReference;
            public class AndroidUiDispatcher {
              public static final class MainLazy {
                private AndroidUiDispatcher value;
                public synchronized boolean isInitialized() {
                  return value != null;
                }
                public synchronized Object getValue() {
                  if (value == null) {
                    value = new AndroidUiDispatcher();
                  }
                  return value;
                }
              }
              public static final MainLazy Main${'$'}delegate = new MainLazy();
              public static AndroidUiDispatcher getMain() {
                @SuppressWarnings("unchecked")
                AtomicReference<ClassLoader> ref =
                    (AtomicReference<ClassLoader>) System.getProperties().get("$classLoaderKey");
                if (ref != null) {
                  ref.set(AndroidUiDispatcher.class.getClassLoader());
                }
                return (AndroidUiDispatcher) Main${'$'}delegate.getValue();
              }
              public final Object lock = new Object();
              public boolean scheduledTrampolineDispatch = false;
              public boolean scheduledFrameDispatch = false;
              public final List<Runnable> toRunTrampolined = new ArrayList<>();
              public final List<Object> toRunOnFrame = new ArrayList<>();
              public final Handler handler = new Handler(Looper.getMainLooper());
              public final Runnable dispatchCallback = new Runnable() {
                @Override
                public void run() {
                  List<Runnable> batch;
                  synchronized (lock) {
                    scheduledTrampolineDispatch = false;
                    if (toRunTrampolined.isEmpty()) return;
                    batch = new ArrayList<>(toRunTrampolined);
                    toRunTrampolined.clear();
                  }
                  for (Runnable task : batch) {
                    task.run();
                  }
                }
              };
              public void dispatch(Object context, Runnable block) {
                synchronized (lock) {
                  toRunTrampolined.add(block);
                  if (!scheduledTrampolineDispatch) {
                    scheduledTrampolineDispatch = true;
                    handler.post(dispatchCallback);
                  }
                }
              }
              public void enqueueLeakedWork(Runnable block) {
                synchronized (lock) {
                  AtomicInteger enqueued = (AtomicInteger) System.getProperties().get("$enqueuedKey");
                  if (enqueued != null) {
                    enqueued.incrementAndGet();
                  }
                  toRunTrampolined.add(block);
                  scheduledTrampolineDispatch = true;
                  scheduledFrameDispatch = true;
                }
              }
              public int queuedCount() {
                synchronized (lock) {
                  return toRunTrampolined.size() + toRunOnFrame.size();
                }
              }
            }
            """
              .trimIndent(),
          ),
          Triple(
            "androidx.compose.ui.tooling",
            "ComposeViewAdapter",
            """
            package androidx.compose.ui.tooling;
            import android.content.Context;
            import android.graphics.Canvas;
            import android.util.AttributeSet;
            import android.widget.TextView;
            import androidx.compose.ui.platform.AndroidUiDispatcher;
            import java.util.List;
            import java.util.concurrent.TimeUnit;
            import java.util.concurrent.atomic.AtomicInteger;
            import java.util.concurrent.atomic.AtomicLong;
            public class ComposeViewAdapter extends TextView {
              private boolean attachedOnce = false;
              private boolean postedRound1 = false;
              private boolean leakedInRound1 = false;
              public ComposeViewAdapter(Context context, AttributeSet attrs) {
                super(context, attrs);
                setText("Initial");
              }
              @Override
              protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                if (attachedOnce) return;
                attachedOnce = true;
                Object tag = getTag();
                if ("timeout_before_render".equals(tag)) {
                  // Touch AndroidUiDispatcher.getMain() during inflation, then in Round 1's first callback step enqueue leaked work
                  // and advance the simulated clock past callbacksTimeoutMs so renderExecutingCallbacks returns before session.render()
                  // is ever called.
                  AndroidUiDispatcher.getMain();
                  post(() -> {
                    @SuppressWarnings("unchecked")
                    List<String> events = (List<String>) System.getProperties().get("$eventsKey");
                    Thread current = Thread.currentThread();
                    boolean scopedHandlerInstalled = current.getUncaughtExceptionHandler() != current.getThreadGroup();
                    events.add("timeout_callback_scoped_handler=" + scopedHandlerInstalled);
                    AtomicInteger leaked = (AtomicInteger) System.getProperties().get("$leakedKey");
                    AndroidUiDispatcher.getMain().enqueueLeakedWork(leaked::incrementAndGet);
                    AtomicLong clock = (AtomicLong) System.getProperties().get("$clockKey");
                    clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
                  });
                } else if ("fail_on_draw".equals(tag)) {
                  @SuppressWarnings("unchecked")
                  List<String> events = (List<String>) System.getProperties().get("$eventsKey");
                  AtomicInteger disposed = (AtomicInteger) System.getProperties().get("$disposedKey");
                  events.add(
                      "fail_on_draw_inflate_disposed="
                          + disposed.get()
                          + ",queued="
                          + AndroidUiDispatcher.getMain().queuedCount());
                } else if ("healthy".equals(tag)) {
                  @SuppressWarnings("unchecked")
                  List<String> events = (List<String>) System.getProperties().get("$eventsKey");
                  events.add("healthy_inflate");
                  AtomicInteger disposed = (AtomicInteger) System.getProperties().get("$disposedKey");
                  AtomicInteger disposedAtInflate = (AtomicInteger) System.getProperties().get("$disposedAtInflateKey");
                  AtomicInteger queuedAtInflate = (AtomicInteger) System.getProperties().get("$queuedAtInflateKey");
                  disposedAtInflate.compareAndSet(-1, disposed.get());
                  queuedAtInflate.compareAndSet(-1, AndroidUiDispatcher.getMain().queuedCount());
                  AndroidUiDispatcher.getMain().dispatch(null, () -> setText("ComposeViewAdapter effect ran"));
                }
              }
              @Override
              protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if ("fail_on_draw".equals(getTag())) {
                  if (!"Round 1".equals(getText().toString())) {
                    if (!postedRound1) {
                      postedRound1 = true;
                      post(() -> setText("Round 1"));
                    }
                  } else {
                    if (!leakedInRound1) {
                      leakedInRound1 = true;
                      AtomicInteger leaked = (AtomicInteger) System.getProperties().get("$leakedKey");
                      AndroidUiDispatcher.getMain().enqueueLeakedWork(leaked::incrementAndGet);
                    }
                    throw new RuntimeException("Simulated onDraw failure in ComposeViewAdapter");
                  }
                }
              }
              public void dispose() {
                if ("timeout_before_render".equals(getTag())) {
                  @SuppressWarnings("unchecked")
                  List<String> events = (List<String>) System.getProperties().get("$eventsKey");
                  Thread current = Thread.currentThread();
                  boolean handlerRestoredBeforeDispose = current.getUncaughtExceptionHandler() == current.getThreadGroup();
                  events.add("timeout_adapter_dispose_handler_restored=" + handlerRestoredBeforeDispose);
                }
                AtomicInteger disposed = (AtomicInteger) System.getProperties().get("$disposedKey");
                disposed.incrementAndGet();
              }
            }
            """
              .trimIndent(),
          ),
          Triple(
            "com.example",
            "PlainDispatcherLeakingTextView",
            """
            package com.example;
            import android.content.Context;
            import android.graphics.Canvas;
            import android.util.AttributeSet;
            import android.widget.TextView;
            import androidx.compose.ui.platform.AndroidUiDispatcher;
            import java.util.concurrent.atomic.AtomicInteger;
            public class PlainDispatcherLeakingTextView extends TextView {
              private boolean postedRound1 = false;
              private boolean leakedInRound1 = false;
              public PlainDispatcherLeakingTextView(Context context, AttributeSet attrs) {
                super(context, attrs);
                setText("Initial");
              }
              @Override
              protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (!"Round 1".equals(getText().toString())) {
                  if (!postedRound1) {
                    postedRound1 = true;
                    post(() -> setText("Round 1"));
                  }
                } else {
                  if (!leakedInRound1) {
                    leakedInRound1 = true;
                    AtomicInteger leaked = (AtomicInteger) System.getProperties().get("$leakedKey");
                    AndroidUiDispatcher.getMain().enqueueLeakedWork(leaked::incrementAndGet);
                  }
                  throw new RuntimeException("Simulated onDraw failure in PlainDispatcherLeakingTextView");
                }
              }
            }
            """
              .trimIndent(),
          ),
        )

      // language=xml
      val timeoutBeforeRenderLayout =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" >
            <androidx.compose.ui.tooling.ComposeViewAdapter
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:tag="timeout_before_render" />
        </LinearLayout>
        """
          .trimIndent()
      // language=xml
      val failingAdapterLayout =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" >
            <androidx.compose.ui.tooling.ComposeViewAdapter
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:tag="fail_on_draw" />
        </LinearLayout>
        """
          .trimIndent()
      // language=xml
      val failingPlainViewLayout =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" >
            <com.example.PlainDispatcherLeakingTextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </LinearLayout>
        """
          .trimIndent()
      // language=xml
      val healthyAdapterLayout =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" >
            <androidx.compose.ui.tooling.ComposeViewAdapter
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:tag="healthy" />
        </LinearLayout>
        """
          .trimIndent()

      val bootstrapper =
        RenderEnvironmentBootstrapper(
          fontsPath = null,
          resourceApkPath = null,
          namespace = "com.example",
          classPath = emptyList(),
          projectClassPath = listOf(customDir.absolutePath),
          layoutlibPath = layoutlibPath.absolutePathString(),
        )
      val outputDir = tmpFolder.newFolder("output_dispatcher_cleanup")
      bootstrapper.bootstrap().use { renderer ->
        renderer.callbackRunner.callbacksTimeoutMs = 5_000L
        renderer.callbackRunner.nanoTime = { System.nanoTime() + simulatedClockNanos.get() }

        // 1a. Preview 1a inflates ComposeViewAdapter (recorded right after inflate()), queues leaked work on AndroidUiDispatcher.Main
        // during Round 1 callbacks, and times out before session.render() is ever called.
        val timeoutBeforeRenderResults =
          renderer.render(
            xmlPreviewScreenshot("com.example.PreviewKt.TimeoutBeforeRenderPreview", timeoutBeforeRenderLayout),
            outputDir.absolutePath,
          )
        assertThat(timeoutBeforeRenderResults).hasSize(1)
        assertThat(timeoutBeforeRenderResults[0].error).isNotNull()

        // 1b. Preview 1b inflates ComposeViewAdapter, then throws during round 2's render() after queueing leftover work on
        // AndroidUiDispatcher.Main.
        val failingAdapterResults =
          renderer.render(
            xmlPreviewScreenshot("com.example.PreviewKt.FailingAdapterPreview", failingAdapterLayout),
            outputDir.absolutePath,
          )
        assertThat(failingAdapterResults).hasSize(1)
        assertThat(failingAdapterResults[0].error).isNotNull()

        // 2. Preview 2 inflates a plain View (so composeViewAdapters is empty) whose round 2 render() queues leftover work on
        // AndroidUiDispatcher.Main and throws. releaseRender must still clear AndroidUiDispatcher.Main via the module class loader.
        val failingPlainResults =
          renderer.render(
            xmlPreviewScreenshot("com.example.PreviewKt.FailingPlainPreview", failingPlainViewLayout),
            outputDir.absolutePath,
          )
        assertThat(failingPlainResults).hasSize(1)
        assertThat(failingPlainResults[0].error).isNotNull()

        // 3. Preview 3 renders immediately back-to-back on the same Renderer and module class loader (without manual render-thread
        // barriers between previews). When Preview 3 inflates on the render thread, Preview 1a and 1b's ComposeViewAdapters must
        // already be disposed and Preview 1a, 1b, and 2's leaked work must already be cleared in FIFO order.
        val healthyResults =
          renderer.render(
            xmlPreviewScreenshot("com.example.PreviewKt.HealthyAdapterPreview", healthyAdapterLayout),
            outputDir.absolutePath,
            includeComponentTree = true,
            renderImage = true,
          )
        assertThat(healthyResults).hasSize(1)
        assertThat(healthyResults[0].error).isNull()
        val treeFile = Paths.get(outputDir.absolutePath, healthyResults[0].componentTreePath!!)
        val parsedTree = Files.newBufferedReader(treeFile, StandardCharsets.UTF_8).use { readComponentTreeFromJson(it) }
        assertThat(parsedTree.components[0].children?.get(0)?.semantics?.get("text")).isEqualTo("ComposeViewAdapter effect ran")

        val firstBatchClassLoader = lastDispatcherClassLoader.get()
        assertNotNull("Expected AndroidUiDispatcher.getMain() to have been called", firstBatchClassLoader)
        assertEquals(
          "Preview 1a and 1b ComposeViewAdapters must already be disposed in FIFO order before Preview 3 inflates",
          2,
          disposedBeforeHealthyInflate.get(),
        )
        assertEquals(
          "Leaked AndroidUiDispatcher.Main work must already be cleared in FIFO order before Preview 3 inflates",
          0,
          queuedBeforeHealthyInflate.get(),
        )
        // Verify the exact FIFO ordering on RenderingTopic.NOT_SPECIFIED:
        // (1) timed-out callback step runs with scoped uncaughtExceptionHandler installed,
        // (2) async uncaughtExceptionHandler restore runs before releaseRender's ComposeViewAdapter.dispose(),
        // (3) releaseRender's ComposeViewAdapter.dispose() + clearComposeUiDispatcherWork() and the recovery probe run before the next
        //     preview (Preview 1b / fail_on_draw) inflates,
        // (4) Preview 1b and Preview 2's releaseRender run before Preview 3 (healthy) inflates.
        assertEquals(
          listOf(
            "timeout_callback_scoped_handler=true",
            "timeout_adapter_dispose_handler_restored=true",
            "fail_on_draw_inflate_disposed=1,queued=0",
            "healthy_inflate",
          ),
          renderThreadEvents.toList(),
        )

        RenderService.getRenderAsyncActionExecutor().runAsyncAction {}.get(10, TimeUnit.SECONDS)
        assertEquals(3, leakedTasksEnqueued.get())
        assertEquals("Preview 1a, Preview 1b, and Preview 3 ComposeViewAdapters must be disposed", 3, disposedAdapters.get())
        assertEquals(
          "Leaked AndroidUiDispatcher.Main work from Previews 1a, 1b, and 2 must be cleared before Preview 3",
          0,
          leakedTasksExecuted.get(),
        )
        assertEquals(false, hasPendingComposeUiDispatcherWork(firstBatchClassLoader!!))

        // 4. Refreshing the classpath creates a fresh module class loader with its own AndroidUiDispatcher.Main.
        renderer.refreshClassPath(emptyList(), listOf(customDir.absolutePath))
        val afterRefreshResults =
          renderer.render(
            xmlPreviewScreenshot("com.example.PreviewKt.AfterRefreshHealthyPreview", healthyAdapterLayout),
            outputDir.absolutePath,
            includeComponentTree = true,
            renderImage = true,
          )
        assertThat(afterRefreshResults).hasSize(1)
        assertThat(afterRefreshResults[0].error).isNull()
        RenderService.getRenderAsyncActionExecutor().runAsyncAction {}.get(10, TimeUnit.SECONDS)
        assertEquals(4, disposedAdapters.get())
        assertEquals(0, leakedTasksExecuted.get())
        assertNotNull(lastDispatcherClassLoader.get())
        assertNotEquals(firstBatchClassLoader, lastDispatcherClassLoader.get())
      }
    } finally {
      System.getProperties().remove(clockKey)
      System.getProperties().remove(disposedKey)
      System.getProperties().remove(enqueuedKey)
      System.getProperties().remove(leakedKey)
      System.getProperties().remove(disposedAtInflateKey)
      System.getProperties().remove(queuedAtInflateKey)
      System.getProperties().remove(classLoaderKey)
      System.getProperties().remove(eventsKey)
    }
  }

  /**
   * Compiles custom Java classes for tests that exercise layoutlib's render thread.
   *
   * Note: Compiled classes are loaded by [DefaultModuleClassLoader], which is isolated from the test class loader, so tests pass shared
   * state (`AtomicLong`, `CountDownLatch`, etc.) through `System.getProperties()` keys and clean them up in `finally`. `JarTestSuite` runs
   * tests sequentially in a single JVM because layoutlib has a single render thread.
   */
  private fun compileCustomView(pkg: String, className: String, javaSource: String): File =
    compileCustomClasses(Triple(pkg, className, javaSource))

  private fun compileCustomClasses(vararg sources: Triple<String, String, String>): File {
    val srcDir = tmpFolder.newFolder()
    val outDir = tmpFolder.newFolder()
    val javaFiles = sources.map { (pkg, className, javaSource) ->
      val pkgDir = File(srcDir, pkg.replace('.', File.separatorChar)).apply { mkdirs() }
      File(pkgDir, "$className.java").apply { writeText(javaSource) }.absolutePath
    }
    val compiler = ToolProvider.getSystemJavaCompiler()
    assertNotNull("System Java compiler is required to compile custom View test fixtures", compiler)
    val layoutlibJar = Paths.get(Class.forName("android.view.View").protectionDomain.codeSource.location.toURI()).toString()
    val classpath = layoutlibJar + File.pathSeparator + System.getProperty("java.class.path")
    val args = (listOf("-cp", classpath, "-d", outDir.absolutePath) + javaFiles).toTypedArray()
    assertEquals(0, compiler.run(null, null, null, *args))
    return outDir
  }

  private fun xmlPreviewScreenshot(
    fqn: String,
    xmlLayout: String,
    previewId: String = "${fqn}_screenshot",
    displayName: String? = null,
  ): PreviewScreenshot =
    object : PreviewScreenshot {
      override val methodFQN = fqn
      override val previewParams = emptyMap<String, String>()
      override val previewId = previewId
      override val previewWrapperFqn: String? = null

      override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement {
        val delegate = ComposeScreenshot(methodFQN, emptyList(), previewParams, previewId).toPreviewElement(module)
        return object : ScreenshotPreviewElement, ConfigurablePreviewElement<Unit> by delegate {
          override fun resolveXmlLayouts(): Sequence<String> = sequenceOf(xmlLayout)

          override fun resolveLayouts(): Sequence<ResolvedScreenshotLayout> =
            sequenceOf(ResolvedScreenshotLayout(xmlLayout, displayName = displayName))
        }
      }
    }

  @Test
  fun testCloseDisposesModuleAndProjectIdempotently() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val renderer =
      RenderEnvironmentBootstrapper(
          fontsPath = null,
          resourceApkPath = null,
          namespace = "",
          classPath = emptyList(),
          projectClassPath = emptyList(),
          layoutlibPath = layoutlibPath.absolutePathString(),
        )
        .bootstrap()

    var moduleChildDisposed = false
    Disposer.register(renderer.module.parentDisposable) { moduleChildDisposed = true }
    assertThat(renderer.module.isDisposed).isFalse()
    assertThat(renderer.project.isDisposed).isFalse()

    renderer.close()

    assertThat(renderer.module.isDisposed).isTrue()
    assertThat(moduleChildDisposed).isTrue()
    assertThat(renderer.project.isDisposed).isTrue()

    // A second close() (or closing after module/project was already disposed) must be a safe no-op.
    renderer.close()
  }

  @Test
  fun testRenderPropagatesInterruptedExceptionAndFatalVirtualMachineError() {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val enteredKey = "renderer.test.interrupt.entered.${System.nanoTime()}"
    val releaseKey = "renderer.test.interrupt.release.${System.nanoTime()}"
    val secondInflatedKey = "renderer.test.interrupt.second.${System.nanoTime()}"
    val enteredLatch = CountDownLatch(1)
    val releaseLatch = CountDownLatch(1)
    val secondInflated = AtomicInteger(0)
    System.getProperties()[enteredKey] = enteredLatch
    System.getProperties()[releaseKey] = releaseLatch
    System.getProperties()[secondInflatedKey] = secondInflated

    val customDir =
      compileCustomClasses(
        Triple(
          "com.example.custom",
          "OomView",
          """
          package com.example.custom;
          import android.content.Context;
          import android.graphics.Canvas;
          import android.util.AttributeSet;
          import android.view.View;
          public class OomView extends View {
            public OomView(Context context, AttributeSet attrs) { super(context, attrs); }
            @Override protected void onDraw(Canvas canvas) {
              throw new OutOfMemoryError("simulated render-thread OOM");
            }
          }
          """
            .trimIndent(),
        ),
        Triple(
          "com.example.custom",
          "BlockingView",
          """
          package com.example.custom;
          import android.content.Context;
          import android.util.AttributeSet;
          import android.view.View;
          import java.util.concurrent.CountDownLatch;
          import java.util.concurrent.TimeUnit;
          public class BlockingView extends View {
            public BlockingView(Context context, AttributeSet attrs) {
              super(context, attrs);
              CountDownLatch entered = (CountDownLatch) System.getProperties().get("$enteredKey");
              CountDownLatch release = (CountDownLatch) System.getProperties().get("$releaseKey");
              entered.countDown();
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
            }
          }
          """
            .trimIndent(),
        ),
        Triple(
          "com.example.custom",
          "TrackingView",
          """
          package com.example.custom;
          import android.content.Context;
          import android.util.AttributeSet;
          import android.view.View;
          import java.util.concurrent.atomic.AtomicInteger;
          public class TrackingView extends View {
            public TrackingView(Context context, AttributeSet attrs) {
              super(context, attrs);
              ((AtomicInteger) System.getProperties().get("$secondInflatedKey")).incrementAndGet();
            }
          }
          """
            .trimIndent(),
        ),
      )

    val oomLayout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <com.example.custom.OomView xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="40dp"
          android:layout_height="40dp" />
      """
        .trimIndent()
    val blockingLayout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <com.example.custom.BlockingView xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="40dp"
          android:layout_height="40dp" />
      """
        .trimIndent()
    val trackingLayout =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <com.example.custom.TrackingView xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="40dp"
          android:layout_height="40dp" />
      """
        .trimIndent()

    val executor = Executors.newSingleThreadExecutor()
    try {
      RenderEnvironmentBootstrapper(
          fontsPath = null,
          resourceApkPath = null,
          namespace = "com.example.custom",
          classPath = emptyList(),
          projectClassPath = listOf(customDir.absolutePath),
          layoutlibPath = layoutlibPath.absolutePathString(),
        )
        .bootstrap()
        .use { renderer ->
          val outputDir = tmpFolder.newFolder().absolutePath

          // 1. Recoverable Errors (StackOverflowError, LinkageError) remain isolated per-preview errors.
          val stackOverflowResults =
            renderer.render(
              FailingPreviewScreenshot("com.example.PreviewKt.Recursive", "Recursive") { StackOverflowError("unbounded recursion") },
              outputDir,
            )
          assertThat(stackOverflowResults.single().error).isNotNull()

          val linkageResults =
            renderer.render(
              FailingPreviewScreenshot("com.example.PreviewKt.Linkage", "Linkage") { LinkageError("missing symbol") },
              outputDir,
            )
          assertThat(linkageResults.single().error).isNotNull()

          // 2. Direct and reflection-wrapped OutOfMemoryErrors during preview setup propagate out of Renderer.render().
          assertThrows(OutOfMemoryError::class.java) {
            renderer.render(FailingPreviewScreenshot("com.example.PreviewKt.Oom", "Oom") { OutOfMemoryError("direct OOM") }, outputDir)
          }
          assertThrows(OutOfMemoryError::class.java) {
            renderer.render(
              FailingPreviewScreenshot("com.example.PreviewKt.WrappedOom", "WrappedOom") {
                InvocationTargetException(OutOfMemoryError("wrapped OOM"))
              },
              outputDir,
            )
          }

          // 3. OutOfMemoryError thrown on the Layoutlib render thread propagates out of Renderer.render() instead of being swallowed.
          val renderThreadOom =
            assertThrows(OutOfMemoryError::class.java) {
              renderer.render(xmlPreviewScreenshot("com.example.PreviewKt.RenderThreadOom", oomLayout), outputDir)
            }
          assertThat(renderThreadOom).hasMessageThat().contains("simulated render-thread OOM")

          // 4. Interrupting the caller thread aborts multi-layout rendering immediately and preserves the thread's interrupt status.
          val multiLayoutScreenshot =
            object : PreviewScreenshot {
              override val methodFQN = "com.example.PreviewKt.Multi"
              override val previewParams = emptyMap<String, String>()
              override val previewId = "Multi"
              override val previewWrapperFqn: String? = null

              override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement {
                val delegate = ComposeScreenshot(methodFQN, emptyList(), previewParams, previewId).toPreviewElement(module)
                return object : ScreenshotPreviewElement, ConfigurablePreviewElement<Unit> by delegate {
                  override fun resolveXmlLayouts(): Sequence<String> = sequenceOf(blockingLayout, trackingLayout)

                  override fun resolveLayouts(): Sequence<ResolvedScreenshotLayout> =
                    sequenceOf(ResolvedScreenshotLayout(blockingLayout, "first"), ResolvedScreenshotLayout(trackingLayout, "second"))
                }
              }
            }

          val callerThreadRef = AtomicReference<Thread>(null)
          val callerInterruptedFlag = AtomicReference<Boolean>(null)
          val renderFuture =
            executor.submit(
              Callable<Throwable?> {
                callerThreadRef.set(Thread.currentThread())
                try {
                  renderer.render(multiLayoutScreenshot, outputDir)
                  null
                } catch (t: Throwable) {
                  callerInterruptedFlag.set(Thread.currentThread().isInterrupted)
                  Thread.interrupted()
                  t
                }
              }
            )

          assertWithMessage("Render thread did not enter BlockingView").that(enteredLatch.await(5, TimeUnit.SECONDS)).isTrue()
          callerThreadRef.get()!!.interrupt()
          val thrown = renderFuture.get(5, TimeUnit.SECONDS)
          assertThat(thrown).isInstanceOf(InterruptedException::class.java)
          assertWithMessage("Caller thread interrupt status must remain set").that(callerInterruptedFlag.get()).isTrue()
          releaseLatch.countDown()
          RenderService.getRenderAsyncActionExecutor().runAsyncAction {}.get(10, TimeUnit.SECONDS)
          assertWithMessage("Second layout must not be inflated after interruption").that(secondInflated.get()).isEqualTo(0)
        }
    } finally {
      releaseLatch.countDown()
      executor.shutdownNow()
      RenderService.getRenderAsyncActionExecutor().runAsyncAction {}.get(10, TimeUnit.SECONDS)
      System.getProperties().remove(enteredKey)
      System.getProperties().remove(releaseKey)
      System.getProperties().remove(secondInflatedKey)
    }
  }

  private fun renderFailingScreenshot(screenshot: FailingPreviewScreenshot): PreviewScreenshotResult {
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
    val outputDir = tmpFolder.newFolder().absolutePath

    val results = bootstrapper.bootstrap().use { renderer -> renderer.render(screenshot, outputDir) }

    assertEquals(1, results.size)
    return results[0]
  }
}

/** A [PreviewScreenshot] whose preview element cannot be created: [toPreviewElement] throws the result of [failure]. */
private data class FailingPreviewScreenshot(
  override val methodFQN: String,
  override val previewId: String,
  override val previewParams: Map<String, String> = emptyMap(),
  override val previewWrapperFqn: String? = null,
  private val failure: () -> Throwable,
) : PreviewScreenshot {
  override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement = throw failure()
}
