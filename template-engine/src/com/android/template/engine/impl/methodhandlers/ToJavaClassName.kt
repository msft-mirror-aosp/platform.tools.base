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
package com.android.template.engine.impl.methodhandlers

import com.android.template.engine.impl.MethodCallNode
import com.android.template.engine.impl.StringInterpolationMethodHandler
import com.android.template.engine.impl.StringInterpolationNode

/** Converts a string to a valid Java class name (PascalCase). */
internal class ToJavaClassName : StringInterpolationMethodHandler {
  override val name: String
    get() = "toJavaClassName"

  override val argCount: Int
    get() = 0

  override fun evaluate(target: String, methodCall: MethodCallNode, evaluateArgument: (StringInterpolationNode) -> String): String {
    return stringToJavaClassName(target)
  }

  companion object {
    fun stringToJavaClassName(value: String): String {
      // Split by anything that is NOT a letter or a digit.
      // This will remove spaces, dashes, underscores, and special characters.
      val parts = value.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotEmpty() }
      var result =
        parts.joinToString("") { part ->
          // Capitalize the first letter, keep the rest as is to preserve acronyms (e.g. ADT)
          part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
      // Java identifiers cannot start with a digit.
      if (result.isNotEmpty() && result[0].isDigit()) {
        result = "_$result"
      }
      return result
    }
  }
}
