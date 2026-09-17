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

package com.android.tools.androidtest.testengine.report

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.logging.Level
import java.util.logging.Logger

/** Identifies a parsed instrumentation test for XML reporting. */
data class DdmlibTestIdentifier(val className: String, val testName: String)

/** Minimal implementation replicating ddmlib's XmlTestRunListener for writing JUnit test results to XML files. */
open class XmlTestRunListener {
  private var reportDir = File(System.getProperty("java.io.tmpdir"))
  private var runName = ""
  private var numTests = 0
  private val testResults = LinkedHashMap<DdmlibTestIdentifier, TestResult>()
  private var currentTestStartTime = 0L
  private var hostName = "localhost"

  private data class TestResult(
    val testId: DdmlibTestIdentifier,
    var failed: Boolean = false,
    var failureTrace: String? = null,
    var elapsedTimeMs: Long = 0L,
  )

  fun setReportDir(file: File) {
    reportDir = file
  }

  open fun getResultFile(reportDir: File): File {
    return File.createTempFile("test_result_", ".xml", reportDir)
  }

  open fun getPropertiesAttributes(): Map<String, String> = emptyMap()

  fun testRunStarted(runName: String, testCount: Int) {
    this.runName = runName
    this.numTests = testCount
    this.testResults.clear()
  }

  fun testStarted(test: DdmlibTestIdentifier) {
    currentTestStartTime = System.currentTimeMillis()
    testResults[test] = TestResult(test)
  }

  fun testFailed(test: DdmlibTestIdentifier, trace: String) {
    testResults[test]?.let {
      it.failed = true
      it.failureTrace = trace
    }
  }

  fun testEnded(test: DdmlibTestIdentifier, testMetrics: Map<String, String>) {
    testResults[test]?.let { it.elapsedTimeMs = System.currentTimeMillis() - currentTestStartTime }
  }

  fun testRunFailed(errorMessage: String) {
    // No-op or log
  }

  open fun testRunEnded(elapsedTime: Long, runMetrics: Map<String, String>?) {
    generateDocument(reportDir, elapsedTime)
  }

  private fun generateDocument(reportDir: File, elapsedTime: Long) {
    val timestamp = getTimestamp()
    var stream: OutputStream? = null
    try {
      val resultFile = getResultFile(reportDir)
      stream = BufferedOutputStream(FileOutputStream(resultFile))
      writeXml(stream, timestamp, elapsedTime)
      val msg = "XML test result file generated at ${resultFile.absolutePath}."
      Logger.getLogger(XmlTestRunListener::class.java.name).log(Level.INFO, msg)
    } catch (e: IOException) {
      Logger.getLogger(XmlTestRunListener::class.java.name).log(Level.SEVERE, "Failed to generate report data", e)
    } finally {
      try {
        stream?.close()
      } catch (ignored: IOException) {}
    }
  }

  internal fun writeXml(stream: OutputStream, timestamp: String, elapsedTime: Long) {
    val writer = stream.writer(Charsets.UTF_8)
    writer.write("<?xml version='1.0' encoding='UTF-8' ?>\n")

    val totalTests = testResults.size
    val totalFailures = testResults.values.count { it.failed }
    val totalTimeSec = String.format(Locale.US, "%.3f", elapsedTime / 1000.0)

    if (testResults.isEmpty()) {
      writer.write(
        "<testsuites tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"$totalTimeSec\" timestamp=\"$timestamp\" hostname=\"$hostName\" />\n"
      )
      writer.flush()
      return
    }

    writer.write(
      "<testsuites tests=\"$totalTests\" failures=\"$totalFailures\" errors=\"0\" skipped=\"0\" time=\"$totalTimeSec\" timestamp=\"$timestamp\" hostname=\"$hostName\">\n"
    )

    // Group by class name maintaining insertion order
    val classResults = LinkedHashMap<String, MutableList<TestResult>>()
    for (result in testResults.values) {
      classResults.computeIfAbsent(result.testId.className) { mutableListOf() }.add(result)
    }

    for ((className, tests) in classResults) {
      val suiteTests = tests.size
      val suiteFailures = tests.count { it.failed }
      val suiteTimeMs = tests.sumOf { it.elapsedTimeMs }
      val suiteTimeSec = String.format(Locale.US, "%.3f", suiteTimeMs / 1000.0)

      writer.write(
        "  <testsuite name=\"${escapeXmlAttribute(className)}\" tests=\"$suiteTests\" failures=\"$suiteFailures\" errors=\"0\" skipped=\"0\" time=\"$suiteTimeSec\" timestamp=\"$timestamp\" hostname=\"$hostName\">\n"
      )
      writer.write("    <properties>\n")
      for ((key, value) in getPropertiesAttributes()) {
        writer.write("      <property name=\"${escapeXmlAttribute(key)}\" value=\"${escapeXmlAttribute(value)}\" />\n")
      }
      writer.write("    </properties>\n")

      for (result in tests) {
        val testTimeSec = String.format(Locale.US, "%.3f", result.elapsedTimeMs / 1000.0)
        val testName = escapeXmlAttribute(result.testId.testName)
        val testClass = escapeXmlAttribute(result.testId.className)

        if (result.failed) {
          writer.write("    <testcase name=\"$testName\" classname=\"$testClass\" time=\"$testTimeSec\">\n")
          writer.write("      <failure>${escapeXmlText(result.failureTrace ?: "")}</failure>\n")
          writer.write("    </testcase>\n")
        } else {
          writer.write("    <testcase name=\"$testName\" classname=\"$testClass\" time=\"$testTimeSec\" />\n")
        }
      }

      writer.write("  </testsuite>\n")
    }

    writer.write("</testsuites>\n")
    writer.flush()
  }

  private fun escapeXmlAttribute(s: String): String {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
  }

  private fun escapeXmlText(s: String): String {
    val sanitized = s.replace("\u0000", "<\\0>")
    return sanitized.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
  }

  internal fun getTimestamp(): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    dateFormat.timeZone = TimeZone.getTimeZone("UTC")
    return dateFormat.format(Date())
  }
}
