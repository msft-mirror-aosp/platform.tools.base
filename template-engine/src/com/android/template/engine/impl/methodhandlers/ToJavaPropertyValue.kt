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
import java.io.StringWriter
import java.util.Properties

/** Converts a string to a format suitable for a Java .properties file value. */
internal class ToJavaPropertyValue : StringInterpolationMethodHandler {
  override val name: String
    get() = "toJavaPropertyValue"

  override val argCount: Int
    get() = 0

  override fun evaluate(target: String, methodCall: MethodCallNode, evaluateArgument: (StringInterpolationNode) -> String): String {
    return escapePropertyValue(target)
  }

  companion object {
    /** Escapes a string for writing to java .properties file as a "value" of a `key=value` entry */
    internal fun escapePropertyValue(value: String): String {
      val properties = Properties()
      properties.setProperty("k", value) // key doesn't matter
      val writer = StringWriter()
      properties.store(writer, null)
      val s = writer.toString()
      var end = s.length

      // Writer inserts trailing newline
      val lineSeparator = System.lineSeparator()
      if (s.endsWith(lineSeparator)) {
        end -= lineSeparator.length
      }

      val start = s.indexOf('=')
      assert(start != -1) { s }
      return s.substring(start + 1, end)
    }
  }
}
