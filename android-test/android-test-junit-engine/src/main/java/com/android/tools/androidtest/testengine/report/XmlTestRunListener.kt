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
  private var currentTest: DdmlibTestIdentifier? = null
  private var currentTestStartTime = 0L
  private var hostName = "localhost"
  private var isRunEnded = false

  enum class Status {
    PASSED,
    FAILURE,
    IGNORED,
    ASSUMPTION_FAILURE,
  }

  private data class TestResult(
    val testId: DdmlibTestIdentifier,
    var status: Status = Status.PASSED,
    var stackTrace: String? = null,
    var elapsedTimeMs: Long = 0L,
    var completed: Boolean = false,
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
    this.currentTest = null
    this.isRunEnded = false
  }

  fun testStarted(test: DdmlibTestIdentifier) {
    currentTestStartTime = System.currentTimeMillis()
    currentTest = test
    testResults[test] = TestResult(test)
  }

  fun testFailed(test: DdmlibTestIdentifier, trace: String) {
    val result = testResults.computeIfAbsent(test) { TestResult(it) }
    result.status = Status.FAILURE
    result.stackTrace = trace
  }

  fun testIgnored(test: DdmlibTestIdentifier) {
    val result = testResults.computeIfAbsent(test) { TestResult(it) }
    result.status = Status.IGNORED
  }

  fun testAssumptionFailure(test: DdmlibTestIdentifier, trace: String) {
    val result = testResults.computeIfAbsent(test) { TestResult(it) }
    result.status = Status.ASSUMPTION_FAILURE
    result.stackTrace = trace
  }

  fun testEnded(test: DdmlibTestIdentifier, testMetrics: Map<String, String>) {
    testResults[test]?.let {
      it.elapsedTimeMs = System.currentTimeMillis() - currentTestStartTime
      it.completed = true
    }
    if (currentTest == test) {
      currentTest = null
    }
  }

  fun testRunFailed(errorMessage: String) {
    val inFlightTest = currentTest?.let { testResults[it] }
    if (inFlightTest != null && !inFlightTest.completed) {
      // If a test was actively running when the run aborted/crashed, attribute the failure to it.
      inFlightTest.status = Status.FAILURE
      inFlightTest.stackTrace =
        if (inFlightTest.stackTrace.isNullOrBlank()) {
          errorMessage
        } else {
          "${inFlightTest.stackTrace}\n$errorMessage"
        }
      inFlightTest.elapsedTimeMs = System.currentTimeMillis() - currentTestStartTime
      inFlightTest.completed = true
      currentTest = null
    } else {
      // If no test was currently in-flight (e.g. the emulator crashed before any test started
      // or between tests), synthesize a testcase entry. In the JUnit XML schema, failures
      // must reside within a <testcase> element; without this, report parsers would observe
      // 0 tests and 0 failures and report the run as passed.
      // We use runName (defaults to "android-test" in AGP) and "testRunFailed" following the
      // convention used by Tradefed and JUnit runners for framework-level failures.
      val syntheticTest = DdmlibTestIdentifier(runName.ifBlank { "android-test" }, "testRunFailed")
      testResults[syntheticTest] =
        TestResult(
          testId = syntheticTest,
          status = Status.FAILURE,
          stackTrace = errorMessage,
          elapsedTimeMs = 0L,
          completed = true,
        )
    }
  }

  open fun testRunEnded(elapsedTime: Long, runMetrics: Map<String, String>?) {
    if (isRunEnded) return
    isRunEnded = true

    currentTest?.let { testId ->
      testResults[testId]?.let { result ->
        if (!result.completed) {
          result.status = Status.FAILURE
          if (result.stackTrace.isNullOrBlank()) {
            result.stackTrace = "Test did not complete before test run ended."
          }
          result.elapsedTimeMs = System.currentTimeMillis() - currentTestStartTime
          result.completed = true
        }
      }
      currentTest = null
    }

    generateDocument(reportDir, elapsedTime)
  }

  private fun generateDocument(reportDir: File, elapsedTime: Long) {
    val timestamp = getTimestamp()
    var stream: OutputStream? = null
    try {
      val resultFile = getResultFile(reportDir)
      resultFile.parentFile?.mkdirs()
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
    val totalFailures = testResults.values.count { it.status == Status.FAILURE }
    val totalSkipped = testResults.values.count { it.status == Status.IGNORED || it.status == Status.ASSUMPTION_FAILURE }
    val totalTimeSec = String.format(Locale.US, "%.3f", elapsedTime / 1000.0)

    if (testResults.isEmpty()) {
      writer.write(
        "<testsuites tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"$totalTimeSec\" timestamp=\"$timestamp\" hostname=\"$hostName\" />\n"
      )
      writer.flush()
      return
    }

    writer.write(
      "<testsuites tests=\"$totalTests\" failures=\"$totalFailures\" errors=\"0\" skipped=\"$totalSkipped\" time=\"$totalTimeSec\" timestamp=\"$timestamp\" hostname=\"$hostName\">\n"
    )

    // Group by class name maintaining insertion order
    val classResults = LinkedHashMap<String, MutableList<TestResult>>()
    for (result in testResults.values) {
      classResults.computeIfAbsent(result.testId.className) { mutableListOf() }.add(result)
    }

    for ((className, tests) in classResults) {
      val suiteTests = tests.size
      val suiteFailures = tests.count { it.status == Status.FAILURE }
      val suiteSkipped = tests.count { it.status == Status.IGNORED || it.status == Status.ASSUMPTION_FAILURE }
      val suiteTimeMs = tests.sumOf { it.elapsedTimeMs }
      val suiteTimeSec = String.format(Locale.US, "%.3f", suiteTimeMs / 1000.0)

      writer.write(
        "  <testsuite name=\"${escapeXmlAttribute(className)}\" tests=\"$suiteTests\" failures=\"$suiteFailures\" errors=\"0\" skipped=\"$suiteSkipped\" time=\"$suiteTimeSec\" timestamp=\"$timestamp\" hostname=\"$hostName\">\n"
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

        when (result.status) {
          Status.FAILURE -> {
            writer.write("    <testcase name=\"$testName\" classname=\"$testClass\" time=\"$testTimeSec\">\n")
            writer.write("      <failure>${escapeXmlText(result.stackTrace ?: "")}</failure>\n")
            writer.write("    </testcase>\n")
          }
          Status.ASSUMPTION_FAILURE,
          Status.IGNORED -> {
            writer.write("    <testcase name=\"$testName\" classname=\"$testClass\" time=\"$testTimeSec\">\n")
            if (!result.stackTrace.isNullOrBlank()) {
              writer.write("      <skipped>${escapeXmlText(result.stackTrace!!)}</skipped>\n")
            } else {
              writer.write("      <skipped />\n")
            }
            writer.write("    </testcase>\n")
          }
          Status.PASSED -> {
            writer.write("    <testcase name=\"$testName\" classname=\"$testClass\" time=\"$testTimeSec\" />\n")
          }
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
