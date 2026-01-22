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
import com.android.build.gradle.internal.utils.setDisallowChanges
import com.android.mockito.kotlin.whenever
import com.android.testutils.SystemPropertyOverrides
import com.android.tools.utp.gradle.api.EmulatorControlConfig
import com.android.tools.utp.gradle.api.RunUtpWorkParameters.UtpRunConfig
import com.android.tools.utp.gradle.api.ShardConfig
import com.android.tools.utp.gradle.api.UtpDependencies
import com.android.utils.Environment
import com.google.common.truth.Truth.assertThat
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
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.io.path.Path
import kotlin.reflect.jvm.javaMethod

/**
 * Unit tests for [ManagedDeviceTestRunner].
 */
class ManagedDeviceTestRunnerTest {
    @get:Rule var temporaryFolderRule = TemporaryFolder()

    private val mockWorkerExecutor: WorkerExecutor = mock()
    private val mockObjectFactory: ObjectFactory = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    private val mockVersionedSdkLoader: SdkComponentsBuildService.VersionedSdkLoader = mock()
    private val mockAvdComponents: AvdComponentsBuildService = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    private val mockTestData: StaticTestData = mock()
    private val mockAppApk: File = mock()
    private val mockHelperApk: File = mock()
    private val mockLogger: Logger = mock()
    private val mockEmulatorControlConfig: EmulatorControlConfig = mock()
    private val mockCoverageOutputDir: File = mock()
    private val mockAdditionalTestOutputDir: File = mock()
    private val mockDslDevice: ManagedVirtualDevice = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    private val mockUtpDependencies: UtpDependencies = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    private val emulatorProvider: Provider<Directory> = mock()
    private val emulatorDirectory: Directory = mock()
    private val avdProvider: Provider<Directory> = mock()
    private val avdDirectory: Directory = mock()
    private lateinit var emulatorFolder: File
    private lateinit var avdFolder: File
    private lateinit var outputDirectory: File

    private val extractedSdkApks = listOf(listOf(Path("test1"), Path("test2")))
    private val sdkApkSet = setOf(File("test"))

    private val runnerConfigsCaptor = argumentCaptor<List<UtpRunConfig>>()

    @Before
    fun setupMocks() {
        Environment.initialize()

        whenever(mockObjectFactory.newInstance(
            eq(UtpRunConfig::class.java))
        ).thenReturn(mock(defaultAnswer = RETURNS_DEEP_STUBS))

        whenever(mockTestData.applicationId).thenReturn("applicationId")
        whenever(mockTestData.instrumentationRunner).thenReturn("instrumentationRunner")
        whenever(mockTestData.instrumentationTargetPackageId).thenReturn("instrumentationTargetPackageId")
        whenever(mockTestData.testApk).thenReturn(mockAppApk)
        whenever(mockTestData.minSdkVersion).thenReturn(AndroidVersionImpl(28))
        whenever(mockTestData.testedApkFinder).thenReturn { listOf(mockAppApk) }
        whenever(mockTestData.privacySandboxInstallBundlesFinder).thenReturn { extractedSdkApks }

        whenever(mockDslDevice.pageAlignmentSuffix).thenReturn("")

        whenever(mockAvdComponents.runWithAvds(
            any(), any(),
            any<(List<String>) -> Boolean>())).then {
            val desiredDeviceCount = it.getArgument<Int>(1)
            val deviceSerials = List(desiredDeviceCount) { "mockDeviceSerial_$it" }
            val onDevicesReadyFunc = it.getArgument<(List<String>)->Boolean>(2)
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
        results: Boolean,
        numShards: Int? = null,
    ): Boolean {
        return runInLinuxEnvironment {
            val runner = ManagedDeviceTestRunner(
                mockWorkerExecutor,
                mockObjectFactory,
                mockUtpDependencies,
                mockVersionedSdkLoader,
                mockEmulatorControlConfig,
                useOrchestrator = false,
                forceCompilation = false,
                numShards,
                mockAvdComponents,
                null,
                false,
                false,
            )

            outputDirectory = temporaryFolderRule.newFolder("results")

            mockStatic(
                ::runUtpTestSuiteAndWait.javaMethod!!.declaringClass,
                Answers.CALLS_REAL_METHODS).use { mockedStatic ->
                mockedStatic.whenever<Boolean> {
                    runUtpTestSuiteAndWait(
                        runnerConfigsCaptor.capture(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }.thenReturn(results)

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

    @Test
    fun runUtpAndPassed() {
        val result = runUtp(results = true)

        assertThat(runnerConfigsCaptor.allValues).hasSize(1)
        assertThat(runnerConfigsCaptor.firstValue).hasSize(1)
        assertThat(result).isTrue()
    }

    @Test
    fun runUtpAndFailed() {
        val result = runUtp(results = false)

        assertThat(runnerConfigsCaptor.allValues).hasSize(1)
        assertThat(runnerConfigsCaptor.firstValue).hasSize(1)
        assertThat(result).isFalse()
    }

    @Test
    fun runUtpWithShardsAndPassed() {
        val result = runUtp(
            results = true,
            numShards = 2,
        )

        assertThat(runnerConfigsCaptor.allValues).hasSize(1)
        assertThat(runnerConfigsCaptor.firstValue).hasSize(2)
        verify(runnerConfigsCaptor.firstValue[0].shardConfig).setDisallowChanges(eq(ShardConfig(2, 0)))
        verify(runnerConfigsCaptor.firstValue[1].shardConfig).setDisallowChanges(eq(ShardConfig(2, 1)))

        assertThat(result).isTrue()
    }
}
