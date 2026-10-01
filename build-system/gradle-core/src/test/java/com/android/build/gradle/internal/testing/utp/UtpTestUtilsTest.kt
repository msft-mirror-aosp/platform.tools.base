/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.internal.testing.utp

import com.android.build.gradle.internal.SdkComponentsBuildService
import com.android.build.gradle.internal.testing.INSTRUMENTATION_ARGS_KEY
import com.android.build.gradle.internal.testing.utp.worker.RunUtpWorkAction
import com.android.tools.utp.gradle.api.RunUtpWorkParameters
import com.android.tools.utp.gradle.api.UtpDependencies
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val TEST_RESULT_EXIT_CODE_FILE_NAME = "test-result-exit-code.txt"

/** Unit tests for UtpTestUtils.kt. */
class UtpTestUtilsTest {
  @get:Rule val temporaryFolderRule = TemporaryFolder()

  private val mockUtpDependencies: UtpDependencies = mock(defaultAnswer = RETURNS_DEEP_STUBS)
  private val mockWorkerExecutor: WorkerExecutor = mock()
  private val mockVersionedSdkLoader: SdkComponentsBuildService.VersionedSdkLoader = mock()
  private val mockWorkQueue: WorkQueue = mock()

  private val testData =
    com.android.tools.utp.gradle.api.TestData(
      applicationId = "com.example.application.test",
      testedApplicationId = "com.example.application",
      instrumentationTargetPackageId = "com.example.application",
      instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
      instrumentationRunnerArguments = emptyMap(),
      animationsDisabled = false,
      isTestCoverageEnabled = false,
      testApk = File("testApk.apk"),
    )

  private val targetApkConfigBundle = com.android.tools.utp.gradle.api.TargetApkConfigBundle(appApks = emptyList(), isSplitApk = false)

  private fun <T : Any> mockProperty(value: T): org.gradle.api.provider.Property<T> {
    val mock = mock<org.gradle.api.provider.Property<T>>()
    whenever(mock.get()).thenReturn(value)
    whenever(mock.orNull).thenReturn(value)
    return mock
  }

  private fun <T : Any> mockListProperty(value: List<T>): org.gradle.api.provider.ListProperty<T> {
    val mock = mock<org.gradle.api.provider.ListProperty<T>>()
    whenever(mock.get()).thenReturn(value)
    whenever(mock.orNull).thenReturn(value)
    return mock
  }

  private fun mockRegularFileProperty(file: File): org.gradle.api.file.RegularFileProperty {
    val mock = mock<org.gradle.api.file.RegularFileProperty>()
    val mockRegularFile = mock<org.gradle.api.file.RegularFile>()
    whenever(mock.get()).thenReturn(mockRegularFile)
    whenever(mockRegularFile.asFile).thenReturn(file)
    return mock
  }

  private fun mockDirectoryProperty(dir: File): org.gradle.api.file.DirectoryProperty {
    val mock = mock<org.gradle.api.file.DirectoryProperty>()
    val mockDirectory = mock<org.gradle.api.file.Directory>()
    whenever(mock.get()).thenReturn(mockDirectory)
    whenever(mockDirectory.asFile).thenReturn(dir)
    whenever(mock.isPresent).thenReturn(true)
    return mock
  }

  private fun configureMockConfig(
    config: RunUtpWorkParameters.UtpRunConfig,
    deviceId: String,
    serial: String,
    outputDir: File,
    utpResultFile: File,
    useOrchestrator: Boolean = false,
  ) {
    val deviceIdProp = mockProperty(deviceId)
    val deviceSerialProp = mockProperty(serial)
    val installTimeoutProp = mockProperty(30)
    val targetApkBundleProp = mockProperty(targetApkConfigBundle)
    val testDataProp = mockProperty(testData)

    val mockHelperApks = mock<org.gradle.api.file.ConfigurableFileCollection>()
    whenever(mockHelperApks.files).thenReturn(emptySet())

    val installOptionsProp = mockListProperty(emptyList<String>())
    val uninstallApksProp = mockProperty(false)
    val useOrchestratorProp = mockProperty(useOrchestrator)
    val outputDirProp = mockDirectoryProperty(outputDir)
    val utpResultProp = mockRegularFileProperty(utpResultFile)

    whenever(config.deviceId).thenReturn(deviceIdProp)
    whenever(config.deviceSerialNumber).thenReturn(deviceSerialProp)
    whenever(config.installApkTimeout).thenReturn(installTimeoutProp)
    whenever(config.targetApkConfigBundle).thenReturn(targetApkBundleProp)
    whenever(config.testData).thenReturn(testDataProp)
    whenever(config.helperApks).thenReturn(mockHelperApks)
    whenever(config.additionalInstallOptions).thenReturn(installOptionsProp)
    whenever(config.uninstallApksAfterTest).thenReturn(uninstallApksProp)
    whenever(config.useOrchestrator).thenReturn(useOrchestratorProp)
    whenever(config.outputDir).thenReturn(outputDirProp)
    whenever(config.utpResultProtoOutputFile).thenReturn(utpResultProp)
  }

  @Before
  fun setupMocks() {
    whenever(mockWorkerExecutor.processIsolation(any())).thenReturn(mockWorkQueue)

    val mockAdbProvider: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile> = mock()
    val mockAdbFile: org.gradle.api.file.RegularFile = mock()
    whenever(mockVersionedSdkLoader.adbExecutableProvider).thenReturn(mockAdbProvider)
    whenever(mockAdbProvider.get()).thenReturn(mockAdbFile)
    whenever(mockAdbFile.asFile).thenReturn(File("mock-adb"))

    val mockBuildToolInfoProvider: org.gradle.api.provider.Provider<com.android.sdklib.BuildToolInfo> = mock()
    val mockBuildToolInfo: com.android.sdklib.BuildToolInfo = mock()
    whenever(mockVersionedSdkLoader.buildToolInfoProvider).thenReturn(mockBuildToolInfoProvider)
    whenever(mockBuildToolInfoProvider.get()).thenReturn(mockBuildToolInfo)
    whenever(mockBuildToolInfo.getPath(com.android.sdklib.BuildToolInfo.PathId.AAPT)).thenReturn("mock-aapt2")
    whenever(mockBuildToolInfo.getPath(com.android.sdklib.BuildToolInfo.PathId.DEXDUMP)).thenReturn("mock-dexdump")

    val mockSdkDirectoryProvider: org.gradle.api.provider.Provider<org.gradle.api.file.Directory> = mock()
    whenever(mockVersionedSdkLoader.sdkDirectoryProvider).thenReturn(mockSdkDirectoryProvider)
  }

  private fun runUtp(expectedResultCode: Int = 0): Boolean {
    val utpResultDir = temporaryFolderRule.newFolder()

    val mockProvider: Provider<String> = mock()
    whenever(mockProvider.orNull).thenReturn(null)
    val mockProviderFactory: ProviderFactory = mock()
    whenever(mockProviderFactory.gradleProperty(any<String>())).thenReturn(mockProvider)

    val config: RunUtpWorkParameters.UtpRunConfig = mock()
    configureMockConfig(config, "device1", "serial1", utpResultDir, File(utpResultDir, "utp-result.pb"))

    whenever(mockWorkQueue.submit(eq(RunUtpWorkAction::class.java), any())).then { invocation ->
      val action = invocation.getArgument<org.gradle.api.Action<RunUtpWorkParameters>>(1)
      val mockParams = mock<RunUtpWorkParameters>(defaultAnswer = RETURNS_DEEP_STUBS)

      val mockExitCodeFileProperty = mock<org.gradle.api.file.RegularFileProperty>()
      whenever(mockParams.testResultExitCodeFile).thenReturn(mockExitCodeFileProperty)

      var targetFile: File? = null
      whenever(mockExitCodeFileProperty.fileValue(any<File>())).then { inv ->
        targetFile = inv.getArgument<File>(0)
        mockExitCodeFileProperty
      }

      action.execute(mockParams)

      if (targetFile != null) {
        targetFile!!.parentFile?.mkdirs()
        targetFile!!.writeText(expectedResultCode.toString())
      }
    }

    return runUtpTestSuiteAndWait(
      listOf(config),
      mockWorkerExecutor,
      "projectName",
      "variantName",
      utpResultDir,
      mockUtpDependencies,
      mockVersionedSdkLoader,
      mockProviderFactory,
    )
  }

  @Test
  fun runSuccessfully() {
    val results = runUtp()

    assertThat(results).isTrue()
  }

  @Test
  fun runSuccessfullyButTestFailed() {
    val results = runUtp(expectedResultCode = 1)

    assertThat(results).isFalse()
  }

  private fun captureWorkerSystemProperties(instrumentationArgs: Map<String, String>): Map<String, Any?> {
    val utpResultDir = temporaryFolderRule.newFolder()

    val mockProvider: Provider<String> = mock()
    whenever(mockProvider.orNull).thenReturn(null)
    val mockProviderFactory: ProviderFactory = mock()
    whenever(mockProviderFactory.gradleProperty(any<String>())).thenReturn(mockProvider)

    val config: RunUtpWorkParameters.UtpRunConfig = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    configureMockConfig(config, "device1", "serial1", utpResultDir, File(utpResultDir, "utp-result.pb"))
    val testDataProp = mockProperty(testData.copy(instrumentationRunnerArguments = instrumentationArgs))
    val forceCompilationProp = mockProperty(false)
    val pccProp = mockProperty(false)
    val coverageOutputDirProp = mockDirectoryProperty(utpResultDir)
    whenever(config.testData).thenReturn(testDataProp)
    whenever(config.forceCompilation).thenReturn(forceCompilationProp)
    whenever(config.privateComputeCoreInstrumentationEnabled).thenReturn(pccProp)
    whenever(config.coverageOutputDir).thenReturn(coverageOutputDirProp)
    whenever(config.additionalTestOutputOnDeviceDir.isPresent).thenReturn(false)
    whenever(config.additionalTestOutputDir.isPresent).thenReturn(false)

    val systemProperties = mutableMapOf<String, Any?>()
    whenever(mockWorkerExecutor.processIsolation(any())).then { invocation ->
      val specAction = invocation.getArgument<org.gradle.api.Action<org.gradle.workers.ProcessWorkerSpec>>(0)
      val mockSpec = mock<org.gradle.workers.ProcessWorkerSpec>(defaultAnswer = RETURNS_DEEP_STUBS)
      whenever(mockSpec.forkOptions(any())).then { forkInvocation ->
        val forkAction = forkInvocation.getArgument<org.gradle.api.Action<org.gradle.process.JavaForkOptions>>(0)
        val mockFork = mock<org.gradle.process.JavaForkOptions>()
        whenever(mockFork.systemProperty(any(), any())).then { propInvocation ->
          systemProperties[propInvocation.getArgument(0)] = propInvocation.getArgument(1)
          mockFork
        }
        forkAction.execute(mockFork)
      }
      specAction.execute(mockSpec)
      mockWorkQueue
    }

    runUtpTestSuiteAndWait(
      listOf(config),
      mockWorkerExecutor,
      "projectName",
      "variantName",
      utpResultDir,
      mockUtpDependencies,
      mockVersionedSdkLoader,
      mockProviderFactory,
    )
    return systemProperties
  }

  @Test
  fun escapesCommaSeparatedInstrumentationArgs() {
    val systemProperties = captureWorkerSystemProperties(mapOf("class" to "com.example.ATest,com.example.BTest", "trailing" to "foo\\"))

    assertThat(systemProperties[INSTRUMENTATION_ARGS_KEY]).isEqualTo("class=com.example.ATest\\,com.example.BTest,trailing=foo\\\\")
  }
}
