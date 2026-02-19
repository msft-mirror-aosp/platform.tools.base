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

package com.android.tools.androidtest.testengine.instrument

import com.android.tools.androidtest.testengine.instrument.AmInstrumentationParser.Companion.STATUS_CODE_ASSUMPTION_FAILURE
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationParser.Companion.STATUS_CODE_ERROR
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationParser.Companion.STATUS_CODE_FAILURE
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationParser.Companion.STATUS_CODE_IGNORED
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationParser.Companion.STATUS_CODE_OK
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFailsWith
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.matches
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

@RunWith(JUnit4::class)
class AmInstrumentationParserTest {

  private class FakeTime(private var now: Instant, private val autoAdvance: Duration) {
    operator fun invoke(): Instant {
      val current = now
      now = now.plus(autoAdvance)
      return current
    }
  }

  private lateinit var listener: AmInstrumentationListener
  private lateinit var parser: AmInstrumentationParser

  @Before
  fun setUp() {
    listener = mock()
    val now = FakeTime(Instant.ofEpochSecond(1), Duration.ofSeconds(1))
    parser = AmInstrumentationParser(setOf(listener)) { TestTimeTracker(now::invoke) }
  }

  @Test
  fun noInstrumentationInput_resultsInFailedInstrumentation() {
    parseAndDone(
      """
      |
      |a
      """
        .trimMargin()
    )
    assertThat(parser.result).isEqualTo(InstrumentationResult())
    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationFailed(any())
      verify(listener).instrumentationEnded(InstrumentationResult())
    }
    verifyNoMoreInteractions(listener)
    assertThat(parser.instrumentationError).isNotNull()
  }

  @Test
  fun onlyInstrumentationCode_resultsInSuccessfulInstrumentation() {
    val expectedResult = InstrumentationResult(-1)

    parseAndDone("INSTRUMENTATION_CODE: -1")

    assertThat(parser.result).isEqualTo(expectedResult)
    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationEnded(expectedResult)
    }
    verifyNoMoreInteractions(listener)
    assertThat(parser.instrumentationError).isNull()
  }

  @Test
  fun onlyInstrumentationResults_resultsInFailedInstrumentation() {
    val expectedResult = InstrumentationResult(bundle = mapOf("a" to "b", "c" to "d"))

    parseAndDone(
      """
      INSTRUMENTATION_RESULT: a=b
      INSTRUMENTATION_RESULT: c=d
      """
        .trimIndent()
    )

    assertThat(parser.result).isEqualTo(expectedResult)
    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationFailed(any())
      verify(listener).instrumentationEnded(expectedResult)
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun multiLineInstrumentationResult_capturesMultiLineValueWithSpaces() {
    parseAndDone(
      """
      INSTRUMENTATION_RESULT: a= multi 
        line 
         output 
      """
        .trimIndent()
    )
    assertThat(parser.result).isEqualTo(InstrumentationResult(bundle = mapOf("a" to " multi \n  line \n   output ")))
  }

  @Test
  fun multiLineStatus_capturesMultiLineValueWithSpaces() {
    commonStatus()
    startStatusCode()
    commonStatus()
    status("multi", " multi \n  line \n   output ")
    successStatusCode()

    val testResultCaptor = argumentCaptor<TestResult>()
    verify(listener).testEnded(testResultCaptor.capture())

    var actualTestResult = testResultCaptor.firstValue
    assertThat(actualTestResult.status).isEqualTo(STATUS_CODE_OK)
    assertThat(actualTestResult.statusBundle).isEqualTo(mapOf("multi" to " multi \n  line \n   output "))
  }

  @Test
  fun duplicateInstrumentationResults_lastInputWins() {
    parseAndDone(
      """
      INSTRUMENTATION_RESULT: a=a
      INSTRUMENTATION_RESULT: a=b
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )
    assertThat(parser.result).isEqualTo(InstrumentationResult(code = -1, bundle = mapOf("a" to "b")))
  }

  @Test
  fun otherInput_isIgnored() {
    parseAndDone(
      """
      INSTRUMENTATION_RESULT: a=multi
      line
      INSTRUMENTATION_STATUS: ignored=ignored
      ignored
      INSTRUMENTATION_RESULT: b=c
      INSTRUMENTATION_CODE: 0
      """
        .trimIndent()
    )
    assertThat(parser.result).isEqualTo(InstrumentationResult(code = 0, bundle = mapOf("a" to "multi\nline", "b" to "c")))
  }

  @Test
  fun instrumentationWithoutTests_resultsInSuccessfulInstrumentation() {
    val expectedResult =
      InstrumentationResult(
        code = -1,
        bundle = mapOf("a" to "", "b" to "multi\nline\noutput", "c" to "another\nmultiline", "space" to " space Value"),
      )

    parseAndDone(
      """
      INSTRUMENTATION_RESULT: a=
      INSTRUMENTATION_RESULT: b=multi
      line
      output
      INSTRUMENTATION_RESULT: c=another
      multiline
      INSTRUMENTATION_RESULT: space = space Value
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )

    assertThat(parser.result).isEqualTo(expectedResult)
    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationEnded(expectedResult)
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun endOfInstrumentation_reportsInstrumentationEndedWithoutCallingDone() {
    parse(
      """
      INSTRUMENTATION_CODE: 0
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationEnded(any())
    }

    done()

    verifyNoMoreInteractions(listener)
  }

  @Test
  fun parseCalledAfterDone_throwsIllegalStateException() {
    done()
    assertFailsWith<IllegalStateException> { parse("") }
  }

  @Test
  fun doneCalledTwice_throwsIllegalStateException() {
    done()
    assertFailsWith<IllegalStateException> { done() }
  }

  @Test
  fun invalidLines_areDropped() {
    result("a", "a")
    statusCode(0)
    parse("invalid")
    done()

    val resultCaptor = argumentCaptor<InstrumentationResult>()
    verify(listener).instrumentationEnded(resultCaptor.capture())
    assertThat(resultCaptor.firstValue.bundle["a"]).isEqualTo("a")
  }

  @Test
  fun successfulTest() {
    commonStatus()
    startStatusCode()
    commonStatus()
    successStatusCode()
    successfulInstrumentation()
    done()

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(expectedTestResult(STATUS_CODE_OK))
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun failedTest() {
    commonStatus()
    startStatusCode()
    commonStatus()
    stackTrace()
    failureStatusCode()
    successfulInstrumentationWithFailureInfo()

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(expectedTestResult(STATUS_CODE_FAILURE, STACK_TRACE))
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun assumptionFailureTest() {
    commonStatus()
    startStatusCode()
    commonStatus()
    stackTrace()
    assumptionFailureStatusCode()
    successfulInstrumentationWithFailureInfo()

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(expectedTestResult(STATUS_CODE_ASSUMPTION_FAILURE, stackTrace = STACK_TRACE))
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun ignoredTest() {
    commonStatus()
    startStatusCode()
    commonStatus()
    ignoreStatusCode()
    successfulInstrumentationWithFailureInfo()

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(expectedTestResult(STATUS_CODE_IGNORED))
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun noTests() {
    parse(
      """
      INSTRUMENTATION_RESULT: stream=
      Time: 0
      OK (0 tests)
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun incompleteTest() {
    commonStatus()
    startStatusCode()
    done()

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(expectedTestResult(STATUS_CODE_ERROR))
      verify(listener).instrumentationFailed(matches("Test run failed to complete."))
      verify(listener).instrumentationEnded(InstrumentationResult())
    }
  }

  @Test
  fun inProgressReportOfTest_reportsOnlyLastValuesSeen() {
    commonStatus()
    startStatusCode()
    status("iteration", "1")
    inProgressStatusCode()
    status("iteration", "2")
    status("multi", "multi\nline\noutput")
    commonStatus()
    successStatusCode()

    val testResultCaptor = argumentCaptor<TestResult>()
    verify(listener).testEnded(testResultCaptor.capture())

    var actualTestResult = testResultCaptor.firstValue
    assertThat(actualTestResult.status).isEqualTo(STATUS_CODE_OK)
    assertThat(actualTestResult.statusBundle).isEqualTo(mapOf("iteration" to "2", "multi" to "multi\nline\noutput"))
  }

  @Test
  fun instrumentationCrashedWhileTestInProgress() {
    commonStatus()
    startStatusCode()
    result("shortMsg", "Process crashed.")
    cancelledInstrumentation()

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(expectedTestResult(STATUS_CODE_ERROR))
      verify(listener).instrumentationFailed(matches("Process crashed"))
      verify(listener).instrumentationEnded(InstrumentationResult(0))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun instrumentationCrashedInBetweenTwoTests() {
    commonStatus()
    startStatusCode()
    commonStatus()
    failureStatusCode()
    result("shortMsg", "Process crashed.")
    cancelledInstrumentation()

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(expectedTestResult(STATUS_CODE_FAILURE))
      verify(listener).instrumentationFailed(matches("Process crashed."))
      verify(listener).instrumentationEnded(InstrumentationResult(0))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun instrumentationCrashedReportingTestEndTwice() {
    commonStatus()
    startStatusCode()
    commonStatus()
    failureStatusCode()
    commonStatus()
    failureStatusCode()
    result("shortMsg", "Process crashed.")
    cancelledInstrumentation()

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(any())
      verify(listener).instrumentationFailed(matches("Process crashed"))
      verify(listener).instrumentationEnded(InstrumentationResult(code = 0))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun testRunnerFatalError() {
    parseAndDone(
      """
      INSTRUMENTATION_RESULT: stream=Fatal exception when running tests
      java.lang.RuntimeException: Something fatal
              at com.example.Tests
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationFailed(matches("Fatal exception when running tests"))
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun unableToInstantiateInstrumentationError() {
    parseAndDone(
      """
      INSTRUMENTATION_RESULT: shortMsg=Unable to instantiate instrumentation
      INSTRUMENTATION_CODE: 0
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationFailed(matches("Unable to instantiate instrumentation"))
      verify(listener).instrumentationEnded(InstrumentationResult(0))
    }
  }

  @Test
  fun unableToFindInstrumentationInfoError() {
    parseAndDone(
      """
      onError: commandError=true message=INSTRUMENTATION_FAILED: com.example.mytestapp.test2/androidx.test.runner.AndroidJUnitRunner
      android.util.AndroidException: INSTRUMENTATION_FAILED: com.example.mytestapp.test2/androidx.test.runner.AndroidJUnitRunner
      	at com.android.commands.am.Instrument.run(Instrument.java:519)
      	at com.android.commands.am.Am.runInstrument(Am.java:202)
      	at com.android.commands.am.Am.onRun(Am.java:80)
      	at com.android.internal.os.BaseCommand.run(BaseCommand.java:60)
      	at com.android.commands.am.Am.main(Am.java:50)
      	at com.android.internal.os.RuntimeInit.nativeFinishInit(Native Method)
      	at com.android.internal.os.RuntimeInit.main(RuntimeInit.java:399)
      INSTRUMENTATION_STATUS: Error=Unable to find instrumentation info for: ComponentInfo{com.example.mytestapp.test2/androidx.test.runner.AndroidJUnitRunner}
      INSTRUMENTATION_STATUS: id=ActivityManagerService
      INSTRUMENTATION_STATUS_CODE: -1
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationFailed(matches("Unable to find instrumentation info"))
      verify(listener).instrumentationEnded(InstrumentationResult())
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun unableToFindInstrumentationInfoShortError() {
    parseAndDone(
      """
      INSTRUMENTATION_STATUS: Error=Unable to find instrumentation info
      INSTRUMENTATION_STATUS_CODE: -1
      INSTRUMENTATION_FAILED: com.fake/androidx.test.runner.AndroidJUnitRunner
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationFailed(matches("Unable to find instrumentation info"))
      verify(listener).instrumentationEnded(InstrumentationResult())
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun unableToFindInstrumentationTargetPackageError() {
    parseAndDone(
      """
      |android.util.AndroidException: INSTRUMENTATION_FAILED: foo/foo
      |INSTRUMENTATION_STATUS: id=ActivityManagerService
      |INSTRUMENTATION_STATUS: Error=Unable to find instrumentation target package: foo
      |INSTRUMENTATION_STATUS_CODE: -1at com.android.commands.am.Am.runInstrument(Am.java:532)
      |
      |        at com.android.commands.am.Am.run(Am.java:111)
      """
        .trimMargin()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationFailed(any())
      verify(listener).instrumentationEnded(InstrumentationResult())
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun amInstrumentCommandFailed() {
    parseAndDone(
      """
      usage: am [subcommand] [options]
      start an Activity: am start [-D] [-W] <INTENT>
      -D: enable debugging
      -W: wait for launch to complete
      start a Service: am startservice <INTENT
      Error: Bad component name: foo
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationFailed(matches("No test results"))
      verify(listener).instrumentationEnded(InstrumentationResult())
    }
  }

  @Test
  fun twoTests() {
    val expectedTestIdentifier = TestIdentifier(testPackage = TEST_PACKAGE, testClass = TEST_SIMPLE_CLASS, testMethod = TEST_METHOD)
    var time = Instant.ofEpochSecond(0)
    fun incAndGet(): Instant {
      time = time.plusSeconds(1)
      return time
    }

    commonStatus()
    status("numtests", "2")
    startStatusCode()
    verify(listener).instrumentationStarted(2)
    verify(listener).testStarted(expectedTestIdentifier)
    verifyNoMoreInteractions(listener)
    reset(listener)

    commonStatus()
    successStatusCode()
    verify(listener)
      .testEnded(
        TestResult(testIdentifier = expectedTestIdentifier, status = STATUS_CODE_OK, startTime = incAndGet(), endTime = incAndGet())
      )
    verifyNoMoreInteractions(listener)
    reset(listener)

    commonStatus()
    startStatusCode()
    verify(listener).testStarted(expectedTestIdentifier)
    verifyNoMoreInteractions(listener)
    reset(listener)

    commonStatus()
    failureStatusCode()
    verify(listener)
      .testEnded(
        TestResult(testIdentifier = expectedTestIdentifier, status = STATUS_CODE_FAILURE, startTime = incAndGet(), endTime = incAndGet())
      )
    verifyNoMoreInteractions(listener)
    reset(listener)

    successfulInstrumentation()

    verify(listener).instrumentationEnded(InstrumentationResult(-1))

    done()

    verifyNoMoreInteractions(listener)
  }

  /**
   * Real-world example where the test aborts without running any tests. This occurred for a test where the app and test APK had signature
   * mismatches, and orchestrator was used for running the test. The output has no test case information, but the instrumentation stream
   * contains details about the crashed instrumentation process.
   */
  @Test
  fun instrumentationCrashesBeforeAnyTestsRun() {
    parseAndDone(
      """
      INSTRUMENTATION_STATUS: stack=Test instrumentation process crashed. Check com.example.Tests#testExample.txt for details
      INSTRUMENTATION_STATUS: stream=
      Error in testExample(com.example.Tests):
      Test instrumentation process crashed. Check com.example.Tests#testExample for details
      INSTRUMENTATION_STATUS_CODE: -2
      INSTRUMENTATION_RESULT: stream=
      Time: 0.752
      There was 1 failure:
      1) testExample(com.exampleTests)
      Test instrumentation process crashed. Check com.example.Tests#testExample.txt for details
      FAILURES!!!
      Tests found: 1, Tests run: 0,  Failures: 1
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(0)
      verify(listener).instrumentationFailed(matches("Test instrumentation process crashed"))
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  /**
   * Real-world example where the instrumentation exits with `System.exit(1)` in an `@BeforeClass` annotated method. In this case the status
   * information is actually pointing to the previous test. The test never starts, and thus is not reported by the parser. At the end the
   * parser doesn't see the number of expected tests and reports this via `instrumentationFailed()`.
   */
  @Test
  fun instrumentationCrashesInBeforeClassAnnotatedMethod() {
    parseAndDone(
      """
      INSTRUMENTATION_STATUS: class=com.example.Tests
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=2
      INSTRUMENTATION_STATUS: stream=
      com.example.Tests:
      INSTRUMENTATION_STATUS: test=testExample
      INSTRUMENTATION_STATUS_CODE: 1
      INSTRUMENTATION_STATUS: class=com.example.Tests
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=2
      INSTRUMENTATION_STATUS: stream=.
      INSTRUMENTATION_STATUS: test=testExample
      INSTRUMENTATION_STATUS_CODE: 0
      INSTRUMENTATION_STATUS: class=com.example.Tests
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=2
      INSTRUMENTATION_STATUS: stack=Test instrumentation process crashed. Check com.example.BeforeClassCrashTest#emptyTest.txt for details
      INSTRUMENTATION_STATUS: stream=
      Error in null:
      Test instrumentation process crashed. Check com.example.BeforeClassCrashTest#emptyTest.txt for details
      INSTRUMENTATION_STATUS: test=testExample
      INSTRUMENTATION_STATUS_CODE: -2
      INSTRUMENTATION_RESULT: stream=
      Time: 1.64
      There was 1 failure:
      1) null
      Test instrumentation process crashed. Check com.example.BeforeClassCrashTest#emptyTest.txt for details
      FAILURES!!!
      Tests found: 2, Tests run: 1,  Failures: 1
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(2)
      verify(listener).testStarted(any())
      verify(listener).testEnded(any())
      verify(listener).instrumentationFailed(matches("Expected 2 tests, received 1"))
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  /**
   * Real-world example where the instrumentation exits with `System.exit(1)` in an `@BeforeClass` annotated method, and it is the first
   * test method. At this point we don't have enough information for calling `instrumentationStarted(testCount)`.
   */
  @Test
  fun instrumentationCrashesInFirstBeforeClassAnnotatedMethod() {
    parseAndDone(
      """
      INSTRUMENTATION_STATUS: stream=
      Error in (null):
      Test instrumentation process crashed. Check com.example.CrashInBeforeClassTest#neverExecuted.txt for details
      INSTRUMENTATION_STATUS: stack=Test instrumentation process crashed. Check com.example.CrashInBeforeClassTest#neverExecuted.txt for details
      INSTRUMENTATION_STATUS_CODE: -2
      INSTRUMENTATION_STATUS: numtests=2
      INSTRUMENTATION_STATUS: stream=
      com.example.Tests:
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: test=testExample
      INSTRUMENTATION_STATUS: class=com.example.Tests
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS_CODE: 1
      INSTRUMENTATION_STATUS: numtests=2
      INSTRUMENTATION_STATUS: stream=.
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: test=testExample
      INSTRUMENTATION_STATUS: class=com.example.Tests
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS_CODE: 0
      INSTRUMENTATION_RESULT: stream=
      Time: 0.56
      There was 1 failure:
      1) (null)
      Test instrumentation process crashed. Check com.example.CrashInBeforeClassTest#neverExecuted.txt for details
      FAILURES!!!
      Tests found: 2, Tests run: 1,  Failures: 1
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(2)
      verify(listener).testStarted(any())
      verify(listener).testEnded(any())
      verify(listener).instrumentationFailed(matches("Expected 2 tests, received 1"))
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  /**
   * Real-world example where the instrumentation throws a runtime exception in an `@BeforeClass` annotated method. The class gets not
   * reported in the normal `INFORMATION_STATUS` flow, but is reported in the end output.
   */
  @Test
  fun instrumentationThrowsExceptionInBeforeClassAnnotatedMethod() {
    parseAndDone(
      """
      INSTRUMENTATION_STATUS: class=com.example.ATests
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=3
      INSTRUMENTATION_STATUS: stream=
      com.example.ATests:
      INSTRUMENTATION_STATUS: test=first
      INSTRUMENTATION_STATUS_CODE: 1
      INSTRUMENTATION_STATUS: class=com.example.ATests
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=3
      INSTRUMENTATION_STATUS: stream=.
      INSTRUMENTATION_STATUS: test=first
      INSTRUMENTATION_STATUS_CODE: 0
      INSTRUMENTATION_STATUS: class=com.example.CTests
      INSTRUMENTATION_STATUS: current=2
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=3
      INSTRUMENTATION_STATUS: stream=
      com.example.CTests:
      INSTRUMENTATION_STATUS: test=third
      INSTRUMENTATION_STATUS_CODE: 1
      INSTRUMENTATION_STATUS: class=com.example.CTests
      INSTRUMENTATION_STATUS: current=2
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=3
      INSTRUMENTATION_STATUS: stream=.
      INSTRUMENTATION_STATUS: test=third
      INSTRUMENTATION_STATUS_CODE: 0
      INSTRUMENTATION_RESULT: stream=
      Time: 0.01
      There was 1 failure:
      1) com.example.BTests
      java.lang.RuntimeException: Something
      	at com.example.BTests.beforeClass(BTests.java:10)
      	at java.lang.reflect.Method.invoke(Native Method)
        ...
      	at android.app.Instrumentation InstrumentationThread .run(Instrumentation.java:2205)
      FAILURES!!!
      Tests run: 2,  Failures: 1
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(3)
      verify(listener).testStarted(any())
      verify(listener).testEnded(any())
      verify(listener).testStarted(any())
      verify(listener).testEnded(any())
      val messageCaptor = argumentCaptor<String>()
      verify(listener).instrumentationFailed(messageCaptor.capture())
      assertThat(messageCaptor.firstValue).contains("Expected 3 tests, received 2.")
      assertThat(messageCaptor.firstValue).contains("java.lang.RuntimeException: Something")
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun onErrorMessageIsUsedForFailureMessage() {
    val onError = "onError: commandError=false message=INSTRUMENTATION_ABORTED: System has crashed."
    val expectedFailureMessage = "Test run failed to complete. Expected 1 tests, received 0. $onError"

    parseAndDone(
      """
      INSTRUMENTATION_STATUS: class=com.example.Tests
      $onError
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_ABORTED: System has crashed.
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=1
      INSTRUMENTATION_STATUS: stream=
      com.example.Tests:
      INSTRUMENTATION_STATUS: test=testExample
      INSTRUMENTATION_STATUS_CODE: 1
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(any())
      verify(listener).instrumentationFailed(matches(expectedFailureMessage))
      verify(listener).instrumentationEnded(InstrumentationResult())
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun instrumentationAbortedMessageIsUsedForFailureMessage() {
    val expectedFailureMessage = "Test run failed to complete. Expected 1 tests, received 0. System has crashed."

    parseAndDone(
      """
      INSTRUMENTATION_STATUS: class=com.example.Tests
      INSTRUMENTATION_ABORTED: System has crashed.
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=1
      INSTRUMENTATION_STATUS: stream=
      com.example.Tests:
      INSTRUMENTATION_STATUS: test=testExample
      INSTRUMENTATION_STATUS_CODE: 1
      """
        .trimIndent()
    )

    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(TEST_IDENTIFIER)
      verify(listener).testEnded(any())
      verify(listener).instrumentationFailed(matches(expectedFailureMessage))
      verify(listener).instrumentationEnded(InstrumentationResult())
    }
    verifyNoMoreInteractions(listener)
  }

  @Test
  fun orderOfStatusEntriesIsPreserved() {
    commonStatus()
    startStatusCode()
    commonStatus()
    status("z", "z")
    status("a", "a")
    status("y", "y")
    successStatusCode()
    successfulInstrumentation()

    val captor = argumentCaptor<TestResult>()
    verify(listener).testEnded(captor.capture())
    assertThat(captor.firstValue.statusBundle.keys).containsExactly("z", "a", "y").inOrder()
  }

  @Test
  fun cucumberStyledNamesAreAccepted() {
    parseAndDone(
      """
      INSTRUMENTATION_STATUS: numtests=1
      INSTRUMENTATION_STATUS: stream=
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: test=a custom test name
      INSTRUMENTATION_STATUS: class=Feature Something
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS_CODE: 1
      INSTRUMENTATION_STATUS: numtests=1
      INSTRUMENTATION_STATUS: stream=.
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: test=a custom test name
      INSTRUMENTATION_STATUS: class=Feature Something
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS_CODE: 0
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )

    val testIdentifier = TestIdentifier(testPackage = "", testClass = "Feature Something", testMethod = "a custom test name")
    inOrder(listener) {
      verify(listener).instrumentationStarted(1)
      verify(listener).testStarted(testIdentifier)
      verify(listener)
        .testEnded(
          TestResult(
            testIdentifier = testIdentifier,
            status = STATUS_CODE_OK,
            startTime = FIRST_TEST_START_TIME,
            endTime = FIRST_TEST_END_TIME,
          )
        )
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  /**
   * Real-world example where the test code is using `Instrumentation.sendStatus()` to write custom instrumentation output, but using a
   * wrong status code (-1 = error) and not the in-progress status code (1).
   *
   * This has been observed with the AndroidX benchmark library v1.0.0.
   */
  @Test
  fun customStatusWithErrorStatus() {
    parseAndDone(
      """
      INSTRUMENTATION_STATUS: numtests=2
      INSTRUMENTATION_STATUS: stream=
      com.example.BenchmarkTest:
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: test=bench1
      INSTRUMENTATION_STATUS: class=com.example.BenchmarkTest
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS_CODE: 1
      INSTRUMENTATION_STATUS: min=1
      INSTRUMENTATION_STATUS: count=2
      INSTRUMENTATION_STATUS: class=com.example.BenchmarkTest
      INSTRUMENTATION_STATUS_CODE: -1
      INSTRUMENTATION_STATUS: numtests=2
      INSTRUMENTATION_STATUS: stream=.
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: test=bench1
      INSTRUMENTATION_STATUS: class=com.example.BenchmarkTest
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS_CODE: 0
      INSTRUMENTATION_STATUS: numtests=2
      INSTRUMENTATION_STATUS: stream=
      com.example.BenchmarkTest:
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: test=bench2
      INSTRUMENTATION_STATUS: class=com.example.BenchmarkTest
      INSTRUMENTATION_STATUS: current=2
      INSTRUMENTATION_STATUS_CODE: 1
      INSTRUMENTATION_STATUS: min=1
      INSTRUMENTATION_STATUS: count=2
      INSTRUMENTATION_STATUS: test=bench2
      INSTRUMENTATION_STATUS_CODE: -1
      INSTRUMENTATION_STATUS: numtests=2
      INSTRUMENTATION_STATUS: stream=.
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: test=bench2
      INSTRUMENTATION_STATUS: class=com.example.BenchmarkTest
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS_CODE: 0
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()
    )

    val testId1 = TestIdentifier(testPackage = "com.example", testClass = "BenchmarkTest", testMethod = "bench1")
    val testId2 = TestIdentifier(testPackage = "com.example", testClass = "BenchmarkTest", testMethod = "bench2")
    val bundle = mapOf("min" to "1", "count" to "2")
    inOrder(listener) {
      verify(listener).instrumentationStarted(2)
      verify(listener).testStarted(testId1)
      verify(listener)
        .testEnded(
          TestResult(
            testIdentifier = testId1,
            status = STATUS_CODE_OK,
            startTime = FIRST_TEST_START_TIME,
            endTime = FIRST_TEST_END_TIME,
            statusBundle = bundle,
          )
        )
      verify(listener).testStarted(testId2)
      verify(listener)
        .testEnded(
          TestResult(
            testIdentifier = testId2,
            status = STATUS_CODE_OK,
            startTime = SECOND_TEST_START_TIME,
            endTime = SECOND_TEST_END_TIME,
            statusBundle = bundle,
          )
        )
      verify(listener).instrumentationEnded(InstrumentationResult(-1))
    }
    verifyNoMoreInteractions(listener)
  }

  private fun commonStatus() {
    status("class", TEST_CLASS)
    status("current", "1")
    status("id", "AndroidJUnitRunner")
    status("numtests", "1")
    status("stream", "\n$TEST_CLASS:")
    status("test", TEST_METHOD)
  }

  private fun stackTrace() {
    status("stack", STACK_TRACE)
  }

  private fun startStatusCode() {
    statusCode(1)
  }

  private fun inProgressStatusCode() {
    statusCode(2)
  }

  private fun successStatusCode() {
    statusCode(0)
  }

  private fun failureStatusCode() {
    statusCode(-2)
  }

  private fun ignoreStatusCode() {
    statusCode(-3)
  }

  private fun assumptionFailureStatusCode() {
    statusCode(-4)
  }

  private fun status(key: String, value: String) {
    parse("INSTRUMENTATION_STATUS: $key=$value")
  }

  private fun statusCode(code: Int) {
    parse("INSTRUMENTATION_STATUS_CODE: $code")
  }

  private fun successfulInstrumentation() {
    result("stream", COMMON_INSTRUMENTATION_STREAM)
    parse("INSTRUMENTATION_CODE: -1")
  }

  private fun successfulInstrumentationWithFailureInfo() {
    result("stream", COMMON_FAILED_INSTRUMENTATION_STREAM)
    parse("INSTRUMENTATION_CODE: -1")
  }

  private fun cancelledInstrumentation() {
    result("stream", COMMON_INSTRUMENTATION_STREAM)
    parse("INSTRUMENTATION_CODE: 0")
  }

  private fun result(key: String, value: String) {
    parse("INSTRUMENTATION_RESULT: $key=$value")
  }

  private fun parse(lines: String) = lines.split("\n").forEach(parser::parse)

  private fun done() = parser.done()

  private fun parseAndDone(lines: String) {
    parse(lines)
    done()
  }

  private companion object {
    const val TEST_PACKAGE = "com.example"
    const val TEST_SIMPLE_CLASS = "Tests"
    const val TEST_CLASS = "com.example.Tests"
    const val TEST_METHOD = "testExample"
    const val STACK_TRACE = "java.lang.AssertionError\n    at com.example.Tests:123"
    val TEST_IDENTIFIER = TestIdentifier("com.example", "Tests", "testExample")
    val FIRST_TEST_START_TIME: Instant = Instant.ofEpochSecond(1)
    val FIRST_TEST_END_TIME: Instant = Instant.ofEpochSecond(2)
    val SECOND_TEST_START_TIME: Instant = Instant.ofEpochSecond(3)
    val SECOND_TEST_END_TIME: Instant = Instant.ofEpochSecond(4)
    val COMMON_INSTRUMENTATION_STREAM =
      """
      |
      |
      |Time: 0.001
      |
      |OK (1 test)
      |
      |"""
        .trimMargin()
    val COMMON_FAILED_INSTRUMENTATION_STREAM =
      """
      |
      |
      |Time: 0.001
      |There was 1 failure:
      |
      |1) com.example.Tests:
      |java.lang.RuntimeException: Something happened
      |      at com.example.Tests
      |
      |FAILURES!!!
      |Tests run: 1, Failures: 1
      |
      |"""
        .trimMargin()

    fun expectedTestResult(status: Int, stackTrace: String? = null, statusBundle: Map<String, String> = mapOf()): TestResult {
      return TestResult(
        testIdentifier = TEST_IDENTIFIER,
        status = status,
        startTime = FIRST_TEST_START_TIME,
        endTime = FIRST_TEST_END_TIME,
        stackTrace = stackTrace,
        statusBundle = statusBundle,
      )
    }
  }
}
