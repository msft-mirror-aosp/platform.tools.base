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

class AmStartResultChecker(onWarning: Consumer<String>? = null, onError: Consumer<String>? = null) :
  ActivationCommandResultChecker(onWarning, onError) {

  private var errorStatus = false
  private val errorPattern = Pattern.compile("^Error:.*")
  private val warningPattern = Pattern.compile("^Warning:.*")

  override fun processLines(lines: Array<String>) {
    for (line in lines) {
      if (errorPattern.matcher(line).matches()) {
        errorStatus = true
        reportError(line)
      } else if (warningPattern.matcher(line).matches()) {
        reportWarning(line)
      }
    }
  }

  override fun check(): Status {
    return if (errorStatus) Status.ERROR else Status.SUCCESS
  }
}
