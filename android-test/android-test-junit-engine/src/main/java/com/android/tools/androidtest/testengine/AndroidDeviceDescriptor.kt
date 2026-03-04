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

import com.android.ddmlib.testrunner.TestIdentifier as DdmlibTestIdentifier
import com.android.ddmlib.testrunner.XmlTestRunListener
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationListener
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationParser
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationRunner
import com.android.tools.androidtest.testengine.instrument.InstrumentationResult
import com.android.tools.androidtest.testengine.instrument.TestIdentifier
import com.android.tools.androidtest.testengine.instrument.TestResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.hierarchical.Node

/**
 * A test descriptor representing an Android device.
 *
 * This descriptor manages the test execution lifecycle on a specific device identified by its [deviceSerial]. It handles APK installation
 * and launches the instrumentation process.
 *
 * @property deviceSerial The serial number of the target Android device.
 */
class AndroidDeviceDescriptor(uniqueId: UniqueId, val deviceSerial: String) :
  AbstractTestDescriptor(uniqueId, deviceSerial), Node<AndroidTestExecutionContext> {

  private sealed class TestEvent {
    data class NewTest(val descriptor: AndroidDynamicTestDescriptor) : TestEvent()
  }

  private val tests = Channel<TestEvent>(Channel.UNLIMITED)

  override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER

  override fun mayRegisterTests(): Boolean = true

  override fun execute(context: AndroidTestExecutionContext, dynamicTestExecutor: Node.DynamicTestExecutor): AndroidTestExecutionContext {
    val config = context.configuration

    val adbApkInstaller = AdbApkInstaller(config.adb, config.aapt2, deviceSerial, config.installTimeoutMs)

    val resultsDir = config.resultsDir?.let { File(it, deviceSerial).also { it.mkdirs() } }
    val reporter = resultsDir?.let { SimpleXmlResultReporter(it, deviceSerial) }
    val listener = Listener(context, reporter)

    val instrumentationRunner =
      AmInstrumentationRunner(
        config.adb,
        deviceSerial,
        config.instrumentationRunnerClass,
        config.instrumentationTargetPackageId,
        setOf(listener),
      )

    val runner =
      AndroidTestRunner(
        adbApkInstaller,
        instrumentationRunner,
        config.testedApks,
        config.testApks,
        config.apkInstallOptions,
        config.testUtilApks,
        config.uninstallApksAfterTests,
      )

    // We run the instrumentation runner in a separate thread so that it can discover and send
    // test cases to the 'tests' channel while the main thread (the current one) consumes and
    // executes them concurrently. This is essential for JUnit dynamic test execution to start
    // as soon as tests are discovered on the device.
    val runnerThread =
      Thread(
        {
          try {
            runner.run()
          } finally {
            listener.finish()
            // Close the channel once the instrumentation process finishes to signal that no
            // more tests will be discovered.
            tests.close()
          }
        },
        "AndroidTestRunner-$deviceSerial",
      )
    runnerThread.start()

    // Consume and process test discovery events from the instrumentation runner.
    runBlocking {
      for (testEvent in tests) {
        when (testEvent) {
          // As new tests are discovered on the device, we dynamically register and execute
          // them within the JUnit engine.
          is TestEvent.NewTest -> dynamicTestExecutor.execute(testEvent.descriptor)
        }
      }
    }

    return context
  }

  /**
   * Bridges Android instrumentation events to JUnit dynamic tests and optional XML reporting.
   *
   * This listener implementation translates low-level instrumentation events (like `testStarted` and `testEnded`) into high-level JUnit
   * [TestDescriptor] operations and optionally reports them to a [SimpleXmlResultReporter].
   */
  inner class Listener(private val context: AndroidTestExecutionContext, private val reporter: SimpleXmlResultReporter?) :
    AmInstrumentationListener {

    private val testDescriptors = ConcurrentHashMap<TestIdentifier, AndroidDynamicTestDescriptor>()

    override fun instrumentationStarted(testCount: Int) {
      reporter?.testRunStarted("android-test", testCount)

      // We publish a ReportEntry with the testCount so that it can be picked up by listeners.
      val reportEntry = ReportEntry.from(AndroidTestReportKeys.TEST_COUNT, testCount.toString())
      context.request.engineExecutionListener.reportingEntryPublished(this@AndroidDeviceDescriptor, reportEntry)
    }

    /** Called when a test case starts on the device. */
    override fun testStarted(testIdentifier: TestIdentifier) {
      val packageName = testIdentifier.testPackage
      val fullClassName = if (packageName.isNotEmpty()) "$packageName.${testIdentifier.testClass}" else testIdentifier.testClass

      val uniqueId = this@AndroidDeviceDescriptor.uniqueId.append("test", "$fullClassName.${testIdentifier.testMethod}")
      val displayName = "$fullClassName.${testIdentifier.testMethod}"
      val testDescriptor = AndroidDynamicTestDescriptor(uniqueId, displayName, fullClassName, testIdentifier.testMethod)

      testDescriptor.setParent(this@AndroidDeviceDescriptor)
      testDescriptors[testIdentifier] = testDescriptor
      tests.trySend(TestEvent.NewTest(testDescriptor)).getOrThrow()

      reporter?.testStarted(DdmlibTestIdentifier(fullClassName, testIdentifier.testMethod))
    }

    /** Completes the [AndroidDynamicTestDescriptor.resultDeferred] for the corresponding test. */
    override fun testEnded(testResult: TestResult) {
      val testIdentifier = testResult.testIdentifier
      val packageName = testIdentifier.testPackage
      val fullClassName = if (packageName.isNotEmpty()) "$packageName.${testIdentifier.testClass}" else testIdentifier.testClass
      val ddmlibTestId = DdmlibTestIdentifier(fullClassName, testIdentifier.testMethod)

      if (testResult.status != AmInstrumentationParser.STATUS_CODE_OK) {
        reporter?.testFailed(ddmlibTestId, testResult.stackTrace ?: "")
      }
      reporter?.testEnded(ddmlibTestId, emptyMap())

      testDescriptors[testIdentifier]?.resultDeferred?.complete(testResult)
    }

    /** Handles instrumentation failures by completing all pending test results exceptionally. */
    override fun instrumentationFailed(errorMessage: String) {
      reporter?.testRunFailed(errorMessage)
      val exception = RuntimeException(errorMessage)
      testDescriptors.values.forEach {
        if (!it.resultDeferred.isCompleted) {
          it.resultDeferred.completeExceptionally(exception)
        }
      }
    }

    /** Handles unexpected instrumentation termination by completing all pending test results exceptionally. */
    override fun instrumentationEnded(instrumentationResult: InstrumentationResult) {
      reporter?.testRunEnded(0, emptyMap())
      // In case of unexpected termination, ensure all pending results are completed exceptionally.
      val exception = RuntimeException("Instrumentation ended unexpectedly")
      testDescriptors.values.forEach {
        if (!it.resultDeferred.isCompleted) {
          it.resultDeferred.completeExceptionally(exception)
        }
      }
    }

    /** Ensures all pending results are completed exceptionally and signals completion. */
    fun finish() {
      // In case of unexpected termination, ensure all pending results are completed exceptionally.
      val exception = RuntimeException("Instrumentation ended unexpectedly")
      testDescriptors.values.forEach {
        if (!it.resultDeferred.isCompleted) {
          it.resultDeferred.completeExceptionally(exception)
        }
      }
    }
  }

  /** A simple XML result reporter that uses ddmlib's [XmlTestRunListener] to generate JUnit XML files. */
  inner class SimpleXmlResultReporter(private val reportDir: File, private val deviceName: String) : XmlTestRunListener() {
    init {
      setReportDir(reportDir)
    }

    override fun getResultFile(reportDir: File): File {
      return File(reportDir, "TEST-$deviceName.xml")
    }

    override fun getPropertiesAttributes(): Map<String, String> {
      return mapOf("device" to deviceName)
    }

    override fun testRunEnded(elapsedTime: Long, runMetrics: Map<String, String>?) {
      super.testRunEnded(elapsedTime, runMetrics)
    }
  }
}
