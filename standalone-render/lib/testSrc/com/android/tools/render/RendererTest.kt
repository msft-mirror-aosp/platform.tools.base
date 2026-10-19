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
import com.android.tools.render.common.ScreenshotPreviewElement
import com.android.tools.render.common.readComponentTreeFromJson
import com.android.tools.render.common.toScreenshotError
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.render.discovery.SamplePreviewTarget
import com.android.tools.rendering.compose.ComposeViewInfo
import com.android.tools.rendering.compose.PxBounds
import com.android.tools.rendering.compose.SourceLocationImpl
import com.android.tools.res.FrameworkOverlay
import com.google.common.truth.Truth.assertThat
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
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
