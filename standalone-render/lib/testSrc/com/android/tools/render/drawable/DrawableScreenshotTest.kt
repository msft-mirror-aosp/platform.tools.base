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
package com.android.tools.render.drawable

import com.android.testutils.TestUtils
import com.android.tools.preview.UNDEFINED_API_LEVEL
import com.android.tools.render.RenderEnvironmentBootstrapper
import com.android.tools.render.Renderer
import com.android.tools.render.common.ScreenshotPreviewElement
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.absolutePathString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// This test sorts after RendererTest in JarTestSuite and doesn't render, so it doesn't use RenderTestRule.
class DrawableScreenshotTest {
  @JvmField @Rule val tmpFolder = TemporaryFolder()

  private fun <T> withRenderer(block: (Renderer) -> T): T {
    val resFolder = tmpFolder.newFolder("res")
    for (dir in listOf("drawable" to "ic_logo.xml", "mipmap" to "ic_launcher.xml")) {
      File(resFolder, dir.first)
        .apply { mkdirs() }
        .resolve(dir.second)
        .writeText(
          """
          <vector xmlns:android="http://schemas.android.com/apk/res/android"
              android:width="24dp" android:height="24dp"
              android:viewportWidth="24" android:viewportHeight="24">
              <path android:fillColor="#FFFF0000" android:pathData="M0,0h24v24h-24z" />
          </vector>
          """
            .trimIndent()
        )
    }
    File(resFolder, "values")
      .apply { mkdirs() }
      .resolve("styles.xml")
      .writeText(
        """
        <resources>
            <style name="Theme.App" parent="android:Theme.Material.Light" />
        </resources>
        """
          .trimIndent()
      )
    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        resourceDirs = listOf(resFolder.absolutePath),
        namespace = "com.example.app",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib").absolutePathString(),
      )
    return bootstrapper.bootstrap().use(block)
  }

  private fun ScreenshotPreviewElement.xml(): String = resolveLayouts().single().xmlLayout

  @Test
  fun toPreviewElement_buildsExpectedLayoutAndRenderingMode() = withRenderer { renderer ->
    val module = renderer.module

    val defaultElement = DrawableScreenshot(methodFQN = "R.drawable.ic_logo", previewId = "ic_logo").toPreviewElement(module)
    assertFalse(defaultElement.expandToContent)
    assertTrue(defaultElement.transparentBackground)
    assertFalse(defaultElement.displaySettings.showDecoration)
    val defaultXml = defaultElement.xml()
    assertThat(defaultXml).contains("""android:layout_width="wrap_content"""")
    assertThat(defaultXml).contains("""android:layout_height="wrap_content"""")
    assertThat(defaultXml).contains("""android:src="@drawable/ic_logo"""")
    assertThat(defaultXml).contains("""android:adjustViewBounds="true"""")
    assertThat(defaultXml).doesNotContain("drawableState")
    assertThat(defaultXml).doesNotContain("drawableLevel")

    // Explicit widthDp or heightDp expands the canvas so sizes larger than the screen are not clamped while keeping device qualifiers.
    val expandedWidth =
      DrawableScreenshot(methodFQN = "R.drawable.ic_logo", previewParams = mapOf("widthDp" to "300"), previewId = "ic_logo_w")
        .toPreviewElement(module)
    assertTrue(expandedWidth.expandToContent)
    assertThat(expandedWidth.xml()).contains("""android:layout_width="300dp"""")
    assertThat(expandedWidth.xml()).contains("""android:layout_height="wrap_content"""")

    val expandedHeight =
      DrawableScreenshot(methodFQN = "R.drawable.ic_logo", previewParams = mapOf("heightDp" to "250"), previewId = "ic_logo_h")
        .toPreviewElement(module)
    assertTrue(expandedHeight.expandToContent)
    assertThat(expandedHeight.xml()).contains("""android:layout_height="250dp"""")

    // -1 (undefined) and blank parameters behave like unset parameters.
    val undefinedElement =
      DrawableScreenshot(
          methodFQN = "R.drawable.ic_logo",
          previewParams = mapOf("widthDp" to "-1", "heightDp" to " -1 ", "apiLevel" to "-1", "state" to " , "),
          previewId = "ic_logo_undef",
        )
        .toPreviewElement(module)
    assertFalse(undefinedElement.expandToContent)
    assertEquals(UNDEFINED_API_LEVEL, undefinedElement.configuration.apiLevel)
    assertThat(undefinedElement.xml()).contains("""android:layout_width="wrap_content"""")
    assertThat(undefinedElement.xml()).contains("""android:layout_height="wrap_content"""")
    assertThat(undefinedElement.xml()).doesNotContain("drawableState")

    val configuredElement =
      DrawableScreenshot(
          methodFQN = "@mipmap/ic_launcher",
          previewParams =
            mapOf(
              "state" to " pressed , \n checked , ",
              "level" to "5000",
              "scaleType" to "centerInside",
              "theme" to "Theme.App",
              "backgroundColor" to "#FF112233",
              "uiMode" to "32",
              "apiLevel" to "34",
            ),
          previewId = "ic_launcher",
        )
        .toPreviewElement(module)
    assertEquals(0x20, configuredElement.configuration.uiMode)
    assertEquals(34, configuredElement.configuration.apiLevel)
    val configuredXml = configuredElement.xml()
    assertThat(configuredXml).contains("""android:src="@mipmap/ic_launcher"""")
    assertThat(configuredXml).contains("""tools:drawableState="pressed,checked"""")
    assertThat(configuredXml).contains("""tools:drawableLevel="5000"""")
    assertThat(configuredXml).contains("""android:scaleType="centerInside"""")
    assertThat(configuredXml).contains("""android:theme="@style/Theme.App"""")
    assertThat(configuredXml).contains("""android:background="#FF112233"""")

    // Framework drawables and framework theme references do not require local app resources.
    val frameworkElement =
      DrawableScreenshot(
          methodFQN = "android.R.drawable.ic_delete",
          previewParams = mapOf("theme" to "@android:style/Theme.Material"),
          previewId = "ic_delete",
        )
        .toPreviewElement(module)
    assertThat(frameworkElement.xml()).contains("""android:src="@android:drawable/ic_delete"""")
    assertThat(frameworkElement.xml()).contains("""android:theme="@android:style/Theme.Material"""")
  }

  @Test
  fun toPreviewElement_rejectsInvalidTargetsAndParameters() = withRenderer { renderer ->
    val module = renderer.module
    fun assertInvalid(methodFQN: String = "R.drawable.ic_logo", params: Map<String, String> = emptyMap(), expectedMessage: String) {
      val error =
        assertThrows(IllegalArgumentException::class.java) {
          DrawableScreenshot(methodFQN = methodFQN, previewParams = params, previewId = "test").toPreviewElement(module)
        }
      assertThat(error.message).contains(expectedMessage)
    }

    assertInvalid(methodFQN = "com.example.Preview", expectedMessage = "Invalid drawable target: 'com.example.Preview'")
    assertInvalid(methodFQN = "R.layout.main", expectedMessage = "Invalid drawable target: 'R.layout.main'")
    assertInvalid(methodFQN = "R.drawable.missing", expectedMessage = "Drawable resource '@drawable/missing' not found")
    assertInvalid(params = mapOf("scaleType" to "stretch"), expectedMessage = "Invalid scaleType 'stretch'")
  }
}
