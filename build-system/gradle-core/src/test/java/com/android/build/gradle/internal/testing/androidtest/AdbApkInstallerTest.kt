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

import com.google.common.truth.Truth.assertThat
import org.gradle.api.logging.Logger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.File
import kotlin.test.assertFailsWith

/**
 * Unit tests for [AdbApkInstaller].
 */
class AdbApkInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var adb: File
    private lateinit var aapt: File
    private lateinit var apk1: File
    private lateinit var apk2: File

    // A map where the key is a command identifier and the value is a queue of mock processes for it.
    private val mockProcessMap = mutableMapOf<String, ArrayDeque<Process>>()
    private val executedCommands = mutableMapOf<String, MutableList<String>>()
    private val mockLogger: Logger = mock()

    @Before
    fun setUp() {
        adb = tempFolder.newFile("adb")
        aapt = tempFolder.newFile("aapt")
        apk1 = tempFolder.newFile("app1.apk")
        apk2 = tempFolder.newFile("app2.apk")

        // Default mock for user ID check, which is often called.
        mockCommand("am get-current-user", exitCode = 0, output = "0")
    }

    /**
     * Creates the test subject with a process builder that uses the mock map.
     */
    private fun createHelper(
        deviceApiLevel: Int,
        installTimeoutMs: Long = 60000L
    ): AdbApkInstaller {
        return AdbApkInstaller(
            adb = adb,
            aapt = aapt,
            deviceSerial = "test-serial",
            deviceApiLevel = deviceApiLevel,
            installTimeoutMs = installTimeoutMs,
            logger = mockLogger
        ) { command ->
            val commandKey = getCommandKey(command)
            executedCommands.getOrPut(commandKey) { mutableListOf() }.add(command.joinToString(" "))
            val processQueue = mockProcessMap[commandKey]
                ?: throw AssertionError(
                    "No mock process found for command key: '$commandKey' (command: ${command.joinToString(" ")})"
                )
            val process = processQueue.removeFirstOrNull()
                ?: throw AssertionError("Mock process queue for command key '$commandKey' is empty.")

            mock {
                on { start() } doReturn process
            }
        }
    }

    /**
     * Determines a unique key for a given command list.
     */
    private fun getCommandKey(command: List<String>): String {
        val commandString = command.joinToString(" ")
        return when {
            commandString.contains("aapt dump badging") -> "aapt dump badging"
            commandString.contains("am get-current-user") -> "am get-current-user"
            commandString.contains("settings put global") -> "settings put global"
            commandString.contains("am set-debug-app") -> "am set-debug-app"
            commandString.contains("am clear-debug-app") -> "am clear-debug-app"
            commandString.contains("cmd package compile") -> "cmd package compile"
            commandString.contains("uninstall") -> "uninstall"
            commandString.contains("install-multiple") -> "install-multiple"
            commandString.contains("install") -> "install"
            else -> throw IllegalArgumentException(
                "Unknown command to generate key for: $commandString"
            )
        }
    }

    /**
     * Queues a mock process to be returned when a command with the given key is executed.
     */
    private fun mockCommand(
        commandKey: String,
        exitCode: Int,
        output: String = "",
        error: String = ""
    ) {
        val process = mock<Process> {
            on { it.exitValue() } doReturn exitCode
            on { it.inputStream } doReturn output.byteInputStream()
            on { it.errorStream } doReturn error.byteInputStream()
            on { it.waitFor(any(), any()) } doReturn true
        }
        mockProcessMap.getOrPut(commandKey) { ArrayDeque() }.add(process)
    }

    private fun mockAapt(packageName: String) {
        mockCommand("aapt dump badging", 0, "package: name='$packageName' versionCode='1'")
    }

    @Test
    fun `installApk success`() {
        val helper = createHelper(deviceApiLevel = 30)
        mockCommand("install", exitCode = 0, output = "Success")

        helper.installApk(apk1, AdbApkInstaller.InstallOptions())

        val executed = executedCommands["install"]?.first()
        assertThat(executed).contains("-t") // Test-only flag should be present.
        assertThat(executed).contains(apk1.absolutePath)
    }

    @Test
    fun `installApk failure throws RuntimeException`() {
        val helper = createHelper(deviceApiLevel = 30)
        mockCommand("install", exitCode = 1, error = "Failure: INSTALL_FAILED")

        val exception = assertFailsWith<RuntimeException> {
            helper.installApk(apk1, AdbApkInstaller.InstallOptions())
        }
        assertThat(exception).hasMessageThat().contains("Failed to install APK")
        assertThat(exception).hasMessageThat().contains("Failure: INSTALL_FAILED")
    }

    @Test
    fun `installApk with all options on API 34`() {
        val helper = createHelper(deviceApiLevel = 34)
        mockCommand("install", exitCode = 0)
        // Mock the user-id call, which is lazy loaded.
        mockCommand("am get-current-user", exitCode = 0, output = "0")


        val options = AdbApkInstaller.InstallOptions(
            grantPermissions = true,
            forceQueryable = true,
            forceReinstall = true,
            extraArgs = listOf("--some-extra-arg")
        )
        helper.installApk(apk1, options)

        val executed = executedCommands["install"]?.first()!!
        assertThat(executed).contains("--bypass-low-target-sdk-block")
        assertThat(executed).contains("-t -r -d -g --force-queryable")
        assertThat(executed).contains("--user 0")
        assertThat(executed).contains("--some-extra-arg")
        assertThat(executed).endsWith(apk1.absolutePath)
    }

    @Test
    fun `installApk options on unsupported API are ignored`() {
        val helper = createHelper(deviceApiLevel = 22)
        mockCommand("install", exitCode = 0)

        // grantPermissions requires API 23, forceQueryable requires API 30.
        val options = AdbApkInstaller.InstallOptions(
            grantPermissions = true,
            forceQueryable = true
        )
        helper.installApk(apk1, options)

        val executed = executedCommands["install"]?.first()!!
        assertThat(executed).doesNotContain("-g")
        assertThat(executed).doesNotContain("--force-queryable")
    }

    @Test
    fun `installApk with full compilation triggers compile command`() {
        val helper = createHelper(deviceApiLevel = 30)
        mockCommand("install", exitCode = 0)
        mockAapt("com.example.app")
        mockCommand("cmd package compile", exitCode = 0)

        val options = AdbApkInstaller.InstallOptions(
            forceCompilation = AdbApkInstaller.ForceCompilation.FULL_COMPILATION
        )
        helper.installApk(apk1, options)

        val executed = executedCommands["cmd package compile"]?.first()!!
        assertThat(executed).contains("-m speed -f com.example.app")
    }

    @Test
    fun `installSplitApk success`() {
        val helper = createHelper(deviceApiLevel = 28)
        mockCommand("install-multiple", exitCode = 0)

        helper.installSplitApk(listOf(apk1, apk2), AdbApkInstaller.InstallOptions())

        val executed = executedCommands["install-multiple"]?.first()!!
        assertThat(executed).contains(apk1.absolutePath)
        assertThat(executed).contains(apk2.absolutePath)
    }

    @Test
    fun `installSplitApk api too low throws exception`() {
        val helper = createHelper(deviceApiLevel = 20) // Min is 21

        val exception = assertFailsWith<RuntimeException> {
            helper.installSplitApk(listOf(apk1), AdbApkInstaller.InstallOptions())
        }
        assertThat(exception).hasMessageThat().contains("Split APK installation requires API level 21")
    }

    @Test
    fun `installSplitApk no apks throws exception`() {
        val helper = createHelper(deviceApiLevel = 28)
        val exception = assertFailsWith<IllegalStateException> {
            helper.installSplitApk(emptyList(), AdbApkInstaller.InstallOptions())
        }
        assertThat(exception).hasMessageThat().isEqualTo("No APKs provided for installation.")
    }

    @Test
    fun `uninstallApk success finds package and uninstalls`() {
        val helper = createHelper(deviceApiLevel = 30)
        mockAapt("com.example.app")
        mockCommand("uninstall", exitCode = 0)

        helper.uninstallApk(apk1)

        val executedAapt = executedCommands["aapt dump badging"]?.first()!!
        assertThat(executedAapt).contains(apk1.absolutePath)

        val executedUninstall = executedCommands["uninstall"]?.first()!!
        assertThat(executedUninstall).contains("uninstall com.example.app")
    }

    @Test
    fun `uninstallApk aapt fails does not uninstall`() {
        val helper = createHelper(deviceApiLevel = 30)
        mockCommand("aapt dump badging", exitCode = 1, error = "Failed to parse")

        helper.uninstallApk(apk1)

        assertThat(executedCommands).doesNotContainKey("uninstall")
        verify(mockLogger).warn("Could not get package name from ${apk1.path} to uninstall.")
    }

    @Test
    fun `preInstallationSetup api 35 sets verifier and debug app`() {
        val helper = createHelper(deviceApiLevel = 35)
        mockCommand("settings put global", exitCode = 0)
        mockCommand("am set-debug-app", exitCode = 0)

        helper.preInstallationSetup("com.example.target")

        val executedSettings = executedCommands["settings put global"]?.first()!!
        assertThat(executedSettings).contains("settings put global verifier_verify_adb_installs 0")

        val executedDebug = executedCommands["am set-debug-app"]?.first()!!
        assertThat(executedDebug).contains("am set-debug-app com.example.target")
    }

    @Test
    fun `preInstallationSetup api 33 sets verifier only`() {
        val helper = createHelper(deviceApiLevel = 33)
        mockCommand("settings put global", exitCode = 0)

        helper.preInstallationSetup("com.example.target")

        assertThat(executedCommands).containsKey("settings put global")
        assertThat(executedCommands).doesNotContainKey("am set-debug-app")
    }

    @Test
    fun `preInstallationSetup api 32 does nothing`() {
        val helper = createHelper(deviceApiLevel = 32)
        helper.preInstallationSetup("com.example.target")
        assertThat(executedCommands).isEmpty()
    }

    @Test
    fun `postTestCleanup runs clear debug app`() {
        val helper = createHelper(deviceApiLevel = 30)
        mockCommand("am clear-debug-app", exitCode = 0)

        helper.postTestCleanup()

        val executed = executedCommands["am clear-debug-app"]?.first()!!
        assertThat(executed).contains("am clear-debug-app")
    }

    @Test
    fun `userId is lazy loaded and cached`() {
        mockProcessMap["am get-current-user"]?.clear()
        mockCommand("am get-current-user", exitCode = 0, output = "10")
        mockCommand("install", exitCode = 0)
        mockCommand("install", exitCode = 0)

        val helper = createHelper(deviceApiLevel = 24)

        // First call should trigger "am get-current-user"
        helper.installApk(apk1, AdbApkInstaller.InstallOptions())
        assertThat(executedCommands["am get-current-user"]).hasSize(1)
        var executedInstall = executedCommands["install"]?.first()!!
        assertThat(executedInstall).contains("--user 10")

        // Second call should use the cached value
        helper.installApk(apk2, AdbApkInstaller.InstallOptions())
        // The count should still be 1, proving it was cached.
        assertThat(executedCommands["am get-current-user"]).hasSize(1)
        executedInstall = executedCommands["install"]?.get(1)!!
        assertThat(executedInstall).contains("--user 10")
    }
}
