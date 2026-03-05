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

package com.android.tools.androidtest.testengine

import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A class responsible for collecting logcat logs from Android devices during test execution. It starts a logcat process per device and
 * parses the output to extract logs for individual test cases.
 *
 * @property resultsDir The directory where logcat files will be stored.
 * @property adbPath The path to the ADB executable.
 * @property processBuilder A factory for creating [ProcessBuilder] instances, primarily exposed for testing purposes.
 */
class LogcatCollector(
  private val resultsDir: File,
  private val adbPath: String,
  private val processBuilder: (command: List<String>) -> ProcessBuilder = { ProcessBuilder(it) },
) {
  companion object {
    private val logger = Logger.getLogger(LogcatCollector::class.java.name)
  }

  /** Tracks logcat processes for each device serial. */
  private val perDeviceLogcatProcesses = ConcurrentHashMap<String, Process>()

  /** Tracks logcat reader threads for each device serial. */
  private val perDeviceLogcatThreads = ConcurrentHashMap<String, Thread>()

  /** Tracks logcat file paths for each test in each device serial. */
  private val perDeviceTestLogcatFiles = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

  /** Starts logcat collection for the given [deviceId]. */
  fun startCapture(deviceId: String) {
    val deviceSerial = deviceId.substringBefore(" - ")
    val deviceTime = getDeviceCurrentTime(deviceSerial)
    val command = mutableListOf(adbPath, "-s", deviceSerial, "logcat", "-v", "threadtime", "-b", "main", "-b", "crash")
    if (deviceTime != null) {
      command.addAll(listOf("-T", deviceTime))
    }

    try {
      val process = processBuilder(command).start()
      perDeviceLogcatProcesses[deviceId] = process
      val thread =
        Thread(
          {
            val reader = process.inputStream.bufferedReader()
            var currentWriter: BufferedWriter? = null
            var testRunInProgress = false

            try {
              reader.forEachLine { line ->
                if (line.contains("TestRunner: started: ")) {
                  testRunInProgress = true
                  val (testPackage, testClass, testMethod) = parseTestInfo(line)
                  val logcatFile = generateLogcatFileName(deviceId, testPackage, testClass, testMethod)
                  logcatFile.parentFile?.mkdirs()
                  currentWriter?.close()
                  currentWriter = logcatFile.bufferedWriter()

                  val testName = "$testPackage.$testClass.$testMethod"
                  perDeviceTestLogcatFiles.getOrPut(deviceId) { ConcurrentHashMap() }[testName] = logcatFile.absolutePath
                }

                if (testRunInProgress) {
                  currentWriter?.write(line)
                  currentWriter?.newLine()
                  currentWriter?.flush()
                }

                if (line.contains("TestRunner: finished:")) {
                  testRunInProgress = false
                  currentWriter?.close()
                  currentWriter = null
                }
              }
            } catch (t: Throwable) {
              logger.log(Level.WARNING, "logcat reader failed for $deviceId", t)
            } finally {
              currentWriter?.close()
            }
          },
          "LogcatReader-$deviceId",
        )
      perDeviceLogcatThreads[deviceId] = thread
      thread.start()
    } catch (t: Throwable) {
      logger.log(Level.SEVERE, "failed to start logcat for $deviceId", t)
    }
  }

  /** Stops logcat collection for the given [deviceId]. */
  fun stopCapture(deviceId: String) {
    val process = perDeviceLogcatProcesses.remove(deviceId) ?: return
    try {
      // Wait for a bit (1 second) to let the logcat reader thread catch up with the final logs
      // from the device before we destroy the process. This is especially important for the
      // "TestRunner: finished" line which marks the end of a test case.
      Thread.sleep(1000)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    process.destroy()
    try {
      process.waitFor(2, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    perDeviceLogcatThreads.remove(deviceId)?.apply {
      interrupt()
      join(1000)
    }
  }

  /** Returns the absolute path to the logcat file for the given [deviceId] and [testName], or null if not found. */
  fun getLogcatPath(deviceId: String, testName: String): String? {
    return perDeviceTestLogcatFiles[deviceId]?.get(testName)
  }

  /** Stops all active logcat collections and releases resources. */
  fun cleanup() {
    perDeviceLogcatProcesses.values.forEach {
      it.destroy()
      try {
        it.waitFor(2, TimeUnit.SECONDS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    perDeviceLogcatProcesses.clear()
    perDeviceLogcatThreads.values.forEach {
      it.interrupt()
      it.join(1000)
    }
    perDeviceLogcatThreads.clear()
  }

  private fun getDeviceCurrentTime(deviceSerial: String): String? {
    try {
      val process = processBuilder(listOf(adbPath, "-s", deviceSerial, "shell", "date", "+%m-%d\\ %H:%M:%S")).start()
      val output = process.inputStream.bufferedReader().readText().trim()
      if (process.waitFor() == 0 && output.isNotEmpty()) {
        return "$output.000"
      }
    } catch (t: Throwable) {
      logger.log(Level.WARNING, "failed to get device time for $deviceSerial", t)
    }
    return null
  }

  /**
   * Parses the test information from the logcat line.
   *
   * This method relies on the standard logging format of [AndroidJUnitRunner]. Specifically, it looks for the string "TestRunner: started:
   * methodName(className)" in the logcat output to identify the start of a test and extract its details.
   */
  private fun parseTestInfo(line: String): Triple<String, String, String> {
    val part = line.substringAfter("TestRunner: started: ")
    val methodName = part.substringBefore("(").trim()
    val fullClassName = part.substringAfter("(").substringBefore(")")
    val pkg = fullClassName.substringBeforeLast(".", "")
    val className = fullClassName.substringAfterLast(".")
    return Triple(pkg, className, methodName)
  }

  private fun generateLogcatFileName(deviceId: String, testPackage: String, testClass: String, testMethod: String): File {
    val fileName = "logcat-$testPackage.$testClass-$testMethod.txt"
    return File(resultsDir, fileName)
  }
}
