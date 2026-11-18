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

package com.android.tools.render

import com.android.ide.common.rendering.api.Result
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.testutils.TestUtils
import com.android.tools.configurations.Configuration
import com.android.tools.preview.ConfigurablePreviewElement
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.preview.applyTo
import com.android.tools.preview.config.DimUnit
import com.android.tools.preview.config.ExpandAxis
import com.android.tools.preview.config.ExpandSettings
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.ResolvedScreenshotLayout
import com.android.tools.render.common.ScreenshotPreviewElement
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.rendering.compose.supportedComposePreviewDeviceSpecParameters
import com.android.tools.rendering.compose.supportsExpand
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.openapi.util.Disposer
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.io.path.absolutePathString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ExpandSupportTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @get:Rule val renderTest = RenderTestRule()

  /**
   * Generates bytecode for:
   * ```java
   * package androidx.compose.ui.tooling;
   *
   * public final class PreviewDeviceSpec {
   *   public static final String SUPPORTED_PARAMETERS = "<supportedParameters>";
   * }
   * ```
   */
  private fun previewDeviceSpecBytes(supportedParameters: String): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
      "androidx/compose/ui/tooling/PreviewDeviceSpec",
      null,
      "java/lang/Object",
      null,
    )
    writer
      .visitField(
        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
        "SUPPORTED_PARAMETERS",
        "Ljava/lang/String;",
        null,
        supportedParameters,
      )
      .visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
  }

  /** Creates a jar standing for a version of ui-tooling declaring [supportedParameters] (or without `PreviewDeviceSpec` if null). */
  private fun createUiToolingJar(supportedParameters: String? = null): File {
    val jar = temporaryFolder.newFile()
    JarOutputStream(jar.outputStream()).use { out ->
      out.putNextEntry(JarEntry("androidx/compose/ui/tooling/PreviewUtilsKt.class"))
      out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
      out.closeEntry()
      if (supportedParameters != null) {
        out.putNextEntry(JarEntry("androidx/compose/ui/tooling/PreviewDeviceSpec.class"))
        out.write(previewDeviceSpecBytes(supportedParameters))
        out.closeEntry()
      }
    }
    return jar
  }

  /** Whether the ui-tooling version in this [ClassLoader] declares `expand`, the check [Renderer] uses before expanding a preview. */
  private val ClassLoader.isExpandSupported: Boolean
    get() = supportedComposePreviewDeviceSpecParameters().supportsExpand()

  private fun isExpandSupported(classPath: List<File>): Boolean {
    val manager = StandaloneModuleClassLoaderManager(classPath.map { it.absolutePath }, emptyList())
    try {
      return manager.getShared(Renderer::class.java.classLoader).classLoader.isExpandSupported
    } finally {
      manager.close()
    }
  }

  private fun <T> withRenderer(classPath: List<File>, block: (Renderer) -> T): T {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "",
        classPath = classPath.map { it.absolutePath },
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    return bootstrapper.bootstrap().use(block)
  }

  private fun deviceConfigModifier(deviceSpec: String): (Configuration) -> Unit {
    val element =
      SingleComposePreviewElementInstance.forTesting<Unit>(
        "com.example.ExpandPreviewKt.Preview",
        configuration = PreviewConfiguration.cleanAndGet(device = deviceSpec),
      )
    return element::applyTo
  }

  private fun solidViewLayout(widthDp: Int, heightDp: Int, colorHex: String = "#FFFF0000"): String =
    """
    <?xml version="1.0" encoding="utf-8"?>
    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical">
        <View
            android:layout_width="${widthDp}dp"
            android:layout_height="${heightDp}dp"
            android:background="$colorHex" />
    </LinearLayout>
    """
      .trimIndent()

  private fun xmlPreviewScreenshot(
    fqn: String,
    xmlLayout: String,
    previewParams: Map<String, String>,
    previewId: String = "${fqn}_screenshot",
    displayNames: List<String?> = listOf(null),
  ): PreviewScreenshot =
    object : PreviewScreenshot {
      override val methodFQN = fqn
      override val previewParams = previewParams
      override val previewId = previewId
      override val previewWrapperFqn: String? = null

      override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement {
        val delegate = ComposeScreenshot(methodFQN, emptyList(), previewParams, previewId).toPreviewElement(module)
        return object : ScreenshotPreviewElement, ConfigurablePreviewElement<Unit> by delegate {
          override fun resolveXmlLayouts(): Sequence<String> = displayNames.asSequence().map { xmlLayout }

          override fun resolveLayouts(): Sequence<ResolvedScreenshotLayout> =
            displayNames.asSequence().map { displayName ->
              ResolvedScreenshotLayout(
                xmlLayout = xmlLayout,
                displayName = displayName,
                deviceSpec = delegate.configuration.deviceSpec.takeIf { it.isNotEmpty() },
              )
            }
        }
      }
    }

  @Test
  fun testExpandIsSupportedWhenDeclaredInPreviewDeviceSpec() {
    val uiTooling = createUiToolingJar("windowPosture,expand,maxHeight,maxWidth")
    val mixedCaseUiTooling = createUiToolingJar("windowPosture,EXPAND")

    assertThat(isExpandSupported(listOf(uiTooling))).isTrue()
    assertThat(isExpandSupported(listOf(mixedCaseUiTooling))).isTrue()
  }

  @Test
  fun testExpandIsSupportedWhenPreviewDeviceSpecIsInTheProjectClassPath() {
    val classesDir = temporaryFolder.newFolder()
    File(classesDir, "androidx/compose/ui/tooling/PreviewDeviceSpec.class").apply {
      parentFile.mkdirs()
      writeBytes(previewDeviceSpecBytes("expand"))
    }
    val manager = StandaloneModuleClassLoaderManager(emptyList(), listOf(classesDir.absolutePath))
    try {
      assertThat(manager.getShared(Renderer::class.java.classLoader).classLoader.isExpandSupported).isTrue()
    } finally {
      manager.close()
    }
  }

  @Test
  fun testExpandIsNotSupportedByOlderUiTooling() {
    val uiToolingWithoutExpand = createUiToolingJar("windowPosture,pointerPrecision")
    val uiToolingWithoutPreviewDeviceSpec = createUiToolingJar()

    assertThat(isExpandSupported(listOf(uiToolingWithoutExpand))).isFalse()
    assertThat(isExpandSupported(listOf(uiToolingWithoutPreviewDeviceSpec))).isFalse()
    assertThat(isExpandSupported(emptyList())).isFalse()
  }

  @Test
  fun testExpandSupportUpdatesAfterRefreshClassPath() {
    val uiTooling = createUiToolingJar("windowPosture")
    val manager = StandaloneModuleClassLoaderManager(listOf(uiTooling.absolutePath), emptyList())
    try {
      assertThat(manager.getShared(Renderer::class.java.classLoader).classLoader.isExpandSupported).isFalse()
      val updatedUiTooling = createUiToolingJar("windowPosture,expand,maxHeight,maxWidth")
      manager.refreshClassPath(listOf(updatedUiTooling.absolutePath), emptyList())
      assertThat(manager.getShared(Renderer::class.java.classLoader).classLoader.isExpandSupported).isTrue()
    } finally {
      manager.close()
    }
  }

  /**
   * Verifies that the LayoutLib framework classes expose the reflective fields and methods used by `ComposeViewAdapter`'s
   * `WindowConfigurationBoundsSupport` (`updateWindowConfigurationBounds`) when expanding a preview.
   */
  @Test
  fun testLayoutlibExposesWindowConfigurationBoundsMembers() {
    val classLoader = ExpandSupportTest::class.java.classLoader
    val configurationClass = Class.forName("android.content.res.Configuration", false, classLoader)
    val rectClass = Class.forName("android.graphics.Rect", false, classLoader)
    val viewClass = Class.forName("android.view.View", false, classLoader)
    val viewRootImplClass = Class.forName("android.view.ViewRootImpl", false, classLoader)

    val windowConfigField = configurationClass.getField("windowConfiguration")
    val windowConfigClass = windowConfigField.type
    assertThat(windowConfigClass.getMethod("setBounds", rectClass)).isNotNull()
    assertThat(windowConfigClass.getMethod("setMaxBounds", rectClass)).isNotNull()

    val getViewRootImplMethod = viewClass.getMethod("getViewRootImpl")
    assertThat(getViewRootImplMethod.returnType).isEqualTo(viewRootImplClass)

    val lastReportedField = viewRootImplClass.getDeclaredField("mLastReportedMergedConfiguration")
    val pendingField = viewRootImplClass.getDeclaredField("mPendingMergedConfiguration")
    assertThat(lastReportedField.type).isEqualTo(pendingField.type)

    val mergedConfigClass = lastReportedField.type
    for (getterName in listOf("getGlobalConfiguration", "getOverrideConfiguration", "getMergedConfiguration")) {
      val getter = mergedConfigClass.getMethod(getterName)
      assertWithMessage("$getterName return type").that(getter.returnType).isEqualTo(configurationClass)
    }

    val lastConfigField = viewRootImplClass.getDeclaredField("mLastConfigurationFromResources")
    assertThat(lastConfigField.type).isEqualTo(configurationClass)
  }

  @Test
  fun testRenderResolvedLayoutsExpandsVerticallyAndHorizontallyWhenUiToolingSupportsExpand() {
    val verticalDeviceSpec = "spec:width=200dp,height=200dp,dpi=160,expand=vertical,maxHeight=2000dp"
    val uiTooling = createUiToolingJar("windowPosture,expand,maxHeight,maxWidth")
    withRenderer(listOf(uiTooling)) { renderer ->
      val request =
        RenderRequest.withResolvedLayouts(
          configurationModifier = deviceConfigModifier(verticalDeviceSpec),
          resolvedLayoutsProvider = {
            sequenceOf(ResolvedScreenshotLayout(solidViewLayout(widthDp = 200, heightDp = 600), deviceSpec = verticalDeviceSpec))
          },
        )
      val (_, result, layout) = renderer.renderResolvedLayouts(request).single()
      try {
        assertThat(result.renderResult.status).isEqualTo(Result.Status.SUCCESS)
        assertThat(result.renderingMode).isEqualTo(RenderingMode.V_SCROLL)
        assertThat(result.renderedImage.width).isEqualTo(200)
        assertThat(result.renderedImage.height).isEqualTo(600)
        assertThat(layout.expandSettings).isEqualTo(ExpandSettings(ExpandAxis.vertical, 2000f, DimUnit.dp))
        assertThat(result.logger.messages).isEmpty()
      } finally {
        Disposer.dispose(result)
      }
    }

    // Mixed-case EXPAND in PreviewDeviceSpec must also be recognized via supportsExpand().
    val horizontalDeviceSpec = "spec:width=200dp,height=200dp,dpi=160,expand=horizontal"
    val mixedCaseUiTooling = createUiToolingJar("windowPosture,EXPAND")
    withRenderer(listOf(mixedCaseUiTooling)) { renderer ->
      val request =
        RenderRequest.withResolvedLayouts(
          configurationModifier = deviceConfigModifier(horizontalDeviceSpec),
          resolvedLayoutsProvider = {
            sequenceOf(ResolvedScreenshotLayout(solidViewLayout(widthDp = 600, heightDp = 200), deviceSpec = horizontalDeviceSpec))
          },
        )
      val (_, result, layout) = renderer.renderResolvedLayouts(request).single()
      try {
        assertThat(result.renderResult.status).isEqualTo(Result.Status.SUCCESS)
        assertThat(result.renderingMode).isEqualTo(RenderingMode.H_SCROLL)
        assertThat(result.renderedImage.width).isEqualTo(600)
        assertThat(result.renderedImage.height).isEqualTo(200)
        assertThat(layout.expandSettings).isEqualTo(ExpandSettings(ExpandAxis.horizontal))
        assertThat(result.logger.messages).isEmpty()
      } finally {
        Disposer.dispose(result)
      }
    }
  }

  @Test
  fun testRenderResolvedLayoutsPreservesExpandRenderingModeWhenShowDecorationsIsEnabled() {
    val uiTooling = createUiToolingJar("expand")
    val expandedSpec = "spec:width=200dp,height=200dp,dpi=160,expand=vertical"
    val fixedSpec = "spec:width=200dp,height=200dp,dpi=160"

    withRenderer(listOf(uiTooling)) { renderer ->
      val expandedRequest =
        RenderRequest.withResolvedLayouts(
          configurationModifier = deviceConfigModifier(expandedSpec),
          resolvedLayoutsProvider = {
            sequenceOf(ResolvedScreenshotLayout(solidViewLayout(widthDp = 200, heightDp = 600), deviceSpec = expandedSpec))
          },
          showDecorations = true,
        )
      val (_, expandedResult, _) = renderer.renderResolvedLayouts(expandedRequest).single()
      try {
        assertThat(expandedResult.renderResult.status).isEqualTo(Result.Status.SUCCESS)
        assertThat(expandedResult.renderingMode).isEqualTo(RenderingMode.V_SCROLL)
        assertThat(expandedResult.renderedImage.height).isGreaterThan(200)
      } finally {
        Disposer.dispose(expandedResult)
      }

      val fixedRequest =
        RenderRequest.withResolvedLayouts(
          configurationModifier = deviceConfigModifier(fixedSpec),
          resolvedLayoutsProvider = {
            sequenceOf(ResolvedScreenshotLayout(solidViewLayout(widthDp = 200, heightDp = 600), deviceSpec = fixedSpec))
          },
          showDecorations = true,
        )
      val (_, fixedResult, _) = renderer.renderResolvedLayouts(fixedRequest).single()
      try {
        assertThat(fixedResult.renderResult.status).isEqualTo(Result.Status.SUCCESS)
        assertThat(fixedResult.renderingMode).isEqualTo(RenderingMode.NORMAL)
        assertThat(fixedResult.renderedImage.height).isEqualTo(200)
      } finally {
        Disposer.dispose(fixedResult)
      }
    }
  }

  @Test
  fun testRenderResolvedLayoutsFallsBackToDeviceSizeWithWarningWhenUiToolingLacksExpand() {
    val deviceSpec = "spec:width=200dp,height=200dp,dpi=160,expand=vertical,maxHeight=2000dp"
    val olderUiTooling = createUiToolingJar("windowPosture")

    withRenderer(listOf(olderUiTooling)) { renderer ->
      val request =
        RenderRequest.withResolvedLayouts(
          configurationModifier = deviceConfigModifier(deviceSpec),
          resolvedLayoutsProvider = {
            sequenceOf(ResolvedScreenshotLayout(solidViewLayout(widthDp = 200, heightDp = 600), deviceSpec = deviceSpec))
          },
        )
      val (_, result, layout) = renderer.renderResolvedLayouts(request).single()
      try {
        assertThat(result.renderResult.status).isEqualTo(Result.Status.SUCCESS)
        assertThat(result.renderingMode).isNotEqualTo(RenderingMode.V_SCROLL)
        assertThat(result.renderedImage.height).isAtMost(200)
        assertThat(layout.expandSettings).isNull()
        val warnings = result.logger.messages.map { it.html }
        assertThat(warnings)
          .contains(
            "expand, maxHeight in the device spec require a newer version of androidx.compose.ui:ui-tooling; rendering at device size."
          )
      } finally {
        Disposer.dispose(result)
      }
    }
  }

  @Test
  fun testRenderPopulatesWarningsWithoutErrorWhenUiToolingLacksExpand() {
    val deviceSpec = "spec:width=200dp,height=200dp,dpi=160,expand=vertical,maxHeight=2000dp"
    val olderUiTooling = createUiToolingJar("windowPosture")
    val outputDir = temporaryFolder.newFolder("output_expand_warning")

    withRenderer(listOf(olderUiTooling)) { renderer ->
      val xmlPreview =
        xmlPreviewScreenshot(
          fqn = "com.example.ExpandPreviewKt.FallbackWithWarning",
          xmlLayout = solidViewLayout(widthDp = 200, heightDp = 600),
          previewParams = mapOf("device" to deviceSpec),
        )
      val xmlResult = renderer.render(xmlPreview, outputDir.absolutePath).single()
      assertThat(xmlResult.error).isNull()
      assertThat(xmlResult.warnings.map { it.html })
        .contains(
          "expand, maxHeight in the device spec require a newer version of androidx.compose.ui:ui-tooling; rendering at device size."
        )
    }
  }

  @Test
  fun testRenderValidatesExpandDeviceSpecAndWritesExpandedPng() {
    val uiTooling = createUiToolingJar("expand,maxHeight,maxWidth")
    val outputDir = temporaryFolder.newFolder("output_expand")

    withRenderer(listOf(uiTooling)) { renderer ->
      val validComposeScreenshot =
        ComposeScreenshot(
          previewId = "valid_expand",
          methodFQN = "com.example.ExpandPreviewKt.ValidExpand",
          previewParams = mapOf("device" to "spec:parent=pixel_7,expand=vertical,maxHeight=2000dp"),
          methodParams = emptyList(),
        )
      val validComposeResult = renderer.render(validComposeScreenshot, outputDir.absolutePath).single()
      // Validation must pass (rendering only fails later because the fake Composable class doesn't exist).
      assertThat(validComposeResult.error?.status).isNotEqualTo("VALIDATION_ERROR")

      val invalidComposeScreenshot =
        ComposeScreenshot(
          previewId = "invalid_expand",
          methodFQN = "com.example.ExpandPreviewKt.InvalidExpand",
          previewParams = mapOf("device" to "spec:parent=pixel_7,expand=both"),
          methodParams = emptyList(),
        )
      val invalidComposeResult = renderer.render(invalidComposeScreenshot, outputDir.absolutePath).single()
      assertThat(invalidComposeResult.error?.status).isEqualTo("VALIDATION_ERROR")
      assertThat(invalidComposeResult.error?.message).contains("expand")

      val expandedXmlPreview =
        xmlPreviewScreenshot(
          fqn = "com.example.ExpandPreviewKt.ExpandedXml",
          xmlLayout = solidViewLayout(widthDp = 200, heightDp = 600),
          previewParams =
            mapOf(
              "device" to "spec:width=200dp,height=200dp,dpi=160,expand=vertical,maxHeight=2000dp",
              "showSystemUi" to "true",
            ),
        )
      val xmlResult = renderer.render(expandedXmlPreview, outputDir.absolutePath, RenderConfiguration(allowShowDecorations = true)).single()
      assertThat(xmlResult.error).isNull()
      val image = ImageIO.read(File(outputDir, xmlResult.imagePath))
      assertThat(image.width).isEqualTo(200)
      assertThat(image.height).isGreaterThan(200)
    }
  }

  @Test
  fun testPostProcessRenderedImageClipsExpandedRoundDeviceToCapsule() {
    val uiTooling = createUiToolingJar("expand")

    withRenderer(listOf(uiTooling)) { renderer ->
      val verticalSpec = "spec:width=200dp,height=200dp,dpi=160,isRound=true,expand=vertical"
      val verticalRequest =
        RenderRequest.withResolvedLayouts(
          configurationModifier = deviceConfigModifier(verticalSpec),
          resolvedLayoutsProvider = {
            sequenceOf(ResolvedScreenshotLayout(solidViewLayout(widthDp = 200, heightDp = 600), deviceSpec = verticalSpec))
          },
        )
      val (verticalConfig, verticalResult, verticalLayout) = renderer.renderResolvedLayouts(verticalRequest).single()
      try {
        val capsule = renderer.postProcessRenderedImage(verticalConfig, verticalResult, expandSettings = verticalLayout.expandSettings)!!
        assertThat(capsule.width).isEqualTo(200)
        assertThat(capsule.height).isEqualTo(600)
        // Round caps clip the four corners.
        assertThat(capsule.getRGB(5, 5) ushr 24).isEqualTo(0)
        assertThat(capsule.getRGB(5, 595) ushr 24).isEqualTo(0)
        // Straight sides of the capsule stay opaque where a stretched ellipse would clip them.
        assertThat(capsule.getRGB(5, 150) ushr 24).isEqualTo(255)
        assertThat(capsule.getRGB(194, 450) ushr 24).isEqualTo(255)

        // Without expandSettings, the round device shape stretches into an ellipse and clips the side edges.
        val ellipse = renderer.postProcessRenderedImage(verticalConfig, verticalResult, expandSettings = null)!!
        assertThat(ellipse.getRGB(5, 150) ushr 24).isEqualTo(0)
        assertThat(ellipse.getRGB(194, 450) ushr 24).isEqualTo(0)
        assertThat(ellipse.getRGB(100, 300) ushr 24).isEqualTo(255)
      } finally {
        Disposer.dispose(verticalResult)
      }

      val horizontalSpec = "spec:width=200dp,height=200dp,dpi=160,isRound=true,expand=horizontal"
      val horizontalRequest =
        RenderRequest.withResolvedLayouts(
          configurationModifier = deviceConfigModifier(horizontalSpec),
          resolvedLayoutsProvider = {
            sequenceOf(ResolvedScreenshotLayout(solidViewLayout(widthDp = 600, heightDp = 200), deviceSpec = horizontalSpec))
          },
        )
      val (horizontalConfig, horizontalResult, horizontalLayout) = renderer.renderResolvedLayouts(horizontalRequest).single()
      try {
        val capsule =
          renderer.postProcessRenderedImage(horizontalConfig, horizontalResult, expandSettings = horizontalLayout.expandSettings)!!
        assertThat(capsule.width).isEqualTo(600)
        assertThat(capsule.height).isEqualTo(200)
        assertThat(capsule.getRGB(5, 5) ushr 24).isEqualTo(0)
        assertThat(capsule.getRGB(595, 5) ushr 24).isEqualTo(0)
        assertThat(capsule.getRGB(150, 5) ushr 24).isEqualTo(255)
        assertThat(capsule.getRGB(450, 194) ushr 24).isEqualTo(255)

        val ellipse = renderer.postProcessRenderedImage(horizontalConfig, horizontalResult, expandSettings = null)!!
        assertThat(ellipse.getRGB(150, 5) ushr 24).isEqualTo(0)
        assertThat(ellipse.getRGB(450, 194) ushr 24).isEqualTo(0)
        assertThat(ellipse.getRGB(300, 100) ushr 24).isEqualTo(255)
      } finally {
        Disposer.dispose(horizontalResult)
      }
    }
  }

  @Test
  fun testRenderAnimationsExpandsAndClipsRoundDeviceWhenUiToolingSupportsExpand() {
    val outputDir = temporaryFolder.newFolder("output_anim_expand")

    val uiTooling = createUiToolingJar("windowPosture,expand,maxHeight,maxWidth")
    withRenderer(listOf(uiTooling)) { renderer ->
      val verticalPreview =
        xmlPreviewScreenshot(
          fqn = "com.example.ExpandPreviewKt.AnimatedVertical",
          xmlLayout = solidViewLayout(widthDp = 200, heightDp = 600),
          previewParams = mapOf("device" to "spec:width=200dp,height=200dp,dpi=160,isRound=true,expand=vertical,maxHeight=2000dp"),
        )
      val result = renderer.renderAnimations(verticalPreview, outputDir.absolutePath).single()
      assertThat(result.error).isNull()
      assertThat(result.framePaths).hasSize(1)
      assertThat(result.manifest?.width).isEqualTo(200)
      assertThat(result.manifest?.height).isEqualTo(600)

      val frame = ImageIO.read(File(outputDir, result.framePaths.single()))
      assertThat(frame.width).isEqualTo(200)
      assertThat(frame.height).isEqualTo(600)
      // Capsule clipping preserves straight side edges while clipping round corners.
      assertThat(frame.getRGB(5, 5) ushr 24).isEqualTo(0)
      assertThat(frame.getRGB(5, 595) ushr 24).isEqualTo(0)
      assertThat(frame.getRGB(5, 150) ushr 24).isEqualTo(255)
      assertThat(frame.getRGB(194, 450) ushr 24).isEqualTo(255)
    }

    // Mixed-case EXPAND in PreviewDeviceSpec must also be recognized via supportsExpand().
    val mixedCaseUiTooling = createUiToolingJar("windowPosture,EXPAND")
    withRenderer(listOf(mixedCaseUiTooling)) { renderer ->
      val horizontalPreview =
        xmlPreviewScreenshot(
          fqn = "com.example.ExpandPreviewKt.AnimatedHorizontal",
          xmlLayout = solidViewLayout(widthDp = 600, heightDp = 200),
          previewParams = mapOf("device" to "spec:width=200dp,height=200dp,dpi=160,isRound=true,expand=horizontal"),
        )
      val result = renderer.renderAnimations(horizontalPreview, outputDir.absolutePath).single()
      assertThat(result.error).isNull()
      assertThat(result.framePaths).hasSize(1)
      assertThat(result.manifest?.width).isEqualTo(600)
      assertThat(result.manifest?.height).isEqualTo(200)

      val frame = ImageIO.read(File(outputDir, result.framePaths.single()))
      assertThat(frame.width).isEqualTo(600)
      assertThat(frame.height).isEqualTo(200)
      assertThat(frame.getRGB(5, 5) ushr 24).isEqualTo(0)
      assertThat(frame.getRGB(595, 5) ushr 24).isEqualTo(0)
      assertThat(frame.getRGB(150, 5) ushr 24).isEqualTo(255)
      assertThat(frame.getRGB(450, 194) ushr 24).isEqualTo(255)
    }
  }

  @Test
  fun testRenderAnimationsFallsBackToDeviceSizeWithWarningWhenUiToolingLacksExpand() {
    val outputDir = temporaryFolder.newFolder("output_anim_fallback")
    val olderUiTooling = createUiToolingJar("windowPosture")
    val expectedWarning =
      "expand, maxHeight in the device spec require a newer version of androidx.compose.ui:ui-tooling; rendering at device size."

    val animLogger = Logger.getLogger(PreviewAnimationRenderer::class.java.name)
    val warningMessages = mutableListOf<String>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          if (record.level == Level.WARNING) {
            warningMessages.add(record.message)
          }
        }

        override fun flush() {}

        override fun close() {}
      }
    animLogger.addHandler(handler)
    try {
      withRenderer(listOf(olderUiTooling)) { renderer ->
        val multiLayoutPreview =
          xmlPreviewScreenshot(
            fqn = "com.example.ExpandPreviewKt.AnimatedFallback",
            xmlLayout = solidViewLayout(widthDp = 200, heightDp = 600),
            previewParams = mapOf("device" to "spec:width=200dp,height=200dp,dpi=160,isRound=true,expand=vertical,maxHeight=2000dp"),
            displayNames = listOf("First", "Second"),
          )
        val results = renderer.renderAnimations(multiLayoutPreview, outputDir.absolutePath)
        assertThat(results).hasSize(2)
        for (result in results) {
          assertThat(result.error).isNull()
          assertThat(result.framePaths).hasSize(1)
          assertThat(result.manifest?.height).isAtMost(200)
          val frame = ImageIO.read(File(outputDir, result.framePaths.single()))
          assertThat(frame.height).isAtMost(200)
        }
        // Logged once per preview element even when multiple layouts are resolved.
        assertThat(warningMessages.filter { expectedWarning in it }).hasSize(1)

        // ComposeScreenshot with expand passes preview validation (no VALIDATION_ERROR).
        val validComposeScreenshot =
          ComposeScreenshot(
            previewId = "anim_fallback_compose",
            methodFQN = "com.example.ExpandPreviewKt.AnimatedFallbackCompose",
            previewParams = mapOf("device" to "spec:parent=pixel_7,expand=vertical,maxHeight=2000dp"),
            methodParams = emptyList(),
          )
        val composeResult = renderer.renderAnimations(validComposeScreenshot, outputDir.absolutePath).single()
        assertThat(composeResult.error?.status).isNotEqualTo("VALIDATION_ERROR")

        // Unsupported-parameter warning is also recorded on RenderLogger (surfaced in ScreenshotError.problems on render failure).
        val brokenInflationPreview =
          xmlPreviewScreenshot(
            fqn = "com.example.ExpandPreviewKt.AnimatedFallbackBrokenInflation",
            xmlLayout =
              """
              <?xml version="1.0" encoding="utf-8"?>
              <View xmlns:android="http://schemas.android.com/apk/res/android"
                  android:layout_width="not_a_dimension"
                  android:layout_height="100dp" />
              """
                .trimIndent(),
            previewParams = mapOf("device" to "spec:parent=pixel_7,expand=vertical,maxHeight=2000dp"),
          )
        val brokenResult = renderer.renderAnimations(brokenInflationPreview, outputDir.absolutePath).single()
        assertThat(brokenResult.error).isNotNull()
        assertThat(brokenResult.error!!.problems.map { it.html }).contains(expectedWarning)

        val invalidComposeScreenshot =
          ComposeScreenshot(
            previewId = "anim_invalid_expand",
            methodFQN = "com.example.ExpandPreviewKt.AnimatedInvalidExpand",
            previewParams = mapOf("device" to "spec:parent=pixel_7,expand=both"),
            methodParams = emptyList(),
          )
        val invalidResult = renderer.renderAnimations(invalidComposeScreenshot, outputDir.absolutePath).single()
        assertThat(invalidResult.error?.status).isEqualTo("VALIDATION_ERROR")
        assertThat(invalidResult.error?.message).contains("expand")
      }
    } finally {
      animLogger.removeHandler(handler)
    }
  }
}
