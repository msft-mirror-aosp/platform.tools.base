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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test

class ScreenshotPerformanceTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withProfileOutput()
      .withMavenRepository {
        jar("com.mytest.memory-printer:memory-printer:1.0")
          .addClasses(MemoryPrinter::class.java)
          .addClasses(CheckMemoryUsage::class.java)
          .addTextFile("META-INF/services/org.junit.platform.launcher.TestExecutionListener", CheckMemoryUsage::class.java.name)
      }
      .from {
        androidApplication { setupProject() }
        androidLibrary { setupProject() }
        repeat(2) { androidLibrary(":lib2_$it") { setupProject(addEmptyJarToClassPath = false) } }
        gradleProperties { add(BooleanOption.ENABLE_SCREENSHOT_TEST, true) }
      }

  @Test
  fun runPreviewScreenshotTestInParallel() {
    val build = rule.build
    build.updateReferenceImage()
    build.sstExecutor().withArguments(listOf("--parallel", "--max-workers=4")).run(":app:validateDebugScreenshotTest")
    val appProject = build.androidApplication()
    val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
    assertThat(indexHtmlReport).exists()
    val classHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.ExampleTest.html")
    assertThat(classHtmlReport).exists()
    val class2HtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.TopLevelPreviewTestKt.html")
    assertThat(class2HtmlReport).exists()
    assertThat(classHtmlReport.readText()).contains("""<h3 class="success">simpleComposableTest_simpleComposable</h3>""")
    assertThat(class2HtmlReport.readText()).contains("""<h3 class="success">simpleComposableTest_3</h3>""")
  }

  @Test
  fun runValidation_whenMaxParallelForksIsConfiguredGlobally() {
    val build = rule.build { androidApplication { pluginCallbacks += ConfigureMaxParallelForksCallback::class.java } }
    val appProject = build.androidApplication()

    build.updateReferenceImage()

    val result = build.sstExecutor().run(":app:validateDebugScreenshotTest")

    assertThat(result.stdout).contains("Forcibly setting maxParallelForks to 4 for task :app:validateDebugScreenshotTest")

    val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
    assertThat(indexHtmlReport).exists()

    val classHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.ExampleTest.html")
    assertThat(classHtmlReport).exists()
    assertThat(classHtmlReport.readText()).contains("""<h3 class="success">simpleComposableTest_simpleComposable</h3>""")
  }

  @Test
  fun previewScreenshotTestDoesNotLeakMemory() {
    rule.build {
      androidApplication {
        files {
          add(
            "src/screenshotTest/java/com/ExampleLargeNumberOfComposables.kt",
            """
                        package pkg.name

                        import androidx.compose.foundation.background
                        import androidx.compose.foundation.layout.Box
                        import androidx.compose.foundation.layout.size
                        import androidx.compose.material.Text
                        import androidx.compose.runtime.Composable
                        import androidx.compose.ui.Alignment
                        import androidx.compose.ui.Modifier
                        import androidx.compose.ui.graphics.Color
                        import androidx.compose.ui.tooling.preview.Preview
                        import androidx.compose.ui.unit.dp
                        import com.android.tools.screenshot.PreviewTest

                        ${
                            List(30) {
                                """
                        @Preview
                        @PreviewTest
                        @Composable
                        fun SimpleComposable_$it(text: String = "Hello World $it") {
                            Box(
                                modifier = Modifier
                                    .size(width = 1280.dp, height = 720.dp)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text)
                            }
                        }
                                """
                            }.joinToString("\n\n")
                        }
                        """
              .trimIndent(),
          )
          add("src/screenshotTest/resources/junit-platform.properties", "junit.platform.execution.listeners.automatic.enabled=true")
        }

        pluginCallbacks += CheckMemoryUsageCallback::class.java
      }
    }

    val result = rule.build.updateReferenceImage()
    result.assertOutputContains("HEAP: Used:")
    result.assertErrorDoesNotContain(CheckMemoryUsageCallback.UNUSUAL_HEAP_MEMORY_GROWTH_ERROR_MESSAGE)
  }
}
