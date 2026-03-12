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

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class LogcatCollectorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var resultsDir: File
  private lateinit var adb: File

  @Before
  fun setUp() {
    resultsDir = tempFolder.newFolder("results")
    adb = tempFolder.newFile("adb")
  }

  @Test
  fun startCapture_processesLogcatLines() {
    val deviceId = "emulator-5554"

    // Mock for "shell date"
    val dateOutput = "03-05 10:00:00"
    val dateProcess =
      mock<Process> {
        on { inputStream } doReturn dateOutput.byteInputStream()
        on { waitFor() } doReturn 0
      }
    val dateProcessBuilder = mock<ProcessBuilder> { on { start() } doReturn dateProcess }

    // Mock for "logcat"
    val logcatOutput =
      """
      03-05 10:00:01.000  123  456 I TestRunner: started: myMethod(com.example.MyTest)
      03-05 10:00:02.000  123  456 D MyTag: some log
      03-05 10:00:03.000  123  456 I TestRunner: finished: myMethod(com.example.MyTest)
      """
        .trimIndent()
    val logcatProcess =
      mock<Process> {
        on { inputStream } doReturn logcatOutput.byteInputStream()
        on { waitFor(any(), any()) } doReturn true
      }
    val logcatProcessBuilder = mock<ProcessBuilder> { on { start() } doReturn logcatProcess }

    val collector =
      LogcatCollector(resultsDir, adb.absolutePath) { command ->
        if (command.contains("shell") && command.contains("date")) {
          dateProcessBuilder
        } else {
          logcatProcessBuilder
        }
      }

    collector.startCapture(deviceId)

    // Wait for the reader thread to catch the "started" line
    var logcatPath: String? = null
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < 5000) {
      logcatPath = collector.getLogcatPath(deviceId, "com.example.MyTest.myMethod")
      if (logcatPath != null) break
      Thread.sleep(100)
    }

    assertThat(logcatPath).isNotNull()
    val logcatFile = File(logcatPath!!)
    assertThat(logcatFile.exists()).isTrue()

    // Wait a bit for the whole test log to be written
    Thread.sleep(500)
    val logcatContent = logcatFile.readText()
    assertThat(logcatContent).contains("TestRunner: started: myMethod(com.example.MyTest)")
    assertThat(logcatContent).contains("MyTag: some log")
    assertThat(logcatContent).contains("TestRunner: finished: myMethod(com.example.MyTest)")

    collector.cleanup()
  }
}
