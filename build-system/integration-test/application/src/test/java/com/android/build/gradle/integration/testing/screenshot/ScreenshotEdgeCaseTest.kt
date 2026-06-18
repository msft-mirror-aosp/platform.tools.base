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
import com.android.build.gradle.internal.TaskManager
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test

class ScreenshotEdgeCaseTest {

  @get:Rule
  val rule =
    GradleRule.configure().withProfileOutput().from {
      androidApplication { setupProject() }
      androidLibrary { setupProject() }
      repeat(2) { androidLibrary(":lib2_$it") { setupProject(addEmptyJarToClassPath = false) } }
      gradleProperties { add(BooleanOption.ENABLE_SCREENSHOT_TEST, true) }
    }

  @Test
  fun runPreviewScreenshotTestWithNoSourceFiles() {
    val build =
      rule.build {
        androidApplication {
          files {
            remove("src/screenshotTest/java/com/ExampleTest.kt")
            remove("src/screenshotTest/java/com/TopLevelPreviewTest.kt")
            remove("src/screenshotTest/java/com/AnotherPreviewParameterProvider.kt")
          }
        }
      }
    val appProject = build.androidApplication()

    val result = build.sstExecutor().run(":app:validateDebugScreenshotTest")
    assertThat(result.skippedTasks).contains(":app:validateDebugScreenshotTest")

    val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
    assertThat(indexHtmlReport).doesNotExist()
  }

  @Test
  fun runPreviewScreenshotTestWithSourceFilesAndNoPreviewsToTest() {
    val build =
      rule.build {
        androidApplication {
          files {
            update("src/screenshotTest/java/com/ExampleTest.kt").transform {
              """
                            /*
                            $it
                            */
                        """
                .trimIndent()
            }
            update("src/screenshotTest/java/com/TopLevelPreviewTest.kt").transform {
              """
                            /*
                            $it
                            */
                        """
                .trimIndent()
            }
          }
        }
      }
    val appProject = build.androidApplication()

    build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")

    val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
    assertThat(indexHtmlReport).exists()
  }

  @Test
  fun runPreviewScreenshotTestWithNoPreviewAnnotation() {
    val build =
      rule.build {
        androidApplication {
          files {
            add(
              "src/screenshotTest/java/com/PreviewTestWithoutPreview.kt",
              """
              package pkg.name

              import com.android.tools.screenshot.PreviewTest

              @PreviewTest
              fun previewTestWithoutPreview() {}
              """
                .trimIndent(),
            )
          }
        }
      }
    val appProject = build.androidApplication()

    build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")

    val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
    assertThat(indexHtmlReport).exists()

    val xmlReport = appProject.buildDir.resolve("test-results/validateDebugScreenshotTest/TEST-preview-screenshot-test-engine.xml")
    assertThat(xmlReport).exists()
    assertThat(xmlReport.readText()).contains("@Preview annotation is required for @PreviewTest")
  }

  @Test
  fun runPreviewScreenshotTestsWithMissingUiToolingDep() {
    val uiToolingDep = "androidx.compose.ui:ui-tooling:${TaskManager.COMPOSE_UI_VERSION}"
    val build =
      rule.build {
        androidApplication {
          dependencies {
            remove("implementation", uiToolingDep)
            screenshotTestImplementation(uiToolingDep)
          }
        }
      }

    build.updateReferenceImage()

    build.androidApplication().reconfigure { dependencies { remove("screenshotTestImplementation", uiToolingDep) } }

    val result = build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")
    result.assertErrorContains(
      "Missing required runtime dependency. Please add androidx.compose.ui:ui-tooling as a screenshotTestImplementation dependency."
    )
  }

  @Test
  fun runScreenshotTestWithNoSource() {
    val build = rule.build { androidApplication(":appWithNoSource") { setupProjectNoScreenshotTestSource() } }
    build.updateReferenceImage(projectName = "appWithNoSource")
    build.sstExecutor().run(":appWithNoSource:validateDebugScreenshotTest")
  }

  @Test
  fun runScreenshotTestWithEmptyPreview() {
    val build =
      rule.build {
        gradleProperties {
          add("org.gradle.java.installations.auto-detect", "false")
          add(
            "org.gradle.java.installations.paths",
            listOf(
              TestUtils.getJava17Jdk().toString().replace("\\", "/"),
              TestUtils.getJava25Jdk().toString().replace("\\", "/")
            ).joinToString(",")
          )
        }
        androidApplication {
          files.update("src/screenshotTest/java/com/TopLevelPreviewTest.kt").searchAndReplace("SimpleComposable()", "")
          kotlin { jvmToolchain(25) }
        }
      }
    build.updateReferenceImage()
    val result = build.sstExecutor().run(":app:validateDebugScreenshotTest")

    // Assert that JDK warnings for native access and Unsafe memory access are successfully suppressed
    result.assertErrorDoesNotContain("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called")
    result.assertErrorDoesNotContain("WARNING: A restricted method in java.lang.System has been called")
    result.assertErrorDoesNotContain("WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a future error")
  }

  @Test
  fun runScreenshotTestWithRenderingException() {
    val build =
      rule.build {
        androidApplication {
          files {
            add(
              "src/screenshotTest/java/com/FailingRenderTest.kt",
              """
              package pkg.name

              import androidx.compose.foundation.layout.Box
              import androidx.compose.foundation.layout.size
              import androidx.compose.ui.Modifier
              import androidx.compose.ui.draw.drawBehind
              import androidx.compose.ui.unit.dp
              import androidx.compose.ui.tooling.preview.Preview
              import androidx.compose.runtime.Composable
              import com.android.tools.screenshot.PreviewTest

              class FailingRenderTest {
                  @PreviewTest
                  @Preview(name = "failingRender")
                  @Composable
                  fun failingRenderTest() {
                      Box(
                          modifier = Modifier
                              .size(100.dp)
                              .drawBehind {
                                  throw RuntimeException("Simulated draw-time RenderProblem")
                              }
                      )
                  }
              }
              """
                .trimIndent(),
            )
          }
        }
      }
    val appProject = build.androidApplication()
    val result = build.sstExecutor().expectFailure().run(":app:updateDebugScreenshotTest")

    result.assertErrorContains("Screenshot rendering failed:")
    result.assertErrorContains("Render Problems:")

    val failingTestReferenceScreenshotDir = appProject.resolve("src/screenshotTestDebug/reference/pkg/name/FailingRenderTest")
    assertThat(failingTestReferenceScreenshotDir).doesNotExist()
  }
}
