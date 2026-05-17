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

package com.android.build.gradle.integration.testing.screenshot

import com.android.build.gradle.integration.common.fixture.ProfileCapturer
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.build.gradle.internal.profile.GradleTaskExecutionType
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class ScreenshotFeaturesTest {

  @get:Rule
  val rule =
    GradleRule.configure().withProfileOutput().from {
      androidApplication { setupProject() }
      gradleProperties { add(BooleanOption.ENABLE_SCREENSHOT_TEST, true) }
    }

  @Test
  fun runPreviewScreenshotTestWithReportEntrySettingEnabled() {
    val build =
      rule.build {
        androidApplication {
          class EnableEntrySettingCallback : GenericCallback {
            override fun handleProject(project: Project) {
              project.afterEvaluate {
                project.tasks.named("validateDebugScreenshotTest", org.gradle.api.tasks.testing.Test::class.java) {
                  it.jvmArgs("-DPreviewScreenshotTestEngineInput.ReportEntrySetting.redirectToStdout=true")
                }
              }
            }
          }
          pluginCallbacks += EnableEntrySettingCallback::class.java
        }
      }
    val appProject = build.androidApplication()

    build.updateReferenceImage()

    val result = build.sstExecutor().run(":app:validateDebugScreenshotTest")

    assertThat(result.stdout).contains("[additionalTestArtifacts]PreviewScreenshot.newImagePath=")
    assertThat(result.stdout).contains("[additionalTestArtifacts]PreviewScreenshot.refImagePath=")
    assertThat(result.stdout).contains("[additionalTestArtifacts]PreviewScreenshot.previewName=")
    assertThat(result.stdout).contains("[additionalTestArtifacts]PreviewScreenshot.methodName=")
    assertThat(result.stdout).contains("[additionalTestArtifacts]PreviewScreenshot.diffPercent=")
  }

  @Test
  fun runPreviewScreenshotTestWithCustomFontShouldLoadCustomFonts() {
    val build =
      rule.build {
        androidApplication {
          files {
            add("src/main/res/font/my_custom_font.ttf", this.javaClass.getResourceAsStream("/fonts/test_font.ttf")!!.readAllBytes())
            add(
              "src/main/java/com/ExampleWithFont.kt",
              """
              package pkg.name

              import androidx.compose.material.Text
              import androidx.compose.runtime.Composable
              import androidx.compose.ui.text.font.Font
              import androidx.compose.ui.text.font.FontFamily
              import androidx.compose.ui.text.font.FontWeight
              import pkg.name.app.R

              private val customFontFamily = FontFamily(
                  Font(R.font.my_custom_font, FontWeight.Normal)
              )

              @Composable
              fun SimpleComposableWithFont(text: String = "Hello World") {
                  Text(text, fontFamily = customFontFamily)
              }
              """
                .trimIndent(),
            )
            add(
              "src/screenshotTest/java/com/ExampleWithFontTest.kt",
              """
              package pkg.name

              import androidx.compose.ui.tooling.preview.Preview
              import androidx.compose.runtime.Composable
              import com.android.tools.screenshot.PreviewTest

              class ExampleWithFontTest {
                  @PreviewTest
                  @Preview(name = "simpleComposable", showBackground = true)
                  @Composable
                  fun simpleComposableWithFontTest() {
                      SimpleComposableWithFont()
                  }
              }
              """
                .trimIndent(),
            )
          }
        }
      }

    build.updateReferenceImage()

    val result = build.sstExecutor().run(":app:validateDebugScreenshotTest")

    result.assertOutputDoesNotContain("Could not load font")
  }

  @Test
  fun runPreviewScreenshotTestWithAssets() {
    val build =
      rule.build {
        androidApplication(":appWithAssets") {
          setupProject(addEmptyJarToClassPath = false)
          files {
            add("src/main/assets/test_asset.txt", "Asset loading integration content")
            add(
              "src/main/java/com/AssetComposable.kt",
              """
              package pkg.name
              import androidx.compose.material.Text
              import androidx.compose.runtime.Composable
              import androidx.compose.ui.platform.LocalContext

              @Composable
              fun AssetComposable() {
                  val context = LocalContext.current
                  context.assets.open("test_asset.txt").use { it.readAllBytes() }
                  Text("Asset Loaded")
              }
              """
                .trimIndent(),
            )
            add(
              "src/screenshotTest/java/com/AssetTest.kt",
              """
              package pkg.name
              import androidx.compose.ui.tooling.preview.Preview
              import androidx.compose.runtime.Composable
              import com.android.tools.screenshot.PreviewTest

              class AssetTest {
                  @PreviewTest
                  @Preview
                  @Composable
                  fun assetTest() {
                      AssetComposable()
                  }
              }
              """
                .trimIndent(),
            )
          }
        }
      }

    val updateResult = build.updateReferenceImage(projectName = "appWithAssets")
    updateResult.assertOutputDoesNotContain("ScreenshotError")
    updateResult.assertOutputDoesNotContain("File not found")

    val validateResult = build.sstExecutor().run(":appWithAssets:validateDebugScreenshotTest")
    validateResult.assertOutputDoesNotContain("ScreenshotError")
    validateResult.assertOutputDoesNotContain("File not found")
  }

  @Test
  fun runPreviewScreenshotTestWithAndroidViewInflater() {
    val build =
      rule.build {
        androidApplication(":appWithInflater") {
          setupProject(addEmptyJarToClassPath = false)
          files {
            add(
              "src/main/res/layout/custom_view.xml",
              """
              <?xml version="1.0" encoding="utf-8"?>
              <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                  android:layout_width="match_parent"
                  android:layout_height="match_parent">
                  <TextView
                      android:id="@+id/custom_text"
                      android:layout_width="wrap_content"
                      android:layout_height="wrap_content"
                      android:text="Inflated Text"
                      style="@style/Theme.Custom" />
              </LinearLayout>
              """.trimIndent()
            )
            add(
              "src/main/res/values/styles.xml",
              """
              <?xml version="1.0" encoding="utf-8"?>
              <resources>
                  <style name="Theme.Custom" parent="android:Theme.Material">
                      <item name="android:textColor">#FF0000</item>
                  </style>
              </resources>
              """.trimIndent()
            )
            add(
              "src/main/java/com/CustomView.kt",
              """
              package pkg.name

              import android.content.Context
              import android.view.LayoutInflater
              import android.widget.LinearLayout
              import android.widget.TextView
              import pkg.name.appWithInflater.R

              class CustomView(context: Context) : LinearLayout(context) {
                  init {
                      val view = LayoutInflater.from(context).inflate(R.layout.custom_view, this, false)
                      addView(view)
                      val textView = findViewById<TextView>(R.id.custom_text)
                      textView.text = "Patched Inflated Text"
                  }
              }
              """.trimIndent()
            )
            add(
              "src/screenshotTest/java/com/AndroidViewTest.kt",
              """
              package pkg.name

              import androidx.compose.ui.tooling.preview.Preview
              import androidx.compose.runtime.Composable
              import androidx.compose.ui.viewinterop.AndroidView
              import android.view.ContextThemeWrapper
              import com.android.tools.screenshot.PreviewTest
              import pkg.name.appWithInflater.R

              class AndroidViewTest {
                  @PreviewTest
                  @Preview(name = "androidViewPreview", showBackground = true)
                  @Composable
                  fun androidViewTest() {
                      AndroidView(
                          factory = { context ->
                              val contextWrapper = ContextThemeWrapper(context, R.style.Theme_Custom)
                              CustomView(contextWrapper)
                          }
                      )
                  }
              }
              """.trimIndent()
            )
          }
        }
      }

    val updateResult = build.updateReferenceImage(projectName = "appWithInflater")
    updateResult.assertOutputDoesNotContain("ScreenshotError")
    updateResult.assertOutputDoesNotContain("File not found")

    val validateResult = build.sstExecutor().run(":appWithInflater:validateDebugScreenshotTest")
    validateResult.assertOutputDoesNotContain("ScreenshotError")
    validateResult.assertOutputDoesNotContain("File not found")
  }

  @Test
  fun runPreviewScreenshotTestWithPreviewWrapper() {
    val build =
      rule.build {
        androidApplication(":appWithWrapper") {
          setupProject(addEmptyJarToClassPath = false)
          files {
            add(
              "src/main/java/androidx/compose/ui/tooling/preview/PreviewWrapper.kt",
              """
              package androidx.compose.ui.tooling.preview
              import androidx.compose.runtime.Composable
              import kotlin.reflect.KClass

              interface PreviewWrapperProvider {
                  @Composable
                  fun Wrap(content: @Composable () -> Unit)
              }

              @Retention(AnnotationRetention.SOURCE)
              @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
              annotation class PreviewWrapper(val wrapper: KClass<out PreviewWrapperProvider>)
              """
                .trimIndent(),
            )
            add(
              "src/main/java/com/WrapperComposable.kt",
              """
              package pkg.name
              import androidx.compose.material.Text
              import androidx.compose.runtime.Composable

              @Composable
              fun Content() {
                  Text("Content without wrapper")
              }
              """
                .trimIndent(),
            )
            add(
              "src/screenshotTest/java/com/WrapperTest.kt",
              """
              package pkg.name
              import androidx.compose.ui.tooling.preview.Preview
              import androidx.compose.ui.tooling.preview.PreviewWrapper
              import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
              import androidx.compose.runtime.Composable
              import com.android.tools.screenshot.PreviewTest
              import androidx.compose.material.Text

              class ThemeWrapper : PreviewWrapperProvider {
                  @Composable
                  override fun Wrap(content: @Composable () -> Unit) {
                      Text("Wrapped: ")
                      content()
                  }
              }

              @PreviewWrapper(ThemeWrapper::class)
              @Preview
              annotation class MyPreviewWrapper

              class WrapperTest {
                  @PreviewTest
                  @MyPreviewWrapper
                  @Composable
                  fun wrapperTest() {
                      Content()
                  }
              }
              """
                .trimIndent(),
            )
          }
        }
      }

    val updateResult = build.updateReferenceImage(projectName = "appWithWrapper")
    updateResult.assertOutputDoesNotContain("ScreenshotError")
    updateResult.assertOutputDoesNotContain("File not found")

    val validateResult = build.sstExecutor().run(":appWithWrapper:validateDebugScreenshotTest")
    validateResult.assertOutputDoesNotContain("ScreenshotError")
    validateResult.assertOutputDoesNotContain("File not found")
  }

  @Test
  fun analytics() {
    val build = rule.build
    val capturer = ProfileCapturer(build)

    val profiles = capturer.capture { build.updateReferenceImage() }

    profiles
      .mapNotNull { profile ->
        val spanList = profile.spanList
        val taskSpan =
          spanList.firstOrNull { it.task.type == GradleTaskExecutionType.PREVIEW_SCREENSHOT_UPDATE_VALUE } ?: return@mapNotNull null
        taskSpan.durationInMs
      }
      .first { durationInMs -> durationInMs > 0L }
  }
}
