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

import com.android.template.engine.TemplateMessageSink.Severity

/** A simple message sink interface used by the template engine. */
interface TemplateMessageSink {
  val outputSeverity: Severity

  enum class Severity {
    Debug,
    Verbose,
    Info,
    Warn,
    Error,
  }

  fun message(severity: Severity, message: String)

  fun hasErrors(): Boolean

  companion object {
    fun createDefault(outputSeverity: Severity): TemplateMessageSink {
      return DefaultTemplateMessageSink(outputSeverity)
    }
  }
}

/**
 * Add a message to this [TemplateMessageSink] if [severity] is greater than [TemplateMessageSink.outputSeverity]
 *
 * Note: This function is inline so that [lazyMessage] is only evaluated if the severity is applicable.
 */
inline fun TemplateMessageSink.message(severity: Severity, lazyMessage: () -> String) {
  if (severity >= outputSeverity) {
    message(severity, lazyMessage())
  }
}

open class DefaultTemplateMessageSink(override val outputSeverity: Severity) : TemplateMessageSink {
  private val messagesMutable = mutableListOf<MessageEntry>()

  data class MessageEntry(val severity: Severity, val message: String)

  val messages: List<MessageEntry>
    get() = messagesMutable.toList()

  open fun onMessage(entry: MessageEntry) {
    println("${severityToString(entry.severity)}: ${entry.message}")
  }

  override fun message(severity: Severity, message: String) {
    MessageEntry(severity, message).also {
      messagesMutable.add(it)
      onMessage(it)
    }
  }

  override fun hasErrors(): Boolean {
    return messages.any { it.severity >= Severity.Error }
  }

  private fun severityToString(severity: Severity): String {
    return when (severity) {
      Severity.Debug -> "DEBUG"
      Severity.Verbose -> "VERBOSE"
      Severity.Info -> "INFO"
      Severity.Warn -> "WARN"
      Severity.Error -> "ERROR"
    }
  }
}
