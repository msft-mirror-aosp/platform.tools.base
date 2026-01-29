/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.testing.suites

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Simplistic logger for test JUnit test engines. */
class TestEngineLogger(val loggerFile: File) {
  private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

  private fun log(level: String, message: String) {
    val timestamp = LocalDateTime.now().format(dateTimeFormatter)
    val logEntry = "[$timestamp] [$level] $message\n"

    try {
      loggerFile.appendText(logEntry)
    } catch (e: Exception) {
      System.err.println("Error writing to log file '${loggerFile.absolutePath}': ${e.message}")
    }
  }

  fun info(message: String) = log("INFO", message)

  fun debug(message: String) = log("DEBUG", message)

  fun warn(message: String) = log("WARN", message)

  fun error(message: String) = log("ERROR", message)
}
