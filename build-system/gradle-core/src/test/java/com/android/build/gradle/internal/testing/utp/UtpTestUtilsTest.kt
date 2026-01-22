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
import com.android.build.gradle.internal.testing.utp.worker.RunUtpWorkAction
import com.android.tools.utp.gradle.api.RunUtpWorkParameters
import com.android.tools.utp.gradle.api.UtpDependencies
import com.google.common.truth.Truth.assertThat
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
import java.io.File

private const val TEST_RESULT_EXIT_CODE_FILE_NAME = "test-result-exit-code.txt"

/**
 * Unit tests for UtpTestUtils.kt.
 */
class UtpTestUtilsTest {
    @get:Rule
    val temporaryFolderRule = TemporaryFolder()

    private val mockUtpDependencies: UtpDependencies = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    private val mockWorkerExecutor: WorkerExecutor = mock()
    private val mockVersionedSdkLoader: SdkComponentsBuildService.VersionedSdkLoader = mock()
    private val mockWorkQueue: WorkQueue = mock()

    @Before
    fun setupMocks() {
        whenever(mockWorkerExecutor.classLoaderIsolation(any()))
            .thenReturn(mockWorkQueue)
    }

    private fun runUtp(
        expectedResultCode: Int = 0,
    ): Boolean {
        val utpResultDir = temporaryFolderRule.newFolder()

        val config: RunUtpWorkParameters.UtpRunConfig = mock(defaultAnswer = RETURNS_DEEP_STUBS)
        whenever(config.utpResultProtoOutputFile.asFile.get()).thenReturn(File(utpResultDir, TEST_RESULT_EXIT_CODE_FILE_NAME))

        whenever(mockWorkQueue.submit(eq(RunUtpWorkAction::class.java), any())).then {
            File(utpResultDir, TEST_RESULT_EXIT_CODE_FILE_NAME)
                .writeBytes(expectedResultCode.toString().toByteArray())
        }

        return runUtpTestSuiteAndWait(
            listOf(config),
            mockWorkerExecutor,
            "projectName",
            "variantName",
            utpResultDir,
            mockUtpDependencies,
            mockVersionedSdkLoader,
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
}
