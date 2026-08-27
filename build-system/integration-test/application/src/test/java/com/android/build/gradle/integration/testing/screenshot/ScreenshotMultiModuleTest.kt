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
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test

class ScreenshotMultiModuleTest {

  @get:Rule
  val rule =
    GradleRule.configure().withProfileOutput().from {
      androidApplication { setupProject() }
      androidLibrary { setupProject() }
      repeat(2) { androidLibrary(":lib2_$it") { setupProject(addEmptyJarToClassPath = false) } }
      gradleProperties { add(BooleanOption.ENABLE_SCREENSHOT_TEST, true) }
    }

  @Test
  fun runPreviewScreenshotTestWithMultiModuleProject() {
    val build = rule.build { useOldPluginStyleForSeparateClassloaders = true }
    verifyClassLoaderSetup(build.updateReferenceImageForAllProjects())
    build.sstExecutor().run("validateDebugScreenshotTest")
  }

  @Test
  fun runUpdateScreenshotTestWithMultiModuleProjectBySingleWorker() {
    val build = rule.build
    build.updateReferenceImageForAllProjects()
    build.sstExecutor().withArguments(listOf("--max-workers", "1")).run("validateDebugScreenshotTest")
  }

  @Test
  fun runPreviewScreenshotTestsOnMultipleFlavors() {
    val build = rule.build {
      androidApplication {
        android {
          flavorDimensions += "new"
          productFlavors {
            create("flavor1") { it.dimension = "new" }
            create("flavor2") { it.dimension = "new" }
          }
        }
        files.update("src/screenshotTest/java/com/ExampleTest.kt").transform {
          """
                        /*
                        $it
                        */
                    """
            .trimIndent()
        }
      }
    }
    val appProject = build.androidApplication()

    build.updateReferenceImage("debug", "flavor1")
    build.updateReferenceImage("debug", "flavor2")

    val flavor1ReferenceScreenshotDir = appProject.resolve("src/screenshotTestFlavor1Debug/reference/pkg/name/TopLevelPreviewTestKt")
    val flavor2ReferenceScreenshotDir = appProject.resolve("src/screenshotTestFlavor2Debug/reference/pkg/name/TopLevelPreviewTestKt")
    assertThat(flavor1ReferenceScreenshotDir.listDirectoryEntries().single().name).isEqualTo("simpleComposableTest_3_748aa731_0.png")
    assertThat(flavor2ReferenceScreenshotDir.listDirectoryEntries().single().name).isEqualTo("simpleComposableTest_3_748aa731_0.png")

    build.sstExecutor().run(":app:validateScreenshotTest")

    val flavor1IndexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor1/index.html")
    val flavor2IndexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor2/index.html")
    val flavor1ClassHtmlReport =
      appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor1/pkg.name.TopLevelPreviewTestKt.html")
    val flavor2ClassHtmlReport =
      appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor2/pkg.name.TopLevelPreviewTestKt.html")
    val flavor1PackageHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor1/pkg.name.html")
    val flavor2PackageHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor2/pkg.name.html")
    assertThat(flavor1IndexHtmlReport).exists()
    assertThat(flavor2IndexHtmlReport).exists()
    assertThat(flavor1ClassHtmlReport).exists()
    assertThat(flavor2ClassHtmlReport).exists()
    val expectedOutput = listOf("""<h3 class="success">simpleComposableTest_3</h3>""")
    expectedOutput.forEach {
      assertThat(flavor1ClassHtmlReport.readText()).contains(it)
      assertThat(flavor2ClassHtmlReport.readText()).contains(it)
    }
    assertThat(flavor1PackageHtmlReport).exists()
    assertThat(flavor2PackageHtmlReport).exists()

    val diffDir1 = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/flavor1/diffs/pkg/name/TopLevelPreviewTestKt")
    val diffDir2 = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/flavor2/diffs/pkg/name/TopLevelPreviewTestKt")
    assert(diffDir1.listDirectoryEntries().isEmpty())
    assert(diffDir2.listDirectoryEntries().isEmpty())
  }

  @Test
  fun runPreviewScreenshotTestWithCrossModuleResources() {
    val build = rule.build {
      androidApplication {
        dependencies { screenshotTestImplementation(project(":lib")) }
        files {
          add(
            "src/screenshotTest/java/com/CrossModuleTest.kt",
            // language=kotlin
            """
            package pkg.name

            import androidx.compose.ui.tooling.preview.Preview
            import androidx.compose.runtime.Composable
            import com.android.tools.screenshot.PreviewTest

            class CrossModuleTest {
                @PreviewTest
                @Preview(showBackground = true)
                @Composable
                fun crossModuleComposableTest() {
                    LibComposable()
                }
            }
            """
              .trimIndent(),
          )
        }
      }
      androidLibrary {
        files {
          add("src/main/res/values/strings.xml", "<resources><string name=\"lib_string\">Library String</string></resources>")
          add("src/main/res/values/colors.xml", "<resources><color name=\"lib_color\">#FF0000</color></resources>")
          add(
            "src/main/java/com/LibComposable.kt",
            // language=kotlin
            """
            package pkg.name

            import androidx.compose.material.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.res.stringResource
            import androidx.compose.ui.res.colorResource
            import androidx.compose.ui.Modifier
            import androidx.compose.foundation.background
            import pkg.name.lib.R

            @Composable
            fun LibComposable() {
                Text(
                    text = stringResource(R.string.lib_string),
                    modifier = Modifier.background(colorResource(R.color.lib_color))
                )
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    val appProject = build.androidApplication()

    val updateResult = build.updateReferenceImage()
    updateResult.assertOutputDoesNotContain("ScreenshotError")
    updateResult.assertErrorDoesNotContain("ScreenshotError")

    val result = build.sstExecutor().run(":app:validateDebugScreenshotTest")
    result.assertOutputDoesNotContain("ScreenshotError")

    val classHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.CrossModuleTest.html")
    assertThat(classHtmlReport).exists()
    assertThat(classHtmlReport.readText()).contains("""<h3 class="success">crossModuleComposableTest</h3>""")
  }
}
