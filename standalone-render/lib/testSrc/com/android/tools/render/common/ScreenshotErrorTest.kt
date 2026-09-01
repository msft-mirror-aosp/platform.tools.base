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

package com.android.tools.render.common

import com.android.ide.common.rendering.api.Result
import com.android.testutils.TestUtils
import com.android.tools.render.RenderEnvironmentBootstrapper
import com.android.tools.render.StandaloneRenderModelModule
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.compose.ComposeRenderErrors
import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException
import kotlin.io.path.absolutePathString
import org.junit.Test

class ScreenshotErrorTest {

  private fun withRenderModule(block: (StandaloneRenderModelModule) -> Unit) {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "com.android.tools.render.test",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )
    bootstrapper.bootstrap().use { renderer -> block(renderer.module) }
  }

  @Test
  fun testThrowableToScreenshotErrorWithCompositionLocalExceptionSetsErrorRenderTask() {
    val rootCause = IllegalStateException("CompositionLocal LocalGraphicsContext not present")
    val wrapped = InvocationTargetException(rootCause)

    val error = wrapped.toScreenshotError()
    assertThat(error.status).isEqualTo("ERROR_RENDER_TASK")
    assertThat(error.message).contains("Failed to instantiate Composition Local")
    assertThat(error.message).contains(ComposeRenderErrors.COMPOSITION_LOCAL_NOT_FOUND_HINT.trimIndent())
    assertThat(error.stackTrace).contains("LocalGraphicsContext not present")
  }

  @Test
  fun testThrowableToScreenshotErrorWithViewModelExceptionSetsErrorRenderTask() {
    val viewModelError =
      IllegalStateException("ViewModel initialization error").apply {
        stackTrace =
          arrayOf(
            StackTraceElement(
              "androidx.compose.ui.tooling.CommonPreviewUtils",
              "invokeComposableMethod",
              "CommonPreviewUtils.kt",
              149,
            ),
            StackTraceElement(
              "androidx.lifecycle.viewmodel.compose.ViewModelKt",
              "viewModel",
              "ViewModel.kt",
              72,
            ),
          )
      }
    val wrapped = InvocationTargetException(viewModelError)

    val error = wrapped.toScreenshotError()
    assertThat(error.status).isEqualTo("ERROR_RENDER_TASK")
    assertThat(error.message).contains("Failed to instantiate a ViewModel")
    assertThat(error.message).contains(ComposeRenderErrors.VIEW_MODEL_HINT.trimIndent())
    assertThat(error.stackTrace).contains("ViewModel initialization error")
  }

  @Test
  fun testThrowableToScreenshotErrorWithGenericExceptionSetsErrorUnknown() {
    val generic = IllegalArgumentException("Something went wrong")

    val error = generic.toScreenshotError()
    assertThat(error.status).isEqualTo("ERROR_UNKNOWN")
    assertThat(error.message).isEqualTo("Something went wrong")
    assertThat(error.stackTrace).contains("Something went wrong")
  }

  @Test
  fun testRenderResultToScreenshotErrorReturnsNullOnSuccess() {
    withRenderModule { module ->
      val renderResult =
        RenderResult.createErrorRenderResult(
          Result.Status.SUCCESS,
          module,
          { throw NotImplementedError() },
          null,
          RenderLogger(),
        )
      val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)

      assertThat(renderResult.toScreenshotError(image)).isNull()
    }
  }

  @Test
  fun testRenderResultToScreenshotErrorWhenNoImageRenderedOnSuccess() {
    withRenderModule { module ->
      val renderResult =
        RenderResult.createErrorRenderResult(
          Result.Status.SUCCESS,
          module,
          { throw NotImplementedError() },
          null,
          RenderLogger(),
        )

      val error = renderResult.toScreenshotError(null)
      assertThat(error).isNotNull()
      assertThat(error?.message).isEqualTo("Nothing to render in Preview. Cannot generate image")
    }
  }

  @Test
  fun testRenderResultToScreenshotErrorWithComposeExceptionInLogger() {
    withRenderModule { module ->
      val logger = RenderLogger()
      val rootCause = IllegalStateException("CompositionLocal LocalGraphicsContext not present")
      val wrapped = InvocationTargetException(rootCause)
      logger.error("INFLATE", "Failed to inflate", wrapped, null, null)

      val renderResult =
        RenderResult.createRenderTaskErrorResult(
          module,
          { throw NotImplementedError() },
          wrapped,
          logger,
        )

      val error = renderResult.toScreenshotError(null)
      assertThat(error).isNotNull()
      assertThat(error?.status).isEqualTo("ERROR_RENDER_TASK")
      assertThat(error?.message).contains("Failed to instantiate Composition Local")
      assertThat(error?.message).contains(ComposeRenderErrors.COMPOSITION_LOCAL_NOT_FOUND_HINT.trimIndent())
      assertThat(error?.stackTrace).contains("LocalGraphicsContext not present")
    }
  }
}
