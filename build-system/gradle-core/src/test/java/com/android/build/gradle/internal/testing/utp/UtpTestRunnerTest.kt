/*
 * Copyright (C) 2020 The Android Open Source Project
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
import com.android.build.gradle.internal.SdkComponentsBuildService
import com.android.build.gradle.internal.testing.AdbHelper
import com.android.build.gradle.internal.testing.StaticTestData
import com.android.builder.testing.api.DeviceConnector
import com.android.mockito.kotlin.whenever
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.utp.gradle.api.RunUtpWorkParameters
import com.google.common.truth.Truth.assertThat
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.workers.WorkerExecutor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.reflect.jvm.javaMethod

/**
 * Unit tests for [UtpTestRunner].
 */
class UtpTestRunnerTest {
    @get:Rule val temporaryFolderRule = TemporaryFolder()

    private val mockWorkerExecutor: WorkerExecutor = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    private val mockObjectFactory: ObjectFactory = mock()
    private val mockVersionedSdkLoader: SdkComponentsBuildService.VersionedSdkLoader = mock()
    private val mockAdbHelper: AdbHelper = mock()
    private val mockTestData: StaticTestData = mock()
    private val mockAppApk: File = mock()
    private val mockDevice: DeviceConnector = mock()

    private lateinit var resultsDirectory: File
    private lateinit var jvmExecutable: File

    private val runnerConfigsCaptor = argumentCaptor<List<RunUtpWorkParameters.UtpRunConfig>>()

    @Before
    fun setupMocks() {
        jvmExecutable = temporaryFolderRule.newFile()

        whenever(mockObjectFactory.newInstance(
            eq(RunUtpWorkParameters.UtpRunConfig::class.java))
        ).thenReturn(mock(defaultAnswer = RETURNS_DEEP_STUBS))

        whenever(mockDevice.name).thenReturn("mockDeviceName")
        whenever(mockDevice.serialNumber).thenReturn("mockDeviceSerialNumber")
        whenever(mockDevice.apiLevel).thenReturn(28)
        whenever(mockTestData.minSdkVersion).thenReturn(AndroidVersionImpl(28))
        whenever(mockTestData.testedApkFinder).thenReturn { listOf(mockAppApk) }
        whenever(mockTestData.privacySandboxInstallBundlesFinder).thenReturn { emptyList() }

        val adbHelperProvider: Provider<AdbHelper> = mock()
        whenever(adbHelperProvider.get()).thenReturn(mockAdbHelper)
        whenever(mockVersionedSdkLoader.adbHelper).thenReturn(adbHelperProvider)
    }

    private fun runUtp(result: UtpTestRunResult): Boolean {
        val runner = UtpTestRunner(
            mock(),
            mockWorkerExecutor,
            mockObjectFactory,
            mock(),
            mock(),
            mockVersionedSdkLoader,
            mock(),
            useOrchestrator = false,
            forceCompilation = false,
            uninstallIncompatibleApks = false,
            null,
            false,
            false,
        )

        resultsDirectory = temporaryFolderRule.newFolder("results")

        mockStatic(::runUtpTestSuiteAndWait.javaMethod!!.declaringClass).use { mockedStatic ->
            mockedStatic.whenever<List<UtpTestRunResult>> {
                runUtpTestSuiteAndWait(runnerConfigsCaptor.capture(), any(), any(), any(), any(), any(), any(), any())
            }.thenReturn(listOf(result))

            return runner.runTests(
                "projectName",
                "variantName",
                mockTestData,
                setOf(mock()),
                setOf(mock()),
                listOf(mockDevice),
                0,
                setOf(),
                resultsDirectory,
                false,
                null,
                temporaryFolderRule.newFolder("coverageDir"),
                mock(),
            )
        }
    }

    @Test
    fun runUtpAndPassed() {
        val result = runUtp(UtpTestRunResult(testPassed = true, TestSuiteResult.getDefaultInstance()))

        assertThat(runnerConfigsCaptor.firstValue).hasSize(1)
        assertThat(result).isTrue()
        assertThat(File(resultsDirectory, TEST_RESULT_PB_FILE_NAME)).exists()
    }

    @Test
    fun runUtpAndFailed() {
        val result = runUtp(UtpTestRunResult(testPassed = false, null))

        assertThat(runnerConfigsCaptor.firstValue).hasSize(1)
        assertThat(result).isFalse()
    }

    @Test
    fun runTestsFiltersManagedDevices() {
        // Ensure all devices are determined to be managed devices.
        whenever(mockAdbHelper.isManagedDevice(any(), any())).thenReturn(true)

        val result = runUtp(UtpTestRunResult(testPassed = true, TestSuiteResult.getDefaultInstance()))

        // Since the only available devices will only be managed devices, we expect no tests to
        // be run.
        assertThat(runnerConfigsCaptor.firstValue).hasSize(0)
        assertThat(result).isTrue()
    }
}
