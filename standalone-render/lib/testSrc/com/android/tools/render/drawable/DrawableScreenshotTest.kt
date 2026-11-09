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
import com.android.tools.render.RenderEnvironmentBootstrapper
import com.android.tools.render.Renderer
import com.android.tools.render.common.ScreenshotPreviewElement
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.absolutePathString
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
  fun toPreviewElement_buildsExpectedLayout() = withRenderer { renderer ->
    val module = renderer.module

    val defaultElement = DrawableScreenshot(methodFQN = "R.drawable.ic_logo", previewId = "ic_logo").toPreviewElement(module)
    assertTrue(defaultElement.transparentBackground)
    assertFalse(defaultElement.displaySettings.showDecoration)
    val defaultXml = defaultElement.xml()
    assertThat(defaultXml).contains("""android:layout_width="wrap_content"""")
    assertThat(defaultXml).contains("""android:layout_height="wrap_content"""")
    assertThat(defaultXml).contains("""android:src="@drawable/ic_logo"""")
    assertThat(defaultXml).contains("""android:adjustViewBounds="true"""")

    val mipmapElement = DrawableScreenshot(methodFQN = "@mipmap/ic_launcher", previewId = "ic_launcher").toPreviewElement(module)
    assertThat(mipmapElement.xml()).contains("""android:src="@mipmap/ic_launcher"""")

    // Framework drawables do not require local app resources.
    val frameworkElement = DrawableScreenshot(methodFQN = "android.R.drawable.ic_delete", previewId = "ic_delete").toPreviewElement(module)
    assertThat(frameworkElement.xml()).contains("""android:src="@android:drawable/ic_delete"""")
  }

  @Test
  fun toPreviewElement_rejectsInvalidTargets() = withRenderer { renderer ->
    val module = renderer.module
    fun assertInvalid(methodFQN: String, expectedMessage: String) {
      val error =
        assertThrows(IllegalArgumentException::class.java) {
          DrawableScreenshot(methodFQN = methodFQN, previewId = "test").toPreviewElement(module)
        }
      assertThat(error.message).contains(expectedMessage)
    }

    assertInvalid(methodFQN = "com.example.Preview", expectedMessage = "Invalid drawable target: 'com.example.Preview'")
    assertInvalid(methodFQN = "R.layout.main", expectedMessage = "Invalid drawable target: 'R.layout.main'")
    assertInvalid(methodFQN = "R.drawable.missing", expectedMessage = "Drawable resource '@drawable/missing' not found")
  }
}
