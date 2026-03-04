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

package com.android.tools.androidtest.listener

import com.android.testutils.SystemPropertyOverrides
import com.android.tools.androidtest.listener.proto.TestResultEventProto.TestResultEvent
import com.google.common.truth.Truth.assertThat
import com.google.testing.platform.proto.api.core.TestCaseProto
import com.google.testing.platform.proto.api.core.TestResultProto
import com.google.testing.platform.proto.api.core.TestStatusProto
import com.google.testing.platform.proto.api.core.TestSuiteResultProto
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AndroidTestResultListenerTest {

  private val outputStream = ByteArrayOutputStream()
  private val originalOut = System.`out`
  private val systemPropertyOverrides = SystemPropertyOverrides()

  @Before
  fun setUp() {
    System.setOut(PrintStream(outputStream))
    systemPropertyOverrides.setProperty(AndroidTestResultListenerKeys.STREAM_BASE64_ENCODED_RESULT, "true")
  }

  @After
  fun tearDown() {
    System.setOut(originalOut)
    systemPropertyOverrides.close()
  }

  private fun decodeEvent(output: String): TestResultEvent {
    val base64 = output.substringAfter("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>").substringBefore("</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
    return TestResultEvent.parseFrom(Base64.getDecoder().decode(base64))
  }

  private fun mockTestIdentifier(isTest: Boolean = true, uniqueIdStr: String = "[engine:mock]"): TestIdentifier {
    val testIdentifier = mock<TestIdentifier>()
    whenever(testIdentifier.isTest).thenReturn(isTest)
    val uniqueId = org.junit.platform.engine.UniqueId.parse(uniqueIdStr)
    whenever(testIdentifier.uniqueId).thenReturn(uniqueId.toString())
    whenever(testIdentifier.legacyReportingName).thenReturn(uniqueId.lastSegment.value)
    return testIdentifier
  }

  @Test
  fun testPlanExecutionStarted() {
    val listener = AndroidTestResultListener()
    val testPlan = mock<TestPlan>()
    whenever(testPlan.countTestIdentifiers(any())).thenReturn(10L)

    listener.testPlanExecutionStarted(testPlan)

    assertThat(outputStream.toString()).isEmpty()
  }

  @Test
  fun testSuiteStarted_onContainerStarted() {
    val listener = AndroidTestResultListener()
    val testIdentifier = mockTestIdentifier(isTest = false, uniqueIdStr = "[engine:mock]/[device:my-device]")
    whenever(testIdentifier.isContainer).thenReturn(true)

    listener.executionStarted(testIdentifier)

    val event = decodeEvent(outputStream.toString())
    assertThat(event.hasTestSuiteStarted()).isTrue()
    assertThat(event.deviceId).isEqualTo("my-device")
  }

  @Test
  fun testSuiteFinished_onContainerFinished() {
    val listener = AndroidTestResultListener()
    val testIdentifier = mockTestIdentifier(isTest = false, uniqueIdStr = "[engine:mock]/[device:my-device]")
    whenever(testIdentifier.isContainer).thenReturn(true)

    listener.executionStarted(testIdentifier)
    outputStream.reset()
    listener.executionFinished(testIdentifier, TestExecutionResult.successful())

    val event = decodeEvent(outputStream.toString())
    assertThat(event.hasTestSuiteFinished()).isTrue()
    assertThat(event.deviceId).isEqualTo("my-device")
    val suiteResult = event.testSuiteFinished.testSuiteResult.unpack(TestSuiteResultProto.TestSuiteResult::class.java)
    assertThat(suiteResult.testStatus).isEqualTo(TestStatusProto.TestStatus.PASSED)
  }

  @Test
  fun testSuiteFinished_failure() {
    val listener = AndroidTestResultListener()
    val deviceIdentifier = mockTestIdentifier(isTest = false, uniqueIdStr = "[engine:mock]/[device:my-device]")
    whenever(deviceIdentifier.isContainer).thenReturn(true)
    val testIdentifier = mockTestIdentifier(uniqueIdStr = "[engine:mock]/[device:my-device]/[test:myTest]")

    listener.executionStarted(deviceIdentifier)
    listener.executionStarted(testIdentifier)
    listener.executionFinished(testIdentifier, TestExecutionResult.failed(RuntimeException()))
    outputStream.reset()
    listener.executionFinished(deviceIdentifier, TestExecutionResult.successful())

    val event = decodeEvent(outputStream.toString())
    assertThat(event.hasTestSuiteFinished()).isTrue()
    val suiteResult = event.testSuiteFinished.testSuiteResult.unpack(TestSuiteResultProto.TestSuiteResult::class.java)
    assertThat(suiteResult.testStatus).isEqualTo(TestStatusProto.TestStatus.FAILED)
  }

  @Test
  fun executionStarted_methodSource() {
    val listener = AndroidTestResultListener()
    val testIdentifier = mockTestIdentifier(uniqueIdStr = "[engine:mock]/[device:my-device]/[test:myTest]")
    val methodSource = MethodSource.from("com.example.MyTest", "myMethod")
    whenever(testIdentifier.source).thenReturn(Optional.of(methodSource))

    listener.executionStarted(testIdentifier)

    val event = decodeEvent(outputStream.toString())
    assertThat(event.hasTestCaseStarted()).isTrue()
    val testCase = event.testCaseStarted.testCase.unpack(TestCaseProto.TestCase::class.java)
    assertThat(testCase.testClass).isEqualTo("MyTest")
    assertThat(testCase.testMethod).isEqualTo("myMethod")
    assertThat(testCase.testPackage).isEqualTo("com.example")
    assertThat(event.deviceId).isEqualTo("my-device")
  }

  @Test
  fun executionStarted_classSource() {
    val listener = AndroidTestResultListener()
    val testIdentifier = mockTestIdentifier(uniqueIdStr = "[engine:mock]/[device:my-device]/[class:myClass]")
    val classSource = ClassSource.from("com.example.MyTest")
    whenever(testIdentifier.source).thenReturn(Optional.of(classSource))
    whenever(testIdentifier.legacyReportingName).thenReturn("myLegacyName")

    listener.executionStarted(testIdentifier)

    val event = decodeEvent(outputStream.toString())
    assertThat(event.hasTestCaseStarted()).isTrue()
    val testCase = event.testCaseStarted.testCase.unpack(TestCaseProto.TestCase::class.java)
    assertThat(testCase.testClass).isEqualTo("MyTest")
    assertThat(testCase.testMethod).isEqualTo("myLegacyName")
    assertThat(testCase.testPackage).isEqualTo("com.example")
    assertThat(event.deviceId).isEqualTo("my-device")
  }

  @Test
  fun executionFinished_success() {
    val listener = AndroidTestResultListener()
    val testIdentifier = mockTestIdentifier(uniqueIdStr = "[engine:mock]/[device:my-device]/[test:myTest]")
    val methodSource = MethodSource.from("com.example.MyTest", "myMethod")
    whenever(testIdentifier.source).thenReturn(Optional.of(methodSource))

    listener.executionFinished(testIdentifier, TestExecutionResult.successful())

    val event = decodeEvent(outputStream.toString())
    assertThat(event.hasTestCaseFinished()).isTrue()
    val testResult = event.testCaseFinished.testCaseResult.unpack(TestResultProto.TestResult::class.java)
    assertThat(testResult.testStatus).isEqualTo(TestStatusProto.TestStatus.PASSED)
    assertThat(event.deviceId).isEqualTo("my-device")
  }

  @Test
  fun executionFinished_failure() {
    val listener = AndroidTestResultListener()
    val testIdentifier = mockTestIdentifier(uniqueIdStr = "[engine:mock]/[device:my-device]/[test:myTest]")
    val methodSource = MethodSource.from("com.example.MyTest", "myMethod")
    whenever(testIdentifier.source).thenReturn(Optional.of(methodSource))

    val throwable = RuntimeException("test failure")
    listener.executionFinished(testIdentifier, TestExecutionResult.failed(throwable))

    val event = decodeEvent(outputStream.toString())
    assertThat(event.hasTestCaseFinished()).isTrue()
    val testResult = event.testCaseFinished.testCaseResult.unpack(TestResultProto.TestResult::class.java)
    assertThat(testResult.testStatus).isEqualTo(TestStatusProto.TestStatus.FAILED)
    assertThat(testResult.error.errorMessage).isEqualTo("test failure")
    assertThat(testResult.error.errorType).isEqualTo("java.lang.RuntimeException")
    assertThat(testResult.error.stackTrace).contains("AndroidTestResultListenerTest.executionFinished_failure")
    assertThat(event.deviceId).isEqualTo("my-device")
  }

  @Test
  fun testPlanExecutionFinished() {
    val listener = AndroidTestResultListener()
    val testPlan = mock<TestPlan>()

    listener.testPlanExecutionFinished(testPlan)

    assertThat(outputStream.toString()).isEmpty()
  }

  @Test
  fun testConcurrentExecution_multipleDevices() {
    val listener = AndroidTestResultListener()
    val device1 = mockTestIdentifier(isTest = false, uniqueIdStr = "[engine:mock]/[device:device-1]")
    whenever(device1.isContainer).thenReturn(true)
    val device2 = mockTestIdentifier(isTest = false, uniqueIdStr = "[engine:mock]/[device:device-2]")
    whenever(device2.isContainer).thenReturn(true)

    val latch = CountDownLatch(2)
    val thread1 = Thread {
      listener.executionStarted(device1)
      val test = mockTestIdentifier(uniqueIdStr = "[engine:mock]/[device:device-1]/[test:test1]")
      listener.executionStarted(test)
      listener.executionFinished(test, TestExecutionResult.successful())
      listener.executionFinished(device1, TestExecutionResult.successful())
      latch.countDown()
    }
    val thread2 = Thread {
      listener.executionStarted(device2)
      val test = mockTestIdentifier(uniqueIdStr = "[engine:mock]/[device:device-2]/[test:test2]")
      listener.executionStarted(test)
      listener.executionFinished(test, TestExecutionResult.failed(RuntimeException()))
      listener.executionFinished(device2, TestExecutionResult.successful())
      latch.countDown()
    }

    thread1.start()
    thread2.start()

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()

    val outputLines = outputStream.toString().trim().lines()
    // 4 events per thread (TestSuiteStarted, TestCaseStarted, TestCaseFinished, TestSuiteFinished) = 8 total
    assertThat(outputLines).hasSize(8)

    val events = outputLines.map { decodeEvent(it) }
    val device1Finished = events.find { it.hasTestSuiteFinished() && it.deviceId == "device-1" }
    val device2Finished = events.find { it.hasTestSuiteFinished() && it.deviceId == "device-2" }

    assertThat(device1Finished).isNotNull()
    assertThat(device1Finished!!.testSuiteFinished.testSuiteResult.unpack(TestSuiteResultProto.TestSuiteResult::class.java).testStatus)
      .isEqualTo(TestStatusProto.TestStatus.PASSED)

    assertThat(device2Finished).isNotNull()
    assertThat(device2Finished!!.testSuiteFinished.testSuiteResult.unpack(TestSuiteResultProto.TestSuiteResult::class.java).testStatus)
      .isEqualTo(TestStatusProto.TestStatus.FAILED)
  }

  @Test
  fun printTestResultEvent_disabled() {
    systemPropertyOverrides.setProperty(AndroidTestResultListenerKeys.STREAM_BASE64_ENCODED_RESULT, "false")
    val listener = AndroidTestResultListener()
    val testPlan = mock<TestPlan>()
    whenever(testPlan.countTestIdentifiers(any())).thenReturn(1L)

    listener.testPlanExecutionStarted(testPlan)

    assertThat(outputStream.toString()).isEmpty()
  }

  @Test
  fun printTestResultEvent_streamingFile_disabled() {
    val tempFile = File.createTempFile("streaming-results", ".txt")
    systemPropertyOverrides.setProperty(AndroidTestResultListenerKeys.STREAMING_RESULTS_FILE, tempFile.absolutePath)
    systemPropertyOverrides.setProperty(AndroidTestResultListenerKeys.STREAM_BASE64_ENCODED_RESULT, "false")

    try {
      val listener = AndroidTestResultListener()
      val testIdentifier = mockTestIdentifier(isTest = false, uniqueIdStr = "[engine:mock]/[device:my-device]")
      whenever(testIdentifier.isContainer).thenReturn(true)

      listener.executionStarted(testIdentifier)

      val fileContent = tempFile.readText()
      assertThat(outputStream.toString()).isEmpty()
      assertThat(fileContent).isEmpty()
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun printTestResultEvent_streamingFile_enabled() {
    val tempFile = File.createTempFile("streaming-results", ".txt")
    systemPropertyOverrides.setProperty(AndroidTestResultListenerKeys.STREAMING_RESULTS_FILE, tempFile.absolutePath)
    systemPropertyOverrides.setProperty(AndroidTestResultListenerKeys.STREAM_BASE64_ENCODED_RESULT, "true")

    try {
      val listener = AndroidTestResultListener()
      val testIdentifier = mockTestIdentifier(isTest = false, uniqueIdStr = "[engine:mock]/[device:my-device]")
      whenever(testIdentifier.isContainer).thenReturn(true)

      listener.executionStarted(testIdentifier)

      val fileContent = tempFile.readText()
      assertThat(outputStream.toString()).isEmpty()
      assertThat(fileContent).contains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
      val event = decodeEvent(fileContent)
      assertThat(event.hasTestSuiteStarted()).isTrue()
      assertThat(event.deviceId).isEqualTo("my-device")
    } finally {
      tempFile.delete()
    }
  }
}
