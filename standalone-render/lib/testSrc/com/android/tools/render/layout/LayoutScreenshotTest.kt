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
package com.android.tools.render.layout

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
class LayoutScreenshotTest {
  @JvmField @Rule val tmpFolder = TemporaryFolder()

  private fun <T> withRenderer(block: (Renderer) -> T): T {
    val resFolder = tmpFolder.newFolder("res")
    File(resFolder, "layout")
      .apply { mkdirs() }
      .resolve("main.xml")
      .writeText(
        """
        <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent" android:layout_height="match_parent" />
        """
          .trimIndent()
      )
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
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        resourceDirs = listOf(resFolder.absolutePath),
        namespace = "com.example.app",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    return bootstrapper.bootstrap().use(block)
  }

  private fun ScreenshotPreviewElement.xml(): String = resolveLayouts().single().xmlLayout

  @Test
  fun toPreviewElement_buildsExpectedLayoutAndRenderingMode() = withRenderer { renderer ->
    val module = renderer.module
    fun element(params: Map<String, String> = emptyMap(), methodFQN: String = "R.layout.main") =
      LayoutScreenshot(methodFQN = methodFQN, previewParams = params, previewId = "main").toPreviewElement(module)

    // Default: the layout's parent fills the device with the window background, and the image isn't expanded.
    val default = element()
    assertFalse(default.expandToContent)
    assertTrue(default.transparentBackground)
    assertFalse(default.displaySettings.showDecoration)
    val defaultXml = default.xml()
    assertThat(defaultXml).startsWith("""<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"""")
    assertThat(defaultXml).contains("""android:layout_width="match_parent"""")
    assertThat(defaultXml).contains("""android:layout_height="match_parent"""")
    assertThat(defaultXml).contains("""android:background="?android:attr/windowBackground"""")
    assertThat(defaultXml).contains("""<include layout="@layout/main" />""")
    assertThat(defaultXml).doesNotContain("android:theme")

    // widthDp or heightDp enables FULL_EXPAND, so that sizes larger than the screen aren't cut off.
    val wide = element(mapOf("widthDp" to "500"))
    assertTrue(wide.expandToContent)
    assertThat(wide.xml()).contains("""android:layout_width="500dp"""")
    assertThat(wide.xml()).contains("""android:layout_height="match_parent"""")
    val tall = element(mapOf("heightDp" to "40"))
    assertTrue(tall.expandToContent)
    assertThat(tall.xml()).contains("""android:layout_height="40dp"""")

    // -1 (undefined) and blank values are like missing values, and values are trimmed.
    val undefined = element(mapOf("widthDp" to "-1", "heightDp" to " -1 ", "apiLevel" to " -1 ", "theme" to " ", "backgroundColor" to ""))
    assertFalse(undefined.expandToContent)
    assertEquals(UNDEFINED_API_LEVEL, undefined.configuration.apiLevel)
    assertThat(undefined.xml()).contains("""android:layout_width="match_parent"""")
    assertThat(undefined.xml()).contains("""android:layout_height="match_parent"""")
    assertThat(undefined.xml()).contains("""android:background="?android:attr/windowBackground"""")
    assertThat(undefined.xml()).doesNotContain("android:theme")
    val trimmed =
      element(
        mapOf(
          "widthDp" to " 50 ",
          "heightDp" to "\t40\n",
          "apiLevel" to " 34 ",
          "fontScale" to " 1.5 ",
          "locale" to " fr ",
          "uiMode" to " 33 ",
          "wallpaper" to " 2 ",
          "device" to " spec:width=200px,height=300px,dpi=320 ",
          "theme" to " Theme.App ",
          "backgroundColor" to " #FF0000FF ",
        )
      )
    assertEquals(34, trimmed.configuration.apiLevel)
    assertEquals(1.5f, trimmed.configuration.fontScale)
    assertEquals("fr", trimmed.configuration.locale)
    assertEquals(0x21, trimmed.configuration.uiMode)
    assertEquals(2, trimmed.configuration.wallpaper)
    assertEquals("spec:width=200px,height=300px,dpi=320", trimmed.configuration.deviceSpec)
    val trimmedXml = trimmed.xml()
    assertThat(trimmedXml).contains("""android:layout_width="50dp"""")
    assertThat(trimmedXml).contains("""android:layout_height="40dp"""")
    assertThat(trimmedXml).contains("""android:theme="@style/Theme.App"""")
    assertThat(trimmedXml).contains("""android:background="#FF0000FF"""")

    // showBackground=false makes the background transparent, even with a backgroundColor, and showBackground=true changes nothing.
    assertThat(element(mapOf("showBackground" to " FALSE ", "backgroundColor" to "#F00")).xml()).doesNotContain("android:background")
    assertThat(element(mapOf("showBackground" to "true")).xml()).contains("""android:background="?android:attr/windowBackground"""")
    for (color in listOf("#F00", "#8F00", "#00FF00", "#800000FF", "@color/square", "@android:color/white", "@com.example:color/a.b")) {
      assertThat(element(mapOf("backgroundColor" to color)).xml()).contains("""android:background="$color"""")
    }
    for (attr in listOf("?attr/colorPrimary", "?android:attr/colorBackground", "?colorPrimary", "?android:colorBackground")) {
      assertThat(element(mapOf("backgroundColor" to attr)).xml()).contains("""android:background="$attr"""")
    }

    // Attribute values are escaped.
    assertThat(element(mapOf("backgroundColor" to """@color/A"B'C&D<E>""")).xml())
      .contains("""android:background="@color/A&quot;B&apos;C&amp;D&lt;E>"""")

    // Framework layouts don't need app resources.
    assertThat(element(methodFQN = "android.R.layout.simple_list_item_1").xml())
      .contains("""<include layout="@android:layout/simple_list_item_1" />""")
  }

  @Test
  fun toPreviewElement_rejectsInvalidTargetsAndParameters() = withRenderer { renderer ->
    val module = renderer.module
    fun assertInvalid(methodFQN: String = "R.layout.main", params: Map<String, String> = emptyMap(), expectedMessage: String) {
      val error =
        assertThrows(IllegalArgumentException::class.java) {
          LayoutScreenshot(methodFQN = methodFQN, previewParams = params, previewId = "test").toPreviewElement(module)
        }
      assertThat(error.message).contains(expectedMessage)
    }

    assertInvalid(methodFQN = "com.example.Preview", expectedMessage = "Invalid layout target: 'com.example.Preview'")
    assertInvalid(methodFQN = "R.drawable.main", expectedMessage = "Invalid layout target: 'R.drawable.main'")
    assertInvalid(methodFQN = "R.layout.missing", expectedMessage = "Layout resource '@layout/missing' not found")
    assertInvalid(params = mapOf("showBackground" to "yes"), expectedMessage = "Invalid showBackground 'yes': expected true or false")
  }
}
