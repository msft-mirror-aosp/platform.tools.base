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

import com.android.tools.androidtest.testengine.instrument.AmInstrumentationRunner
import com.android.tools.androidtest.testengine.instrument.TestIdentifier
import com.android.tools.androidtest.testengine.instrument.TestResult
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
  fun `instrumentationStarted reports device info path`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", deviceSerial)
    val descriptor = AndroidDeviceDescriptor(uniqueId, deviceSerial)
    val context = AndroidTestExecutionContext(executionRequest)
    val deviceInfoFile = File(resultsDir, "device-info.pb")

    val listener = descriptor.Listener(context, null, null, deviceInfoFile, null)

    listener.instrumentationStarted(1)

    val reportEntryCaptor = argumentCaptor<ReportEntry>()
    verify(engineExecutionListener, atLeastOnce()).reportingEntryPublished(eq(descriptor), reportEntryCaptor.capture())

    val deviceInfoEntry = reportEntryCaptor.allValues.find { it.keyValuePairs.containsKey(AndroidTestReportKeys.DEVICE_INFO_PATH) }
    assertThat(deviceInfoEntry).isNotNull()
    assertThat(deviceInfoEntry?.keyValuePairs?.get(AndroidTestReportKeys.DEVICE_INFO_PATH)).isEqualTo(deviceInfoFile.absolutePath)
  }

  @Mock private lateinit var mockDynamicTestExecutor: Node.DynamicTestExecutor
  @Mock private lateinit var mockAdbApkInstaller: AdbApkInstaller
  @Mock private lateinit var mockAmInstrumentationRunner: AmInstrumentationRunner

  @Test
  fun execute_withEmulatorControlEnabled_injectsGrpcPortAndToken() {
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
    descriptor.execute(context, mockDynamicTestExecutor)
  }

  @Test
  fun execute_withEmulatorControlDisabled_doesNotInjectGrpcPortAndToken() {
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
    descriptor.execute(context, mockDynamicTestExecutor)
  }

  @Test
  fun `testEnded reports benchmark trace paths and message path`() {
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

    val listener = descriptor.Listener(context, null, null, null, additionalTestOutputCollector)

    // We need to call testStarted to register the test descriptor,
    // so that testEnded can find it and publish the report entry.
    listener.testStarted(testIdentifier)

    listener.testEnded(mockTestResult)

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
}
