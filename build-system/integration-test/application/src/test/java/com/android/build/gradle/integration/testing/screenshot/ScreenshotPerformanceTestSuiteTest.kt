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
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/** Performance tests for screenshot test suites declared through `testOptions.screenshotTests`. */
class ScreenshotPerformanceTestSuiteTest {

  /** The counterpart of [ConfigureMaxParallelForksCallback], which configures the validation task of the screenshot test plugin. */
  class ConfigureMaxParallelForksWithTestSuiteCallback : GenericCallback {
    override fun handleProject(project: Project) {
      project.afterEvaluate {
        project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java) {
          if (it.name == getTestSuiteValidateTaskName(projectName = "")) {
            println("Forcibly setting maxParallelForks to 4 for task ${it.path}")
            it.maxParallelForks = 4
          }
        }
      }
    }
  }

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
        androidApplication { setupScreenshotTestSuiteProject() }
        androidLibrary { setupScreenshotTestSuiteProject() }
        repeat(2) { androidLibrary(":lib2_$it") { setupScreenshotTestSuiteProject(addEmptyJarToClassPath = false) } }
        gradleProperties {
          add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
          add(BooleanOption.TEST_SUITE_SUPPORT, true)
        }
      }

  @Test
  fun runPreviewScreenshotTestInParallel() {
    val build = rule.build
    build.updateReferenceImageWithTestSuite()
    build.sstExecutor().withArguments(listOf("--parallel", "--max-workers=4")).run(getTestSuiteValidateTaskName())
    val appProject = build.androidApplication()
    val indexHtmlReport = getTestSuiteHtmlReportIndexFile(appProject)
    assertThat(indexHtmlReport).exists()
    val classHtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.ExampleTest")
    assertThat(classHtmlReport).exists()
    val class2HtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.TopLevelPreviewTestKt")
    assertThat(class2HtmlReport).exists()
    assertAllTestsPassed(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.ExampleTest"), "simpleComposableTest_simpleComposable")
    assertThat(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.TopLevelPreviewTestKt"))
      .containsExactly("simpleComposableTest_3", TEST_SUITE_REPORT_PASSED)
  }

  @Test
  fun runValidation_whenMaxParallelForksIsConfiguredGlobally() {
    val build = rule.build { androidApplication { pluginCallbacks += ConfigureMaxParallelForksWithTestSuiteCallback::class.java } }
    val appProject = build.androidApplication()

    build.updateReferenceImageWithTestSuite()

    val result = build.validateScreenshotTestWithTestSuite()

    assertThat(result.stdout).contains("Forcibly setting maxParallelForks to 4 for task ${getTestSuiteValidateTaskName()}")

    val indexHtmlReport = getTestSuiteHtmlReportIndexFile(appProject)
    assertThat(indexHtmlReport).exists()

    val classHtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.ExampleTest")
    assertThat(classHtmlReport).exists()
    assertAllTestsPassed(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.ExampleTest"), "simpleComposableTest_simpleComposable")
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

        // CheckMemoryUsageCallback adds the memory printer to the test engine of the screenshot test plugin. The runtime classpath of a
        // test
        // suite is on the classpath of its test task, so the memory printer is a dependency of the test suite instead.
        android {
          testOptions.screenshotTests.named("screenshotTest") {
            it.dependencies { implementation.add("com.mytest.memory-printer:memory-printer:1.0") }
          }
        }
        pluginCallbacks += CheckMemoryUsageCallback::class.java
      }
    }

    val result = rule.build.updateReferenceImageWithTestSuite()
    result.assertOutputContains("HEAP: Used:")
    result.assertErrorDoesNotContain(CheckMemoryUsageCallback.UNUSUAL_HEAP_MEMORY_GROWTH_ERROR_MESSAGE)
  }

  /** Asserts that the test named [testName] is one of the tests with [statuses] in the report, and that all of them passed. */
  private fun assertAllTestsPassed(statuses: Map<String, String>, testName: String) {
    assertThat(statuses).containsEntry(testName, TEST_SUITE_REPORT_PASSED)
    assertThat(statuses.values.toSet()).containsExactly(TEST_SUITE_REPORT_PASSED)
  }
}
