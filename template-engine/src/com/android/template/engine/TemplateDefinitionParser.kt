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
package com.android.template.engine

internal class TemplateDefinitionParser(private val messageSink: TemplateMessageSink, val relativePath: String) {

  fun getMandatoryObject(obj: JsonSourceObject, memberName: String): JsonSourceObject? {
    return obj[memberName]?.asJsonObject ?: return addError(obj.lineNumber, "Json object does not contain member named '$memberName'")
  }

  fun getMandatoryString(obj: JsonSourceObject, memberName: String): String? {
    return obj[memberName]?.asString ?: addError(obj.lineNumber, "Json object does not contain member named '$memberName'")
  }

  fun getOptionalString(obj: JsonSourceObject, memberName: String): String? {
    return obj[memberName]?.asString
  }

  fun getMandatoryNonEmptyString(obj: JsonSourceObject, memberName: String): String? {
    val value = obj[memberName]?.asString ?: return addError(obj.lineNumber, "Json object does not contain member named '$memberName'")
    if (value.isEmpty()) {
      return addError(obj.lineNumber, "Json object member '$memberName' cannot be empty")
    }
    return value
  }

  fun toSourceLocation(element: JsonSourceElement): FileLocation {
    return FileLocation(relativePath, element.lineNumber)
  }

  private fun <T : Any> addError(lineNumber: Int, message: String): T? {
    messageSink.message(TemplateMessageSink.Severity.Error) { "$relativePath:$lineNumber: $message" }
    return null
  }

  fun <T : Any> addError(obj: JsonSourceElement, message: String): T? {
    return addError(obj.lineNumber, message)
  }

  fun <T : Any> addWarning(obj: JsonSourceElement, message: String): T? {
    messageSink.message(TemplateMessageSink.Severity.Warn) { "$relativePath:${obj.lineNumber}: $message" }
    return null
  }
}
