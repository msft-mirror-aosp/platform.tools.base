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

package com.android.tools.androidtest.testengine.descriptor

import com.android.tools.androidtest.testengine.AndroidTestExecutionContext
import com.android.tools.androidtest.testengine.adb.AdbApkInstaller
import com.android.tools.androidtest.testengine.adb.EmulatorGrpcInfo
import com.android.tools.androidtest.testengine.collector.AndroidAdditionalTestOutputCollector
import com.android.tools.androidtest.testengine.collector.BenchmarkOutput
import com.android.tools.androidtest.testengine.config.AndroidTestConfigurationKeys
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationParser
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationRunner
import com.android.tools.androidtest.testengine.instrument.TestIdentifier
import com.android.tools.androidtest.testengine.instrument.TestResult
import com.android.tools.androidtest.testengine.report.AndroidTestReportKeys
import com.android.tools.androidtest.testengine.report.DdmlibTestIdentifier
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.platform.engine.ConfigurationParameters
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.hierarchical.Node
import org.junit.rules.TemporaryFolder
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit tests for the [AndroidDeviceDescriptor] class. */
class AndroidDeviceDescriptorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

  @Mock private lateinit var executionRequest: ExecutionRequest
  @Mock private lateinit var engineExecutionListener: EngineExecutionListener
  @Mock private lateinit var configurationParameters: ConfigurationParameters
  @Mock private lateinit var additionalTestOutputCollector: AndroidAdditionalTestOutputCollector

  private val deviceSerial = "device-1234"
  private lateinit var adbFile: File
  private lateinit var aapt2File: File
  private lateinit var resultsDir: File

  @Before
  fun setUp() {
    adbFile = tempFolder.newFile("adb")
    aapt2File = tempFolder.newFile("aapt2")
    resultsDir = tempFolder.newFolder("results")

    whenever(executionRequest.engineExecutionListener).thenReturn(engineExecutionListener)
    whenever(executionRequest.configurationParameters).thenReturn(configurationParameters)

    whenever(configurationParameters.get(AndroidTestConfigurationKeys.ADB_PATH)).thenReturn(java.util.Optional.of(adbFile.absolutePath))
    whenever(configurationParameters.get(AndroidTestConfigurationKeys.AAPT2_PATH)).thenReturn(java.util.Optional.of(aapt2File.absolutePath))
    whenever(configurationParameters.get(AndroidTestConfigurationKeys.DEVICE_SERIALS)).thenReturn(java.util.Optional.of(deviceSerial))
    whenever(configurationParameters.get(AndroidTestConfigurationKeys.RESULTS_DIR))
      .thenReturn(java.util.Optional.of(resultsDir.absolutePath))
    whenever(configurationParameters.get(AndroidTestConfigurationKeys.INSTRUMENTATION_RUNNER_CLASS))
      .thenReturn(java.util.Optional.of("android.support.test.runner.AndroidJUnitRunner"))
    whenever(configurationParameters.get(AndroidTestConfigurationKeys.TEST_PACKAGE_ID))
      .thenReturn(java.util.Optional.of("com.example.app.test"))
    whenever(configurationParameters.get(AndroidTestConfigurationKeys.INSTRUMENTATION_TARGET_PACKAGE_ID))
      .thenReturn(java.util.Optional.of("com.example.app"))
  }

  @Test
  fun `instrumentationStarted reports device info path when executing`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val context = AndroidTestExecutionContext(executionRequest)
    val deviceInfoFile = File(resultsDir, "device-info.pb")

    val listener = descriptor.Listener(deviceInfoFile = deviceInfoFile)

    listener.instrumentationStarted(1)
    listener.finish()

    val dynamicTestExecutor = mock<Node.DynamicTestExecutor>()
    descriptor.execute(context, dynamicTestExecutor)

    val reportEntryCaptor = argumentCaptor<ReportEntry>()
    verify(engineExecutionListener, atLeastOnce()).reportingEntryPublished(eq(descriptor), reportEntryCaptor.capture())

    val deviceInfoEntry = reportEntryCaptor.allValues.find { it.keyValuePairs.containsKey(AndroidTestReportKeys.DEVICE_INFO_PATH) }
    assertThat(deviceInfoEntry).isNotNull()
    assertThat(deviceInfoEntry?.keyValuePairs?.get(AndroidTestReportKeys.DEVICE_INFO_PATH)).isEqualTo(deviceInfoFile.absolutePath)
  }

  @Test
  fun `instrumentationStarted queues report entries without calling reportingEntryPublished immediately`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val context = AndroidTestExecutionContext(executionRequest)

    val listener = descriptor.Listener()

    listener.instrumentationStarted(1)

    verify(engineExecutionListener, never()).reportingEntryPublished(any(), any())
  }

  @Test
  fun `instrumentationFailed publishes run failure report entry`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val context = AndroidTestExecutionContext(executionRequest)

    val listener = descriptor.Listener()

    listener.instrumentationStarted(0)
    listener.instrumentationFailed("Test run failed to complete. Process crashed.")
    listener.finish()

    descriptor.execute(context, mock<Node.DynamicTestExecutor>())

    val reportEntryCaptor = argumentCaptor<ReportEntry>()
    verify(engineExecutionListener, atLeastOnce()).reportingEntryPublished(eq(descriptor), reportEntryCaptor.capture())
    val runFailures = reportEntryCaptor.allValues.mapNotNull { it.keyValuePairs[AndroidTestReportKeys.RUN_FAILURE] }
    assertThat(runFailures).containsExactly("Test run failed to complete. Process crashed.")
  }

  @Test
  fun `reportRunFailure after finish is ignored`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val context = AndroidTestExecutionContext(executionRequest)

    val listener = descriptor.Listener()

    listener.finish()
    listener.reportRunFailure("too late")

    descriptor.execute(context, mock<Node.DynamicTestExecutor>())

    verify(engineExecutionListener, never()).reportingEntryPublished(any(), any())
  }

  @Test
  fun `startRunner publishes run failure report entry when the test runner fails`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    whenever(mockAdbApkInstaller.preInstallationSetup(any())).thenThrow(RuntimeException("Failed to install APKs"))

    val descriptor =
      AndroidDeviceDescriptor(
        uniqueId,
        deviceSerial,
        adbApkInstallerFactory = { _, _, _, _, _ -> mockAdbApkInstaller },
        instrumentationRunnerFactory = { _, _, _, _, _, _, _, _, _, _ -> mockAmInstrumentationRunner },
      )

    val context = AndroidTestExecutionContext(executionRequest)
    descriptor.startRunner(context)
    // Consumes the events sent by the runner thread until it finishes.
    descriptor.execute(context, mock<Node.DynamicTestExecutor>())

    val reportEntryCaptor = argumentCaptor<ReportEntry>()
    verify(engineExecutionListener, atLeastOnce()).reportingEntryPublished(eq(descriptor), reportEntryCaptor.capture())
    val runFailures = reportEntryCaptor.allValues.mapNotNull { it.keyValuePairs[AndroidTestReportKeys.RUN_FAILURE] }
    assertThat(runFailures).isNotEmpty()
  }

  @Test
  fun `testStarted queues test without calling dynamicTestRegistered immediately`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val context = AndroidTestExecutionContext(executionRequest)

    val testIdentifier = TestIdentifier("pkg", "Cls", "meth")
    val listener = descriptor.Listener()

    listener.testStarted(testIdentifier)

    verify(engineExecutionListener, never()).dynamicTestRegistered(any())
  }

  @Test
  fun `execute registers dynamic tests from queue`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val context = AndroidTestExecutionContext(executionRequest)

    val testIdentifier = TestIdentifier("pkg", "Cls", "meth")
    val listener = descriptor.Listener()

    listener.testStarted(testIdentifier)
    listener.finish()

    val dynamicTestExecutor = mock<Node.DynamicTestExecutor>()
    descriptor.execute(context, dynamicTestExecutor)

    verify(engineExecutionListener).dynamicTestRegistered(any())
    verify(dynamicTestExecutor).execute(any())
  }

  @Mock private lateinit var mockDynamicTestExecutor: Node.DynamicTestExecutor
  @Mock private lateinit var mockAdbApkInstaller: AdbApkInstaller
  @Mock private lateinit var mockAmInstrumentationRunner: AmInstrumentationRunner

  @Test
  fun startRunner_withEmulatorControlEnabled_injectsGrpcPortAndToken() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)

    whenever(configurationParameters.get("android-test.emulator-control-enabled[$deviceSerial]")).thenReturn(java.util.Optional.of("true"))

    val testPort = 1234
    val testToken = "fake-token"

    val descriptor =
      AndroidDeviceDescriptor(
        uniqueId,
        deviceSerial,
        findGrpcInfoProvider = { serial ->
          assertThat(serial).isEqualTo(deviceSerial)
          EmulatorGrpcInfo(testPort, testToken)
        },
        adbApkInstallerFactory = { _, _, _, _, _ -> mockAdbApkInstaller },
        instrumentationRunnerFactory = { _, serial, _, _, _, _, args, _, _, _ ->
          assertThat(serial).isEqualTo(deviceSerial)
          assertThat(args["grpc.port"]).isEqualTo(testPort.toString())
          assertThat(args["grpc.token"]).isEqualTo(testToken)
          mockAmInstrumentationRunner
        },
      )

    val context = AndroidTestExecutionContext(executionRequest)
    descriptor.startRunner(context)
  }

  @Test
  fun startRunner_withEmulatorControlDisabled_doesNotInjectGrpcPortAndToken() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)

    whenever(configurationParameters.get("android-test.emulator-control-enabled[$deviceSerial]")).thenReturn(java.util.Optional.of("false"))

    val descriptor =
      AndroidDeviceDescriptor(
        uniqueId,
        deviceSerial,
        findGrpcInfoProvider = { throw RuntimeException("findGrpcInfoProvider should not be called when emulator control is disabled") },
        adbApkInstallerFactory = { _, _, _, _, _ -> mockAdbApkInstaller },
        instrumentationRunnerFactory = { _, _, _, _, _, _, args, _, _, _ ->
          assertThat(args.containsKey("grpc.port")).isFalse()
          assertThat(args.containsKey("grpc.token")).isFalse()
          mockAmInstrumentationRunner
        },
      )

    val context = AndroidTestExecutionContext(executionRequest)
    descriptor.startRunner(context)
  }

  @Test
  fun startRunner_withShardingConfigured_injectsNumShardsAndShardIndex() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)

    whenever(configurationParameters.get("android-test.num-shards[$deviceSerial]")).thenReturn(java.util.Optional.of("10"))
    whenever(configurationParameters.get("android-test.shard-index[$deviceSerial]")).thenReturn(java.util.Optional.of("3"))

    val descriptor =
      AndroidDeviceDescriptor(
        uniqueId,
        deviceSerial,
        adbApkInstallerFactory = { _, _, _, _, _ -> mockAdbApkInstaller },
        instrumentationRunnerFactory = { _, serial, _, _, _, _, args, _, _, _ ->
          assertThat(serial).isEqualTo(deviceSerial)
          assertThat(args["numShards"]).isEqualTo("10")
          assertThat(args["shardIndex"]).isEqualTo("3")
          mockAmInstrumentationRunner
        },
      )

    val context = AndroidTestExecutionContext(executionRequest)
    descriptor.startRunner(context)
  }

  @Test
  fun startRunner_withoutShardingConfigured_doesNotInjectNumShardsAndShardIndex() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)

    val descriptor =
      AndroidDeviceDescriptor(
        uniqueId,
        deviceSerial,
        adbApkInstallerFactory = { _, _, _, _, _ -> mockAdbApkInstaller },
        instrumentationRunnerFactory = { _, _, _, _, _, _, args, _, _, _ ->
          assertThat(args.containsKey("numShards")).isFalse()
          assertThat(args.containsKey("shardIndex")).isFalse()
          mockAmInstrumentationRunner
        },
      )

    val context = AndroidTestExecutionContext(executionRequest)
    descriptor.startRunner(context)
  }

  @Test
  fun `testEnded buffers report entries which are published during test execution`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val context = AndroidTestExecutionContext(executionRequest)

    val testIdentifier = TestIdentifier("pkg", "Cls", "meth")
    val mockTestResult = mock<TestResult>()
    whenever(mockTestResult.testIdentifier).thenReturn(testIdentifier)
    whenever(mockTestResult.status).thenReturn(0) // OK

    val traceFiles = listOf(File("/path/to/trace1.pb"), File("/path/to/trace2.pb"))
    val messageFile = File("/path/to/message.txt")
    whenever(additionalTestOutputCollector.addBenchmarkOutput(mockTestResult)).thenReturn(BenchmarkOutput(traceFiles, messageFile))

    val listener = descriptor.Listener(additionalTestOutputCollector = additionalTestOutputCollector)

    listener.testStarted(testIdentifier)
    listener.testEnded(mockTestResult)
    listener.finish()

    val dynamicTestExecutor = mock<Node.DynamicTestExecutor>()
    descriptor.execute(context, dynamicTestExecutor)

    val captor = argumentCaptor<AndroidDynamicTestDescriptor>()
    verify(dynamicTestExecutor).execute(captor.capture())
    captor.firstValue.execute(context, dynamicTestExecutor)

    val reportEntryCaptor = argumentCaptor<ReportEntry>()
    verify(engineExecutionListener, atLeastOnce()).reportingEntryPublished(any(), reportEntryCaptor.capture())

    val benchmarkEntry = reportEntryCaptor.allValues.find { it.keyValuePairs.containsKey(AndroidTestReportKeys.BENCHMARK_TRACE_PATHS) }
    assertThat(benchmarkEntry).isNotNull()
    val expectedTracePaths = traceFiles.joinToString(",") { it.absolutePath }
    assertThat(benchmarkEntry?.keyValuePairs?.get(AndroidTestReportKeys.BENCHMARK_TRACE_PATHS)).isEqualTo(expectedTracePaths)

    val messageEntry = reportEntryCaptor.allValues.find { it.keyValuePairs.containsKey(AndroidTestReportKeys.BENCHMARK_MESSAGE_PATH) }
    assertThat(messageEntry).isNotNull()
    assertThat(messageEntry?.keyValuePairs?.get(AndroidTestReportKeys.BENCHMARK_MESSAGE_PATH)).isEqualTo(messageFile.absolutePath)
  }

  @Test
  fun `testEnded with ignored status calls reporter testIgnored`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val mockReporter = mock<AndroidDeviceDescriptor.SimpleXmlResultReporter>()

    val testIdentifier = TestIdentifier("pkg", "Cls", "meth")
    val mockTestResult = mock<TestResult>()
    whenever(mockTestResult.testIdentifier).thenReturn(testIdentifier)
    whenever(mockTestResult.status).thenReturn(AmInstrumentationParser.STATUS_CODE_IGNORED)

    val listener = descriptor.Listener(reporter = mockReporter)
    listener.testStarted(testIdentifier)
    listener.testEnded(mockTestResult)

    val ddmlibTestId = DdmlibTestIdentifier("pkg.Cls", "meth")
    verify(mockReporter).testIgnored(eq(ddmlibTestId))
    verify(mockReporter, never()).testFailed(any(), any())
    verify(mockReporter).testEnded(eq(ddmlibTestId), any())
  }

  @Test
  fun `testEnded with assumption failure calls reporter testAssumptionFailure`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val mockReporter = mock<AndroidDeviceDescriptor.SimpleXmlResultReporter>()

    val testIdentifier = TestIdentifier("pkg", "Cls", "meth")
    val mockTestResult = mock<TestResult>()
    whenever(mockTestResult.testIdentifier).thenReturn(testIdentifier)
    whenever(mockTestResult.status).thenReturn(AmInstrumentationParser.STATUS_CODE_ASSUMPTION_FAILURE)
    whenever(mockTestResult.stackTrace).thenReturn("Assumption violated")

    val listener = descriptor.Listener(reporter = mockReporter)
    listener.testStarted(testIdentifier)
    listener.testEnded(mockTestResult)

    val ddmlibTestId = DdmlibTestIdentifier("pkg.Cls", "meth")
    verify(mockReporter).testAssumptionFailure(eq(ddmlibTestId), eq("Assumption violated"))
    verify(mockReporter, never()).testFailed(any(), any())
    verify(mockReporter).testEnded(eq(ddmlibTestId), any())
  }

  /**
   * Regression test for a flaky failure in AndroidManagedDeviceTest.additionalTestOutputWithBenchmarkV3Files where a test that passed on
   * the device (and was reported as passed in the XML/HTML report) was reported to JUnit as failed with "Instrumentation ended
   * unexpectedly".
   *
   * This mirrors the threading model of [AmInstrumentationRunner.runAmInstrumentCommand]: the instrumentation output is parsed on a
   * dedicated thread, and [AmInstrumentationParser.done] is called on the runner thread once the parsing thread terminates. If
   * post-processing in [AndroidDeviceDescriptor.Listener.testEnded] (e.g. pulling benchmark files via adb) throws, the parsing thread dies
   * before the test result is completed, and the subsequent `instrumentationEnded` callback marks the passed test as failed.
   */
  @Test
  fun `test result is not lost when benchmark output collection throws`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val context = AndroidTestExecutionContext(executionRequest)
    val mockReporter = mock<AndroidDeviceDescriptor.SimpleXmlResultReporter>()

    whenever(additionalTestOutputCollector.addBenchmarkOutput(any())).thenThrow(RuntimeException("simulated adb failure"))

    val listener = descriptor.Listener(reporter = mockReporter, additionalTestOutputCollector = additionalTestOutputCollector)
    val parser = AmInstrumentationParser(listeners = setOf(listener))

    val instrumentationOutput =
      """
      INSTRUMENTATION_STATUS: class=com.example.helloworld.AdditionalTestOutputExampleTest
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=1
      INSTRUMENTATION_STATUS: stream=
      INSTRUMENTATION_STATUS: test=createSampleFileAndReportIt
      INSTRUMENTATION_STATUS_CODE: 1
      INSTRUMENTATION_STATUS: android.studio.v3display.benchmark=benchmark: [trace](uri://trace.perfetto-trace)
      INSTRUMENTATION_STATUS: android.studio.v3display.benchmark.outputDirPath=/sdcard/Android/media/com.example.helloworld/additional_test_output
      INSTRUMENTATION_STATUS: class=com.example.helloworld.AdditionalTestOutputExampleTest
      INSTRUMENTATION_STATUS: current=1
      INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
      INSTRUMENTATION_STATUS: numtests=1
      INSTRUMENTATION_STATUS: stream=.
      INSTRUMENTATION_STATUS: test=createSampleFileAndReportIt
      INSTRUMENTATION_STATUS_CODE: 0
      INSTRUMENTATION_RESULT: stream=
      Time: 1.0

      OK (1 test)

      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()

    // Same as AmInstrumentationRunner: parse on a dedicated thread, then call done() after it terminates.
    val outThread = Thread({ instrumentationOutput.lines().forEach { parser.parse(it) } }, "AmInstrumentationRunner-out")
    outThread.setUncaughtExceptionHandler { _, _ -> /* Swallow to keep test output clean; default handler only prints. */ }
    outThread.start()
    outThread.join()
    parser.done()
    listener.finish()

    val dynamicTestExecutor = mock<Node.DynamicTestExecutor>()
    descriptor.execute(context, dynamicTestExecutor)

    val captor = argumentCaptor<AndroidDynamicTestDescriptor>()
    verify(dynamicTestExecutor).execute(captor.capture())

    // The XML reporter already considers the test passed...
    val ddmlibTestId = DdmlibTestIdentifier("com.example.helloworld.AdditionalTestOutputExampleTest", "createSampleFileAndReportIt")
    verify(mockReporter).testEnded(eq(ddmlibTestId), any())
    verify(mockReporter, never()).testFailed(any(), any())

    // ...so the JUnit result must agree. Previously this threw RuntimeException("Instrumentation ended unexpectedly").
    captor.firstValue.execute(context, dynamicTestExecutor)
  }
}
