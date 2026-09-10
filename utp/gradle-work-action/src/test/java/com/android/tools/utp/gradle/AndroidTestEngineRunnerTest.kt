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

package com.android.tools.utp.gradle

import com.android.tools.androidtest.testengine.config.AndroidTestConfigurationKeys
import com.android.tools.utp.gradle.api.EmulatorControlConfig
import com.android.tools.utp.gradle.api.RunUtpWorkParameters
import com.android.tools.utp.gradle.api.RunUtpWorkParameters.UtpRunConfig
import com.android.tools.utp.gradle.api.TargetApkConfigBundle
import com.android.tools.utp.gradle.api.TestData
import com.google.common.truth.Truth.assertThat
import com.google.testing.platform.proto.api.core.TestSuiteResultProto
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import java.io.File
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AndroidTestEngineRunnerTest {

  @get:Rule var temporaryFolder = TemporaryFolder()

  private lateinit var exitCodeFile: File
  private lateinit var mergedResultFile: File

  private val testData =
    TestData(
      applicationId = "com.example.application.test",
      testedApplicationId = "com.example.application",
      instrumentationTargetPackageId = "com.example.application",
      instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
      instrumentationRunnerArguments = emptyMap(),
      animationsDisabled = false,
      isTestCoverageEnabled = false,
      testApk = File("testApk.apk"),
    )

  private val targetApkConfigBundle = TargetApkConfigBundle(appApks = emptyList(), isSplitApk = false)

  @Before
  fun setUp() {
    exitCodeFile = temporaryFolder.newFile("exit-code.txt")
    mergedResultFile = temporaryFolder.newFile("merged-result.pb")
  }

  private fun <T : Any> mockProperty(value: T): Property<T> {
    val mock = mock<Property<T>>()
    whenever(mock.get()).thenReturn(value)
    whenever(mock.orNull).thenReturn(value)
    return mock
  }

  private fun <T : Any> mockListProperty(value: List<T>): ListProperty<T> {
    val mock = mock<ListProperty<T>>()
    whenever(mock.get()).thenReturn(value)
    whenever(mock.orNull).thenReturn(value)
    return mock
  }

  private fun mockRegularFileProperty(file: File): RegularFileProperty {
    val mock = mock<RegularFileProperty>()
    val mockRegularFile = mock<RegularFile>()
    whenever(mock.get()).thenReturn(mockRegularFile)
    whenever(mockRegularFile.asFile).thenReturn(file)
    return mock
  }

  private fun mockDirectoryProperty(dir: File): DirectoryProperty {
    val mock = mock<DirectoryProperty>()
    val mockDirectory = mock<Directory>()
    whenever(mock.get()).thenReturn(mockDirectory)
    whenever(mockDirectory.asFile).thenReturn(dir)
    whenever(mock.isPresent).thenReturn(true)
    return mock
  }

  private fun <T : Any> mockEmptyProperty(): Property<T> {
    val mock = mock<Property<T>>()
    whenever(mock.get()).thenThrow(IllegalStateException("value not available"))
    whenever(mock.orNull).thenReturn(null)
    return mock
  }

  private fun configureMockConfig(
    config: UtpRunConfig,
    deviceId: String,
    serial: String,
    outputDir: File,
    utpResultFile: File,
    useOrchestrator: Boolean = false,
    hasEmulatorControlConfig: Boolean = true,
  ) {
    val deviceIdProp = mockProperty(deviceId)
    val deviceSerialProp = mockProperty(serial)
    val deviceNameProp = mockProperty(deviceId)
    val deviceShardNameProp = mockProperty(deviceId)
    val installTimeoutProp = mockProperty(30)
    val targetApkBundleProp = mockProperty(targetApkConfigBundle)
    val testDataProp = mockProperty(testData)
    val emulatorControlConfigProp =
      if (hasEmulatorControlConfig) {
        mockProperty(EmulatorControlConfig(enabled = false, allowedEndpoints = emptySet(), secondsValid = 0))
      } else {
        mockEmptyProperty()
      }

    val mockHelperApks = mock<ConfigurableFileCollection>()
    whenever(mockHelperApks.files).thenReturn(emptySet())

    val installOptionsProp = mockListProperty(emptyList<String>())
    val uninstallApksProp = mockProperty(false)
    val useOrchestratorProp = mockProperty(useOrchestrator)
    val outputDirProp = mockDirectoryProperty(outputDir)
    val utpResultProp = mockRegularFileProperty(utpResultFile)

    whenever(config.deviceId).thenReturn(deviceIdProp)
    whenever(config.deviceSerialNumber).thenReturn(deviceSerialProp)
    whenever(config.deviceName).thenReturn(deviceNameProp)
    whenever(config.deviceShardName).thenReturn(deviceShardNameProp)
    whenever(config.installApkTimeout).thenReturn(installTimeoutProp)
    whenever(config.targetApkConfigBundle).thenReturn(targetApkBundleProp)
    whenever(config.testData).thenReturn(testDataProp)
    whenever(config.emulatorControlConfig).thenReturn(emulatorControlConfigProp)
    whenever(config.helperApks).thenReturn(mockHelperApks)
    whenever(config.additionalInstallOptions).thenReturn(installOptionsProp)
    whenever(config.uninstallApksAfterTest).thenReturn(uninstallApksProp)
    whenever(config.useOrchestrator).thenReturn(useOrchestratorProp)
    whenever(config.outputDir).thenReturn(outputDirProp)
    whenever(config.utpResultProtoOutputFile).thenReturn(utpResultProp)
  }

  private fun configureMockParameters(parameters: RunUtpWorkParameters, configs: List<UtpRunConfig>) {
    val adbProp = mockRegularFileProperty(File("adb"))
    val aaptProp = mockRegularFileProperty(File("aapt2"))
    val runConfigsProp = mockListProperty(configs)
    val xmlReportDirProp = mockDirectoryProperty(File("xml-report"))

    whenever(parameters.adbExecutable).thenReturn(adbProp)
    whenever(parameters.aaptExecutable).thenReturn(aaptProp)
    whenever(parameters.utpRunConfigs).thenReturn(runConfigsProp)
    whenever(parameters.xmlTestReportOutputDirectory).thenReturn(xmlReportDirProp)
  }

  @Test
  fun execute_successfulRun() {
    val parameters = mock<RunUtpWorkParameters>()
    val config = mock<UtpRunConfig>()
    val xmlReportDir = temporaryFolder.newFolder("xml-report")
    val outputDir = temporaryFolder.newFolder("output")

    val deviceDir = File(xmlReportDir, "device1")
    deviceDir.mkdirs()
    val localUtpResultFile = File(deviceDir, "test-result.pb")
    val dummyResult = TestSuiteResult.newBuilder().build()
    localUtpResultFile.outputStream().use { dummyResult.writeTo(it) }

    val targetResultFile = File(outputDir, "test-result.pb")

    configureMockConfig(config, "device1", "serial1", outputDir, targetResultFile)

    val xmlReportDirProp = mockDirectoryProperty(xmlReportDir)
    val adbProp = mockRegularFileProperty(File("adb"))
    val aaptProp = mockRegularFileProperty(File("aapt2"))
    val runConfigsProp = mockListProperty(listOf(config))

    whenever(parameters.adbExecutable).thenReturn(adbProp)
    whenever(parameters.aaptExecutable).thenReturn(aaptProp)
    whenever(parameters.utpRunConfigs).thenReturn(runConfigsProp)
    whenever(parameters.xmlTestReportOutputDirectory).thenReturn(xmlReportDirProp)

    val runner = AndroidTestEngineRunner { request, listener -> mapOf("serial1" to true) }

    runner.execute(parameters, emptyList(), mergedResultFile, exitCodeFile)

    assertThat(exitCodeFile.readText()).isEqualTo("0")
    assertThat(mergedResultFile.exists()).isTrue()
    assertThat(targetResultFile.exists()).isTrue()
    assertThat(localUtpResultFile.exists()).isFalse()
  }

  @Test
  fun execute_multipleConfigs_successfulRun() {
    val parameters = mock<RunUtpWorkParameters>()
    val config1 = mock<UtpRunConfig>()
    val config2 = mock<UtpRunConfig>()
    val xmlReportDir = temporaryFolder.newFolder("xml-report")
    val outputDir1 = temporaryFolder.newFolder("output1")
    val outputDir2 = temporaryFolder.newFolder("output2")
    val utpResultFile1 = File(File(xmlReportDir, "device1").also { it.mkdirs() }, "test-result.pb")
    val utpResultFile2 = File(File(xmlReportDir, "device2").also { it.mkdirs() }, "test-result.pb")

    val dummyResult1 =
      TestSuiteResult.newBuilder()
        .setTestSuiteMetaData(TestSuiteResultProto.TestSuiteMetaData.newBuilder().setScheduledTestCaseCount(1))
        .build()
    utpResultFile1.outputStream().use { dummyResult1.writeTo(it) }
    val dummyResult2 =
      TestSuiteResult.newBuilder()
        .setTestSuiteMetaData(TestSuiteResultProto.TestSuiteMetaData.newBuilder().setScheduledTestCaseCount(2))
        .build()
    utpResultFile2.outputStream().use { dummyResult2.writeTo(it) }

    val targetResultFile1 = File(outputDir1, "test-result.pb")
    val targetResultFile2 = File(outputDir2, "test-result.pb")

    configureMockConfig(config1, "device1", "serial1", outputDir1, targetResultFile1)
    configureMockConfig(config2, "device2", "serial2", outputDir2, targetResultFile2)

    val xmlReportDirProp = mockDirectoryProperty(xmlReportDir)
    val adbProp = mockRegularFileProperty(File("adb"))
    val aaptProp = mockRegularFileProperty(File("aapt2"))
    val runConfigsProp = mockListProperty(listOf(config1, config2))

    whenever(parameters.adbExecutable).thenReturn(adbProp)
    whenever(parameters.aaptExecutable).thenReturn(aaptProp)
    whenever(parameters.utpRunConfigs).thenReturn(runConfigsProp)
    whenever(parameters.xmlTestReportOutputDirectory).thenReturn(xmlReportDirProp)

    val runner = AndroidTestEngineRunner { request, listener -> mapOf("serial1" to true, "serial2" to true) }

    runner.execute(parameters, emptyList(), mergedResultFile, exitCodeFile)

    assertThat(exitCodeFile.readText()).isEqualTo("0")
    assertThat(mergedResultFile.exists()).isTrue()

    val mergedResult = TestSuiteResult.parseFrom(mergedResultFile.inputStream())
    assertThat(mergedResult.testSuiteMetaData.scheduledTestCaseCount).isEqualTo(3)
    assertThat(targetResultFile1.exists()).isTrue()
    assertThat(targetResultFile2.exists()).isTrue()
    assertThat(utpResultFile1.exists()).isFalse()
    assertThat(utpResultFile2.exists()).isFalse()
  }

  @Test
  fun execute_substringDeviceIds_successfulRun() {
    val parameters = mock<RunUtpWorkParameters>()
    val config1 = mock<UtpRunConfig>()
    val config2 = mock<UtpRunConfig>()
    val xmlReportDir = temporaryFolder.newFolder("xml-report")
    val outputDir1 = temporaryFolder.newFolder("output1")
    val outputDir2 = temporaryFolder.newFolder("output2")

    // We want to simulate the case where device10 is matched when we look for device1.
    // If we name the directories "device10" and "device1", "device10" contains "device1".
    val utpResultFile10 = File(File(xmlReportDir, "device10").also { it.mkdirs() }, "test-result.pb")
    val utpResultFile1 = File(File(xmlReportDir, "device1").also { it.mkdirs() }, "test-result.pb")

    val dummyResult10 =
      TestSuiteResult.newBuilder()
        .setTestSuiteMetaData(TestSuiteResultProto.TestSuiteMetaData.newBuilder().setScheduledTestCaseCount(10))
        .build()
    utpResultFile10.outputStream().use { dummyResult10.writeTo(it) }

    val dummyResult1 =
      TestSuiteResult.newBuilder()
        .setTestSuiteMetaData(TestSuiteResultProto.TestSuiteMetaData.newBuilder().setScheduledTestCaseCount(1))
        .build()
    utpResultFile1.outputStream().use { dummyResult1.writeTo(it) }

    val targetResultFile1 = File(outputDir1, "test-result.pb")
    val targetResultFile2 = File(outputDir2, "test-result.pb")

    configureMockConfig(config1, "device1", "serial1", outputDir1, targetResultFile1)
    configureMockConfig(config2, "device10", "serial10", outputDir2, targetResultFile2)

    val xmlReportDirProp = mockDirectoryProperty(xmlReportDir)
    val adbProp = mockRegularFileProperty(File("adb"))
    val aaptProp = mockRegularFileProperty(File("aapt2"))
    val runConfigsProp = mockListProperty(listOf(config1, config2))

    whenever(parameters.adbExecutable).thenReturn(adbProp)
    whenever(parameters.aaptExecutable).thenReturn(aaptProp)
    whenever(parameters.utpRunConfigs).thenReturn(runConfigsProp)
    whenever(parameters.xmlTestReportOutputDirectory).thenReturn(xmlReportDirProp)

    val runner = AndroidTestEngineRunner { request, listener -> mapOf("serial1" to true, "serial10" to true) }

    runner.execute(parameters, emptyList(), mergedResultFile, exitCodeFile)

    assertThat(exitCodeFile.readText()).isEqualTo("0")
    assertThat(mergedResultFile.exists()).isTrue()

    val mergedResult = TestSuiteResult.parseFrom(mergedResultFile.inputStream())

    assertThat(targetResultFile1.exists()).isTrue()
    assertThat(targetResultFile2.exists()).isTrue()

    val result1 = TestSuiteResult.parseFrom(targetResultFile1.inputStream())
    val result2 = TestSuiteResult.parseFrom(targetResultFile2.inputStream())

    assertThat(result1.testSuiteMetaData.scheduledTestCaseCount).isEqualTo(1)
    assertThat(result2.testSuiteMetaData.scheduledTestCaseCount).isEqualTo(10)
    assertThat(mergedResult.testSuiteMetaData.scheduledTestCaseCount).isEqualTo(11)
  }

  @Test
  fun execute_failedRun() {
    val parameters = mock<RunUtpWorkParameters>()
    val config = mock<UtpRunConfig>()
    val xmlReportDir = temporaryFolder.newFolder("xml-report")
    val outputDir = temporaryFolder.newFolder("output")

    val deviceDir = File(xmlReportDir, "device1")
    deviceDir.mkdirs()
    val localUtpResultFile = File(deviceDir, "test-result.pb")
    val dummyResult = TestSuiteResult.newBuilder().build()
    localUtpResultFile.outputStream().use { dummyResult.writeTo(it) }

    val targetResultFile = File(outputDir, "test-result.pb")

    configureMockConfig(config, "device1", "serial1", outputDir, targetResultFile)

    val xmlReportDirProp = mockDirectoryProperty(xmlReportDir)
    val adbProp = mockRegularFileProperty(File("adb"))
    val aaptProp = mockRegularFileProperty(File("aapt2"))
    val runConfigsProp = mockListProperty(listOf(config))

    whenever(parameters.adbExecutable).thenReturn(adbProp)
    whenever(parameters.aaptExecutable).thenReturn(aaptProp)
    whenever(parameters.utpRunConfigs).thenReturn(runConfigsProp)
    whenever(parameters.xmlTestReportOutputDirectory).thenReturn(xmlReportDirProp)

    val runner = AndroidTestEngineRunner { request, listener -> mapOf("serial1" to false) }

    runner.execute(parameters, emptyList(), mergedResultFile, exitCodeFile)

    assertThat(exitCodeFile.readText()).isEqualTo("1")
  }

  @Test
  fun execute_withoutEmulatorControlConfig_successfulRun() {
    val parameters = mock<RunUtpWorkParameters>()
    val config = mock<UtpRunConfig>()
    val xmlReportDir = temporaryFolder.newFolder("xml-report")
    val outputDir = temporaryFolder.newFolder("output")

    val deviceDir = File(xmlReportDir, "device1")
    deviceDir.mkdirs()
    val localUtpResultFile = File(deviceDir, "test-result.pb")
    val dummyResult = TestSuiteResult.newBuilder().build()
    localUtpResultFile.outputStream().use { dummyResult.writeTo(it) }

    val targetResultFile = File(outputDir, "test-result.pb")

    configureMockConfig(config, "device1", "serial1", outputDir, targetResultFile, hasEmulatorControlConfig = false)

    val xmlReportDirProp = mockDirectoryProperty(xmlReportDir)
    val adbProp = mockRegularFileProperty(File("adb"))
    val aaptProp = mockRegularFileProperty(File("aapt2"))
    val runConfigsProp = mockListProperty(listOf(config))

    whenever(parameters.adbExecutable).thenReturn(adbProp)
    whenever(parameters.aaptExecutable).thenReturn(aaptProp)
    whenever(parameters.utpRunConfigs).thenReturn(runConfigsProp)
    whenever(parameters.xmlTestReportOutputDirectory).thenReturn(xmlReportDirProp)

    val runner = AndroidTestEngineRunner { request, listener -> mapOf("serial1" to true) }

    runner.execute(parameters, emptyList(), mergedResultFile, exitCodeFile)

    assertThat(exitCodeFile.readText()).isEqualTo("0")
    assertThat(mergedResultFile.exists()).isTrue()
    assertThat(targetResultFile.exists()).isTrue()
    assertThat(localUtpResultFile.exists()).isFalse()
  }

  @Test
  fun execute_enablesParallelTestResultReporting() {
    val parameters = mock<RunUtpWorkParameters>()
    val config = mock<UtpRunConfig>()
    val xmlReportDir = temporaryFolder.newFolder("xml-report")
    val outputDir = temporaryFolder.newFolder("output")

    val deviceDir = File(xmlReportDir, "device1").also { it.mkdirs() }
    val localUtpResultFile = File(deviceDir, "test-result.pb")
    val dummyResult = TestSuiteResult.newBuilder().build()
    localUtpResultFile.outputStream().use { dummyResult.writeTo(it) }

    val targetResultFile = File(outputDir, "test-result.pb")
    configureMockConfig(config, "device1", "serial1", outputDir, targetResultFile)

    val xmlReportDirProp = mockDirectoryProperty(xmlReportDir)
    val adbProp = mockRegularFileProperty(File("adb"))
    val aaptProp = mockRegularFileProperty(File("aapt2"))
    val runConfigsProp = mockListProperty(listOf(config))

    whenever(parameters.adbExecutable).thenReturn(adbProp)
    whenever(parameters.aaptExecutable).thenReturn(aaptProp)
    whenever(parameters.utpRunConfigs).thenReturn(runConfigsProp)
    whenever(parameters.xmlTestReportOutputDirectory).thenReturn(xmlReportDirProp)

    var capturedRequest: LauncherDiscoveryRequest? = null
    val runner = AndroidTestEngineRunner { request, _ ->
      capturedRequest = request
      mapOf("serial1" to true)
    }

    runner.execute(parameters, emptyList(), mergedResultFile, exitCodeFile)

    assertThat(capturedRequest).isNotNull()
    assertThat(capturedRequest!!.configurationParameters.get(AndroidTestConfigurationKeys.PARALLEL_TEST_RESULT_REPORTING).orElse(null))
      .isEqualTo("true")
  }
}
