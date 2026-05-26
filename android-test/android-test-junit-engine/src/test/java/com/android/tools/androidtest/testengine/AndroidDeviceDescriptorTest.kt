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
import org.junit.platform.engine.ConfigurationParameters
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.rules.TemporaryFolder
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit tests for the [AndroidDeviceDescriptor] class. */
class AndroidDeviceDescriptorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

  @Mock private lateinit var executionRequest: ExecutionRequest
  @Mock private lateinit var engineExecutionListener: EngineExecutionListener
  @Mock private lateinit var configurationParameters: ConfigurationParameters
  @Mock private lateinit var adbController: AdbController

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

  @Test
  fun `extractAgentIfNeeded runs expected commands when coverage enabled`() {
    val configParams =
      object : ConfigurationParameters {
        private val params =
          mapOf(
            AndroidTestConfigurationKeys.ADB_PATH to adbFile.absolutePath,
            AndroidTestConfigurationKeys.AAPT2_PATH to aapt2File.absolutePath,
            AndroidTestConfigurationKeys.DEVICE_SERIALS to deviceSerial,
            AndroidTestConfigurationKeys.TEST_PACKAGE_ID to "com.example.app.test",
            AndroidTestConfigurationKeys.INSTRUMENTATION_TARGET_PACKAGE_ID to "com.example.app",
            AndroidTestConfigurationKeys.INSTRUMENTATION_RUNNER_CLASS to "android.support.test.runner.AndroidJUnitRunner",
            AndroidTestConfigurationKeys.COVERAGE_TYPE to "ON_THE_FLY",
          )

        override fun get(key: String): java.util.Optional<String> = java.util.Optional.ofNullable(params[key])

        override fun getBoolean(key: String): java.util.Optional<Boolean> = get(key).map { it.toBoolean() }

        override fun size(): Int = params.size

        override fun keySet(): Set<String> = params.keys
      }
    whenever(executionRequest.configurationParameters).thenReturn(configParams)

    val descriptor =
      AndroidDeviceDescriptor(
        UniqueId.forEngine("android-test-engine").append("device", deviceSerial),
        deviceSerial,
        adbControllerFactory = { adbController },
      )
    val context = AndroidTestExecutionContext(executionRequest)
    val config = context.configuration

    val abi = "x86_64"
    val apkPath = "/data/app/pkg-1/base.apk"

    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.product.cpu.abi")), anyOrNull()))
      .thenReturn(AdbController.CommandResult(0, abi, ""))
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("pm", "path", "com.example.app.test")), anyOrNull()))
      .thenReturn(AdbController.CommandResult(0, "package:$apkPath", ""))
    whenever(
        adbController.runAdbShellCommand(
          eq(deviceSerial),
          eq(listOf("run-as", "com.example.app.test", "sh", "-c", "\"unzip -p $apkPath lib/$abi/coverage_agent.so > coverage_agent.so\"")),
          anyOrNull(),
        )
      )
      .thenReturn(AdbController.CommandResult(0, "", ""))
    whenever(
        adbController.runAdbShellCommand(
          eq(deviceSerial),
          eq(listOf("run-as", "com.example.app.test", "ls", "-l", "coverage_agent.so")),
          anyOrNull(),
        )
      )
      .thenReturn(AdbController.CommandResult(0, "-rw------- 1 ... coverage_agent.so", ""))

    descriptor.extractAgentIfNeeded(config)

    verify(adbController)
      .runAdbShellCommand(
        eq(deviceSerial),
        eq(listOf("run-as", "com.example.app.test", "sh", "-c", "\"unzip -p $apkPath lib/$abi/coverage_agent.so > coverage_agent.so\"")),
        anyOrNull(),
      )
  }
}
