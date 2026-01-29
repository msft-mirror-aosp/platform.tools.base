/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.compose.screenshot.tasks

import com.android.compose.screenshot.services.AnalyticsService
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class PreviewScreenshotUpdateTask : Test() {

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
    testEngineInput.recordingModeEnabled.set(true)
  }

  override fun getClasspath(): ConfigurableFileCollection {
    return super.getClasspath() as ConfigurableFileCollection
  }

  @TaskAction
  override fun executeTests() =
    analyticsService.get().recordTaskAction(path) {
      if (testEngineInput.testProjectJars.get().isEmpty() && testEngineInput.testProjectClassDirs.get().isEmpty()) {
        return@recordTaskAction
      }
      testEngineInput.copyJvmArgsTo(::jvmArgs)
      super.executeTests()
    }
}
