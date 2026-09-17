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

package com.android.tools.render.common

import com.android.ide.common.rendering.api.Result
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.compose.ComposeRenderErrors
import java.awt.image.BufferedImage
import java.io.Serializable

/** Data class that provides information about the problems that happened during a single screenshot creation. */
data class ScreenshotError(
  val status: String,
  val message: String,
  val stackTrace: String,
  val problems: List<RenderProblem>,
  val brokenClasses: List<BrokenClass>,
  val missingClasses: List<String>,
) : Serializable {
  constructor(t: Throwable) : this("", t.message ?: "", t.stackTraceToString(), emptyList(), emptyList(), emptyList())
}

/** Serializable representation of a rendering problem. */
data class RenderProblem(val html: String, val stackTrace: String?) : Serializable

/** Serializable representation of a broken class found during rendering. */
data class BrokenClass(val className: String, val stackTrace: String) : Serializable

/**
 * Converts a [RenderResult] into a [ScreenshotError] if rendering failed or encountered issues.
 *
 * Inspects exceptions and logger messages for known Compose preview failure modes via [ComposeRenderErrors] to enrich the error message
 * with summaries, remediation hints, and unwrapped root causes.
 */
fun RenderResult.toScreenshotError(imageRendered: BufferedImage?): ScreenshotError? {
  if (renderResult.status == Result.Status.SUCCESS && !logger.hasErrors() && imageRendered != null) {
    return null
  }

  val allThrowables =
    sequenceOf(renderResult.exception) + logger.messages.asSequence().map { it.throwable } + logger.brokenClasses.values.asSequence()
  val diagnosedComposeError = allThrowables.mapNotNull { ComposeRenderErrors.match(it) }.firstOrNull()

  val errorMessage =
    when {
      imageRendered == null && renderResult.status == Result.Status.SUCCESS -> "Nothing to render in Preview. Cannot generate image"
      diagnosedComposeError != null -> {
        val hintSuffix = diagnosedComposeError.hint?.let { "\nHint: ${it.trimIndent()}" } ?: ""
        "${diagnosedComposeError.summary}$hintSuffix"
      }
      else -> renderResult.errorMessage ?: ""
    }

  val stackTrace = diagnosedComposeError?.rootCause?.stackTraceToString() ?: renderResult.exception?.stackTraceToString() ?: ""

  return ScreenshotError(
    status = renderResult.status.name,
    message = errorMessage,
    stackTrace = stackTrace,
    problems = logger.messages.map { RenderProblem(it.html, it.throwable?.stackTraceToString()) },
    brokenClasses = logger.brokenClasses.map { BrokenClass(it.key, it.value.stackTraceToString()) },
    missingClasses = logger.missingClasses.toList(),
  )
}

/**
 * Converts a [Throwable] into a [ScreenshotError], diagnosing known Compose preview issues.
 *
 * If the exception matches a recognized Compose error via [ComposeRenderErrors], it is classified as [Result.Status.ERROR_RENDER_TASK] with
 * a remediation hint and unwrapped root cause stack trace; otherwise it defaults to [Result.Status.ERROR_UNKNOWN].
 */
fun Throwable.toScreenshotError(customStatus: String? = null): ScreenshotError {
  val diagnosedComposeError = ComposeRenderErrors.match(this)
  val message =
    if (diagnosedComposeError != null) {
      val hintSuffix = diagnosedComposeError.hint?.let { "\nHint: ${it.trimIndent()}" } ?: ""
      "${diagnosedComposeError.summary}$hintSuffix"
    } else {
      this.message ?: ""
    }
  val rootCause = diagnosedComposeError?.rootCause ?: this
  val status = customStatus ?: if (diagnosedComposeError != null) Result.Status.ERROR_RENDER_TASK.name else Result.Status.ERROR_UNKNOWN.name

  return ScreenshotError(
    status = status,
    message = message,
    stackTrace = rootCause.stackTraceToString(),
    problems = emptyList(),
    brokenClasses = emptyList(),
    missingClasses = emptyList(),
  )
}
