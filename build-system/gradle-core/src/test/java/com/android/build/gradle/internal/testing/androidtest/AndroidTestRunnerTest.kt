/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.internal.testing.androidtest

import com.android.build.gradle.internal.testing.androidtest.AdbApkInstaller.InstallOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Unit tests for the [AndroidTestRunner] class.
 */
class AndroidTestRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var adbApkInstaller: AdbApkInstaller

    private lateinit var baseApk: File
    private lateinit var splitApk1: File
    private lateinit var utilApk: File

    @Before
    fun setUp() {
        baseApk = tempFolder.newFile("base.apk")
        splitApk1 = tempFolder.newFile("split1.apk")
        utilApk = tempFolder.newFile("util.apk")
    }

    @Test
    fun `run with single APK installs and uninstalls correctly`() {
        // Given a runner for a single "tested" APK with uninstallation enabled.
        val installOptions = listOf("-t", "-d")
        val runner = AndroidTestRunner(
            adbApkInstaller = adbApkInstaller,
            testedApks = listOf(baseApk),
            apkInstallOptions = installOptions,
            testUtilApks = emptyList(),
            uninstallApksAfterTests = true
        )
        val expectedInstallOptions = InstallOptions(extraArgs = installOptions)

        // When the runner is executed.
        runner.run()

        // Then verify the sequence of operations is correct: install, cleanup, uninstall.
        inOrder(adbApkInstaller) {
            verify(adbApkInstaller).installApk(baseApk, expectedInstallOptions)
            verify(adbApkInstaller).postTestCleanup()
            verify(adbApkInstaller).uninstallApk(baseApk)
        }
    }

    @Test
    fun `run with split APKs installs and uninstalls correctly`() {
        // Given a runner for multiple "tested" (split) APKs with uninstallation enabled.
        val testedApks = listOf(baseApk, splitApk1)
        val installOptions = listOf("-g")
        val runner = AndroidTestRunner(
            adbApkInstaller = adbApkInstaller,
            testedApks = testedApks,
            apkInstallOptions = installOptions,
            testUtilApks = emptyList(),
            uninstallApksAfterTests = true
        )
        val expectedInstallOptions = InstallOptions(extraArgs = installOptions)

        // When the runner is executed.
        runner.run()

        // Then verify the runner uses installSplitApk and uninstalls the base APK.
        inOrder(adbApkInstaller) {
            verify(adbApkInstaller).installSplitApk(testedApks, expectedInstallOptions)
            verify(adbApkInstaller).postTestCleanup()
            // The current implementation only uninstalls the first APK in the list.
            verify(adbApkInstaller).uninstallApk(baseApk)
        }
    }

    @Test
    fun `run with util APKs installs and uninstalls all APKs`() {
        // Given a runner with a base APK and a utility APK.
        val runner = AndroidTestRunner(
            adbApkInstaller = adbApkInstaller,
            testedApks = listOf(baseApk),
            apkInstallOptions = emptyList(),
            testUtilApks = listOf(utilApk),
            uninstallApksAfterTests = true
        )
        val expectedUtilApkOptions = InstallOptions(grantPermissions = true, forceQueryable = true)

        // When the runner is executed.
        runner.run()

        // Then verify all APKs are installed and then uninstalled in the correct order.
        inOrder(adbApkInstaller) {
            verify(adbApkInstaller).installApk(eq(baseApk), any())
            verify(adbApkInstaller).installApk(utilApk, expectedUtilApkOptions)
            verify(adbApkInstaller).postTestCleanup()
            verify(adbApkInstaller).uninstallApk(baseApk)
            verify(adbApkInstaller).uninstallApk(utilApk)
        }
    }

    @Test
    fun `run with uninstall set to false does not uninstall APKs`() {
        // Given a runner where uninstallation is explicitly disabled.
        val runner = AndroidTestRunner(
            adbApkInstaller = adbApkInstaller,
            testedApks = listOf(baseApk),
            apkInstallOptions = emptyList(),
            testUtilApks = emptyList(),
            uninstallApksAfterTests = false // Key condition for this test
        )

        // When the runner is executed.
        runner.run()

        // Then verify that installation and cleanup occur.
        verify(adbApkInstaller).installApk(eq(baseApk), any())
        verify(adbApkInstaller).postTestCleanup()
        // Crucially, verify that uninstallApk is never called.
        verify(adbApkInstaller, never()).uninstallApk(any())
    }

    @Test
    fun `run when installation fails still performs cleanup and uninstall`() {
        // Given a runner where the installer will throw an error during installation.
        val runner = AndroidTestRunner(
            adbApkInstaller = adbApkInstaller,
            testedApks = listOf(baseApk),
            apkInstallOptions = emptyList(),
            testUtilApks = emptyList(),
            uninstallApksAfterTests = true
        )
        val installException = RuntimeException("Install failed!")
        whenever(adbApkInstaller.installApk(eq(baseApk), any())).thenThrow(installException)

        // When the runner is executed, expect it to fail.
        try {
            runner.run()
            fail("Expected exception was not thrown")
        } catch (e: RuntimeException) {
            assertEquals("The original exception should be propagated.", installException, e)
        }

        // Then verify that the 'finally' block logic (cleanup and uninstall) still ran.
        inOrder(adbApkInstaller) {
            verify(adbApkInstaller).postTestCleanup()
            verify(adbApkInstaller).uninstallApk(baseApk)
        }
    }

    @Test
    fun `run with no APKs only performs cleanup`() {
        // Given a runner with no APKs to install.
        val runner = AndroidTestRunner(
            adbApkInstaller = adbApkInstaller,
            testedApks = emptyList(),
            apkInstallOptions = emptyList(),
            testUtilApks = emptyList(),
            uninstallApksAfterTests = true
        )

        // When the runner is executed.
        runner.run()

        // Then verify that only post-test cleanup was called.
        verify(adbApkInstaller).postTestCleanup()
        // Verify no install or uninstall attempts were made.
        verify(adbApkInstaller, never()).installApk(any(), any())
        verify(adbApkInstaller, never()).installSplitApk(any(), any())
        verify(adbApkInstaller, never()).uninstallApk(any())
    }
}
