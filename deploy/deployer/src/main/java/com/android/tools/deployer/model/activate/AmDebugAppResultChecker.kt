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
package com.android.tools.deployer.model.activate

import java.util.function.Consumer
import java.util.regex.Pattern

class AmDebugAppResultChecker(onWarning: Consumer<String>? = null, onError: Consumer<String>? = null) :
  ActivationCommandResultChecker(onWarning, onError) {

  private var exceptionStatus = false
  private val exceptionPattern = Pattern.compile("(Exception)")

  override fun processLines(lines: Array<String>) {
    for (line in lines) {
      val matcher = exceptionPattern.matcher(line)
      if (matcher.find()) {
        exceptionStatus = true
        reportError(line)
      }
    }
  }

  override fun check(): Status {
    return if (exceptionStatus) Status.ERROR else Status.SUCCESS
  }
}
