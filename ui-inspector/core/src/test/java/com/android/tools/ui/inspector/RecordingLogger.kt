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

package com.android.tools.ui.inspector

/** A [Logger] that keeps every message it receives, in order, for tests to assert on. */
class RecordingLogger : Logger {
  val messages = mutableListOf<Pair<LogLevel, String>>()

  override fun log(level: LogLevel, message: String) {
    messages += level to message
  }

  /** Every recorded message, one per line, in the order received. */
  fun text(): String = messages.joinToString(separator = "") { (_, message) -> message + "\n" }
}
