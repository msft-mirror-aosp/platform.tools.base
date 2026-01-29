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

package com.android.compose.screenshot.tasks

import com.android.compose.screenshot.report.TestReport
import com.android.compose.screenshot.services.AnalyticsService
import com.android.utils.FileUtils
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

/** Runs screenshot tests of a variant. */
@CacheableTask
abstract class PreviewScreenshotValidationTask : Test() {

  @get:Nested abstract val testEngineInput: PreviewScreenshotTestEngineInput

  @get:Internal abstract val analyticsService: Property<AnalyticsService>

  init {
    classpath =
      objectFactory.fileCollection().apply {
        from(
          testEngineInput.testRuntimeClassDirs,
          testEngineInput.testRuntimeJars,
          testEngineInput.mainRuntimeClassDirs,
          testEngineInput.mainRuntimeJars,
        )
      }
    testClassesDirs = objectFactory.fileCollection().apply { from(testEngineInput.testProjectJars, testEngineInput.testProjectClassDirs) }
    testEngineInput.recordingModeEnabled.set(false)
  }

  override fun getClasspath(): ConfigurableFileCollection {
    return super.getClasspath() as ConfigurableFileCollection
  }

  @TaskAction
  override fun executeTests() {
    // Per b/405923412: Force sequential execution at execution time. This is done here
    // to override any global parallel execution settings that may have been configured.
    // Our custom report generation logic can overwrite the same XML report file
    // if multiple JVMs run in parallel.
    if (this.maxParallelForks > 1) {
      logger.warn(
        "Preview Screenshot Testing does not support parallel execution. " +
          "Overriding maxParallelForks to 1. " +
          "To suppress this warning, explicitly set maxParallelForks = 1 for the 'validateScreenshotTest' task."
      )
      this.maxParallelForks = 1
    }

    analyticsService.get().recordTaskAction(path) {
      var testCount = 0
      addTestListener(
        object : TestListener {
          override fun beforeSuite(suite: TestDescriptor) {}

          override fun afterSuite(suite: TestDescriptor, result: TestResult) {}

          override fun beforeTest(testDescriptor: TestDescriptor?) {
            testCount++
          }

          override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        }
      )

      testEngineInput.copyJvmArgsTo(::jvmArgs)
      FileUtils.cleanOutputDir(reports.junitXml.outputLocation.get().asFile)

      try {
        super.executeTests()
      } finally {
        analyticsService.get().recordPreviewScreenshotTestRun(totalTestCount = testCount)

        // Delete html files which Gradle's Test task generates.
        FileUtils.cleanOutputDir(reports.html.outputLocation.get().asFile)
        TestReport(reports.junitXml.outputLocation.get().asFile, reports.html.outputLocation.get().asFile).generateScreenshotTestReport()
      }
    }
  }
}
