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

package com.android.tools.screenshot.descriptor

import com.android.tools.render.common.BrokenClass
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.render.common.RenderProblem
import com.android.tools.render.common.ScreenshotError
import com.android.tools.screenshot.PreviewScreenshotExecutionContext
import com.android.tools.screenshot.ScreenshotRenderException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.hierarchical.Node
import org.junit.rules.TemporaryFolder

class PreviewScreenshotDescriptorTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val noopExecutionListener =
    object : EngineExecutionListener {
      override fun executionStarted(testDescriptor: TestDescriptor?) {}

      override fun executionFinished(
        testDescriptor: TestDescriptor?,
        testExecutionResult: TestExecutionResult?,
      ) {}

      override fun executionSkipped(testDescriptor: TestDescriptor?, reason: String?) {}

      override fun reportingEntryPublished(testDescriptor: TestDescriptor?, entry: ReportEntry?) {}

      override fun dynamicTestRegistered(testDescriptor: TestDescriptor?) {}
    }

  private val dummyDynamicTestExecutor =
    object : Node.DynamicTestExecutor {
      override fun execute(
        testDescriptor: TestDescriptor?,
        executionListener: EngineExecutionListener?,
      ): java.util.concurrent.Future<*>? {
        return null
      }

      override fun execute(testDescriptor: TestDescriptor?) {}

      override fun awaitFinished() {}
    }

  @Test
  fun execute_withScreenshotError_throwsScreenshotRenderException() {
    val screenshotError =
      ScreenshotError(
        status = "FAILED",
        message = "Simulated rendering exception in Layoutlib",
        stackTrace = "com.example.MyComposable.render(MyComposable.kt:10)",
        problems = listOf(RenderProblem("<html>Problem details</html>", "problemStack...")),
        brokenClasses = emptyList(),
        missingClasses = listOf("com.example.MissingClass"),
      )

    val result =
      PreviewScreenshotResult(
        previewId = "myPreviewId",
        methodFQN = "com.example.MyTestClass.myTestMethod",
        imagePath = "my_image.png",
        error = screenshotError,
      )

    val descriptor =
      PreviewScreenshotDescriptor(
        parentId = UniqueId.forEngine("preview-screenshot-test-engine"),
        className = "com.example.MyTestClass",
        methodName = "myTestMethod",
        previewName = "myPreviewName",
        previewDisplayName = "myPreviewDisplayName",
        previewScreenshotResultIndex = 0,
        previewScreenshotResult = result,
      )

    val context =
      PreviewScreenshotExecutionContext(
        executionListener = noopExecutionListener,
        methodNameToPreview = emptyMap(),
        previewImageOutputDir = tempDir.newFolder("output"),
        previewDiffImageOutputDir = tempDir.newFolder("diff"),
        referenceImageDir = tempDir.newFolder("ref"),
        projectRoot = tempDir.root,
      )

    val exception =
      assertThrows(ScreenshotRenderException::class.java) {
        descriptor.execute(context, dummyDynamicTestExecutor)
      }

    val msg = exception.message ?: ""
    assertThat(msg).contains("Screenshot rendering failed: Simulated rendering exception in Layoutlib")
    assertThat(msg).contains("Problem details")
    assertThat(msg).contains("Missing Classes: com.example.MissingClass")
  }

  @Test
  fun execute_withScreenshotErrorHavingBrokenClasses_throwsScreenshotRenderExceptionWithBrokenClassDetails() {
    val screenshotError =
      ScreenshotError(
        status = "FAILED",
        message = "Class initialization failed",
        stackTrace = "java.lang.NoClassDefFoundError: com/example/MyClass",
        problems = emptyList(),
        brokenClasses = listOf(BrokenClass("com.example.MyClass", "stacktrace details of broken class")),
        missingClasses = emptyList(),
      )

    val result =
      PreviewScreenshotResult(
        previewId = "myPreviewId",
        methodFQN = "com.example.MyTestClass.myTestMethod",
        imagePath = "my_image.png",
        error = screenshotError,
      )

    val descriptor =
      PreviewScreenshotDescriptor(
        parentId = UniqueId.forEngine("preview-screenshot-test-engine"),
        className = "com.example.MyTestClass",
        methodName = "myTestMethod",
        previewName = "myPreviewName",
        previewDisplayName = "myPreviewDisplayName",
        previewScreenshotResultIndex = 0,
        previewScreenshotResult = result,
      )

    val context =
      PreviewScreenshotExecutionContext(
        executionListener = noopExecutionListener,
        methodNameToPreview = emptyMap(),
        previewImageOutputDir = tempDir.newFolder("output"),
        previewDiffImageOutputDir = tempDir.newFolder("diff"),
        referenceImageDir = tempDir.newFolder("ref"),
        projectRoot = tempDir.root,
      )

    val exception =
      assertThrows(ScreenshotRenderException::class.java) {
        descriptor.execute(context, dummyDynamicTestExecutor)
      }

    val msg = exception.message ?: ""
    assertThat(msg).contains("Screenshot rendering failed: Class initialization failed")
    assertThat(msg).contains("Broken Classes:")
    assertThat(msg).contains("Class: com.example.MyClass")
    assertThat(msg).contains("stacktrace details of broken class")
  }
}
