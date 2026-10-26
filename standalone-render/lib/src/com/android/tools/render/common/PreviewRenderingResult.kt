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

data class PreviewRenderingResult(
  // Exception that is not bound to a particular screenshot
  val globalError: String?,
  val screenshotResults: List<PreviewScreenshotResult>,
) {
  /**
   * Class name of a fatal [VirtualMachineError] (other than [StackOverflowError], such as `"OutOfMemoryError"`) caught during rendering, or
   * `null` when no fatal JVM error occurred.
   *
   * Declared outside the `data class` primary constructor to preserve binary compatibility of generated `copy` and `componentN` methods and
   * JSON round-trip equality.
   */
  var fatalJvmError: String? = null
    private set

  /**
   * `true` when rendering failed due to a fatal [VirtualMachineError] (other than [StackOverflowError]), indicating that the hosting JVM
   * should retire instead of serving further requests.
   */
  val isFatalJvmError: Boolean
    get() = fatalJvmError != null

  constructor(
    globalError: String?,
    screenshotResults: List<PreviewScreenshotResult>,
    fatalJvmError: String?,
  ) : this(globalError, screenshotResults) {
    this.fatalJvmError = fatalJvmError
  }
}
