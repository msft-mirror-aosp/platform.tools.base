/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.tools.rendering.compose.ComposeViewInfo
import java.io.Serializable

/**
 * Result of a screenshot creation.
 *
 * [previewId] is a string designed to uniquely identify the preview (currently an image name without the suffix for method params).
 * [methodFQN] is the fully qualified name of the test method that this result is generated from. [imagePath] is the relative path of the
 * screenshot image in the output directory (using OS-dependent file separators), or an empty string (`""`) when image rendering is disabled
 * (`renderImage = false`). When image rendering is enabled and succeeds, the image is created in the output folder at this path; otherwise,
 * it identifies the screenshot that failed. [error] contains info about issues encountered during rendering. Both an image and an error may
 * be present if rendering succeeded with fidelity warnings or problems. [viewInfos] is the Compose hierarchy of the preview, read only when
 * the component tree is requested (and empty otherwise). [warnings] contains any warning-level problems logged to the render logger during
 * rendering, even when the render succeeded. [componentTreePath] is the relative path of the serialized component tree JSON file in the
 * output directory if requested and extracted.
 *
 * Note: [warnings] is declared outside the `data class` primary constructor to preserve JVM binary compatibility of the 7-argument
 * constructor, `copy`, and `componentN` methods; [copy] does not preserve [warnings].
 */
data class PreviewScreenshotResult(
  val previewId: String,
  val methodFQN: String,
  val imagePath: String,
  val error: ScreenshotError?,
  val displayName: String? = null,
  val viewInfos: List<ComposeViewInfo> = emptyList(),
  val componentTreePath: String? = null,
) : Serializable {
  /** Warning-level problems logged to the render logger during rendering, even when the render succeeded. */
  var warnings: List<RenderProblem> = emptyList()
    private set

  constructor(
    previewId: String,
    methodFQN: String,
    imagePath: String,
    error: ScreenshotError?,
    displayName: String? = null,
    viewInfos: List<ComposeViewInfo> = emptyList(),
    componentTreePath: String? = null,
    warnings: List<RenderProblem>,
  ) : this(previewId, methodFQN, imagePath, error, displayName, viewInfos, componentTreePath) {
    this.warnings = warnings
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is PreviewScreenshotResult) return false
    return previewId == other.previewId &&
      methodFQN == other.methodFQN &&
      imagePath == other.imagePath &&
      error == other.error &&
      displayName == other.displayName &&
      viewInfos == other.viewInfos &&
      componentTreePath == other.componentTreePath &&
      warnings == other.warnings
  }

  override fun hashCode(): Int {
    var result = previewId.hashCode()
    result = 31 * result + methodFQN.hashCode()
    result = 31 * result + imagePath.hashCode()
    result = 31 * result + (error?.hashCode() ?: 0)
    result = 31 * result + (displayName?.hashCode() ?: 0)
    result = 31 * result + viewInfos.hashCode()
    result = 31 * result + (componentTreePath?.hashCode() ?: 0)
    result = 31 * result + warnings.hashCode()
    return result
  }

  override fun toString(): String =
    "PreviewScreenshotResult(previewId=$previewId, methodFQN=$methodFQN, imagePath=$imagePath, error=$error, displayName=$displayName, viewInfos=$viewInfos, componentTreePath=$componentTreePath, warnings=$warnings)"

  /** Ensures non-null collection fields default to [emptyList] when deserialized from older streams that omit them. */
  private fun readResolve(): Any {
    @Suppress("SENSELESS_COMPARISON") val resolvedViewInfos = if (viewInfos == null) emptyList() else viewInfos
    @Suppress("SENSELESS_COMPARISON") val resolvedWarnings = if (warnings == null) emptyList() else warnings
    return if (resolvedViewInfos !== viewInfos || resolvedWarnings !== warnings) {
      PreviewScreenshotResult(
        previewId = previewId,
        methodFQN = methodFQN,
        imagePath = imagePath,
        error = error,
        displayName = displayName,
        viewInfos = resolvedViewInfos,
        componentTreePath = componentTreePath,
        warnings = resolvedWarnings,
      )
    } else {
      this
    }
  }

  companion object {
    // Matches the published 0.0.1-alpha17 7-field serialVersionUID so streams remain compatible across isolatedClassLoaderForRendering when
    // android.compose.screenshot.validationEngineVersion differs from the plugin version.
    private const val serialVersionUID: Long = -1391406289525218573L
  }
}
