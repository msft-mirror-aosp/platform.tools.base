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

import com.android.build.api.variant.impl.AndroidVersionImpl
import com.android.build.gradle.internal.AvdComponentsBuildService
import com.android.build.gradle.internal.SdkComponentsBuildService
import com.android.build.gradle.internal.dsl.ManagedVirtualDevice
import com.android.build.gradle.internal.testing.StaticTestData
import com.android.build.gradle.internal.testing.utp.emulatorcontrol.EmulatorControlConfig
import com.android.mockito.kotlin.whenever
import com.android.testutils.SystemPropertyOverrides
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.Environment
import com.google.common.truth.Truth.assertThat
import com.google.testing.platform.proto.api.config.RunnerConfigProto.RunnerConfig
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import org.gradle.api.file.Directory
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.workers.WorkerExecutor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.util.logging.Level
import kotlin.io.path.Path
import kotlin.reflect.jvm.javaMethod

/**
 * Unit tests for [ManagedDeviceTestRunner].
 */
class ManagedDeviceTestRunnerTest {
    @get:Rule var temporaryFolderRule = TemporaryFolder()

    private val mockWorkerExecutor: WorkerExecutor = mock()
    private val mockObjectFactory: ObjectFactory = mock()
    private val mockVersionedSdkLoader: SdkComponentsBuildService.VersionedSdkLoader = mock()
    private val mockAvdComponents: AvdComponentsBuildService = mock(defaultAnswer = Answers.RETURNS_DEEP_STUBS)
    private val mockTestData: StaticTestData = mock()
    private val mockAppApk: File = mock()
    private val mockHelperApk: File = mock()
    private val mockLogger: Logger = mock()
    private val mockEmulatorControlConfig: EmulatorControlConfig = mock()
    private val mockCoverageOutputDir: File = mock()
    private val mockAdditionalTestOutputDir: File = mock()
    private val mockDslDevice: ManagedVirtualDevice = mock(defaultAnswer = Answers.RETURNS_DEEP_STUBS)
    private val mockUtpDependencies: UtpDependencies = mock(defaultAnswer = Answers.RETURNS_DEEP_STUBS)
    private val emulatorProvider: Provider<Directory> = mock()
    private val emulatorDirectory: Directory = mock()
    private val avdProvider: Provider<Directory> = mock()
    private val avdDirectory: Directory = mock()
    private lateinit var emulatorFolder: File
    private lateinit var avdFolder: File
    private lateinit var outputDirectory: File
    private lateinit var jvmExecutable: File

    private lateinit var capturedRunnerConfigs: List<UtpRunnerConfig>
    private var utpInvocationCount: Int = 0
    private val extractedSdkApks = listOf(listOf(Path("test1"), Path("test2")))
    private val sdkApkSet = setOf(File("test"))

    @Before
    fun setupMocks() {
        Environment.initialize()

        jvmExecutable = temporaryFolderRule.newFile()

        whenever(mockTestData.minSdkVersion).thenReturn(AndroidVersionImpl(28))
        whenever(mockTestData.testedApkFinder).thenReturn { listOf(mockAppApk) }
        whenever(mockTestData.privacySandboxInstallBundlesFinder).thenReturn { extractedSdkApks }

        whenever(mockDslDevice.pageAlignmentSuffix).thenReturn("")

        whenever(mockAvdComponents.runWithAvds(
            any(), any(),
            any<(List<String>) -> UtpTestRunResult>())).then {
            val desiredDeviceCount = it.getArgument<Int>(1)
            val deviceSerials = List(desiredDeviceCount) { "mockDeviceSerial_$it" }
            val onDevicesReadyFunc = it.getArgument<(List<String>)->UtpTestRunResult>(2)
            onDevicesReadyFunc(deviceSerials)
        }

        emulatorFolder = temporaryFolderRule.newFolder("emulator")
        whenever(emulatorDirectory.asFile).thenReturn(emulatorFolder)
        whenever(emulatorProvider.get()).thenReturn(emulatorDirectory)
        whenever(emulatorProvider.isPresent()).thenReturn(true)
        whenever(mockAvdComponents.emulatorDirectory).thenReturn(emulatorProvider)

        avdFolder = temporaryFolderRule.newFolder("avd")
        whenever(avdDirectory.asFile).thenReturn(avdFolder)
        whenever(avdProvider.get()).thenReturn(avdDirectory)
        whenever(mockAvdComponents.avdFolder).thenReturn(avdProvider)

        whenever(mockDslDevice.getName()).thenReturn("testDevice")
        whenever(mockDslDevice.device).thenReturn("Pixel 2")
        whenever(mockDslDevice.apiLevel).thenReturn(28)
        whenever(mockDslDevice.systemImageSource).thenReturn("aosp")
        whenever(mockDslDevice.require64Bit).thenReturn(true)
    }

    private fun <T> runInLinuxEnvironment(function: () -> T): T {
        return try {
            // Need to use a custom set up environment to ensure deterministic behavior.
            SystemPropertyOverrides().use { systemPropertyOverrides ->
                // This will ensure the config believes we are running on an x86_64 Linux machine.
                // This will guarantee the x86 system-image is selected.
                systemPropertyOverrides.setProperty("os.name", "Linux")
                Environment.instance = object : Environment() {
                    override fun getVariable(name: EnvironmentVariable): String? =
                        if (name.key == "HOSTTYPE") "x86_64" else null
                }
                systemPropertyOverrides.setProperty("os.arch", "x86_64")

                function.invoke()
            }
        } finally {
            Environment.instance = Environment.SYSTEM
        }
    }

    private fun runUtp(
        result: Boolean,
        numShards: Int? = null,
        hasEmulatorTimeoutException: List<Boolean> = List(numShards ?: 1) { false },
    ): Boolean {
        return runInLinuxEnvironment {
            val runner = ManagedDeviceTestRunner(
                mockWorkerExecutor,
                mockObjectFactory,
                mockUtpDependencies,
                jvmExecutable,
                mockVersionedSdkLoader,
                mockEmulatorControlConfig,
                useOrchestrator = false,
                forceCompilation = false,
                numShards,
                mockAvdComponents,
                null,
                false,
                Level.WARNING,
                false,
                { runnerConfigs, _, _, resultsDir, _ ->
                    utpInvocationCount++
                    capturedRunnerConfigs = runnerConfigs
                    TestSuiteResult.getDefaultInstance()
                        .writeTo(File(resultsDir, TEST_RESULT_PB_FILE_NAME).outputStream())
                    runnerConfigs.map {
                        UtpTestRunResult(
                            result,
                            createTestSuiteResult(
                                hasEmulatorTimeoutException[it.shardConfig?.index ?: 0]
                            )
                        )
                    }
                },
            )

            outputDirectory = temporaryFolderRule.newFolder("results")

            mockStatic(::createRunnerConfigProtoForLocalDevice.javaMethod!!.declaringClass).use { mockedStatic ->
                mockedStatic.whenever<RunnerConfig> {
                    createRunnerConfigProtoForLocalDevice(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyOrNull(),
                        anyOrNull(),
                        anyOrNull(),
                        any(),
                        any(),
                        any(),
                        anyOrNull(),
                    )
                }.thenReturn(RunnerConfig.getDefaultInstance())

                runner.runTests(
                    mockDslDevice,
                    "mockDeviceId",
                    outputDirectory,
                    mockCoverageOutputDir,
                    mockAdditionalTestOutputDir,
                    "projectPath",
                    "variantName",
                    mockTestData,
                    listOf(),
                    setOf(mockHelperApk),
                    mockLogger,
                    sdkApkSet
                )
            }
        }
    }

    private fun createTestSuiteResult(
        hasEmulatorTimeoutException: Boolean = false
    ): TestSuiteResult {
        return TestSuiteResult.newBuilder().apply {
            if (hasEmulatorTimeoutException) {
                platformErrorBuilder.apply {
                    addErrorsBuilder().apply {
                        causeBuilder.apply {
                            summaryBuilder.apply {
                                stackTrace = "EmulatorTimeoutException"
                            }
                        }
                    }
                }
            }
        }.build()
    }

    @Test
    fun runUtpAndPassed() {
        val result = runUtp(result = true)

        assertThat(utpInvocationCount).isEqualTo(1)
        assertThat(capturedRunnerConfigs).hasSize(1)

        assertThat(result).isTrue()
        assertThat(File(outputDirectory, TEST_RESULT_PB_FILE_NAME)).exists()
    }

    @Test
    fun runUtpAndFailed() {
        val result = runUtp(result = false)

        assertThat(utpInvocationCount).isEqualTo(1)
        assertThat(capturedRunnerConfigs).hasSize(1)
        assertThat(result).isFalse()
    }

    @Test
    fun runUtpWithShardsAndPassed() {
        val result = runUtp(result = true, numShards = 2)

        assertThat(capturedRunnerConfigs).hasSize(2)
        assertThat(capturedRunnerConfigs[0].shardConfig).isEqualTo(ShardConfig(2, 0))
        assertThat(capturedRunnerConfigs[1].shardConfig).isEqualTo(ShardConfig(2, 1))

        assertThat(result).isTrue()
        assertThat(File(outputDirectory, TEST_RESULT_PB_FILE_NAME)).exists()
    }
}
