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

package com.android.tools.journeys.testengine.robo

import java.util.concurrent.TimeUnit

/**
 * A class for interacting with the Android Debug Bridge (ADB).
 *
 * @param adbPath The path to the ADB executable.
 * @param deviceId The ID of the target device.
 */
class Adb(
    private val adbPath: String,
    private val deviceId: String
) {

    /**
     * Installs an APK file on the device.
     *
     * @param apkFilePath The APK file path to install.
     * @param flags Additional flags to pass to the `adb install` command (e.g., "-r", "-g").
     * @param timeoutSeconds The maximum time to wait for the installation to complete.
     */
    fun install(
        apkFilePath: String,
        flags: List<String> = listOf(),
        timeoutSeconds: Long = Long.MAX_VALUE
    ) {
        execCmdSync(
            adbCmdParts = buildList {
                add(adbPath)
                add("-s")
                add(deviceId)
                add("install")
                addAll(flags)
                add(apkFilePath)
            },
            timeoutSeconds = timeoutSeconds,
            hasFailed = {
                exitValue != 0 || !fullOut.contains("Success")
            }
        )
    }

    /**
     * Uninstalls an application from the device.
     * Checks if the app is installed before attempting uninstallation.
     *
     * @param applicationId The package name of the application to uninstall.
     */
    fun uninstall(applicationId: String) {
        val isAppInstalled = execCmdSync(
            adbCmd = "shell pm path $applicationId",
            hasFailed = { false }
        ).fullOut.contains("package:")
        if (isAppInstalled) {
            execCmdSync(adbCmd = "uninstall $applicationId")
        }
    }

    /**
     * Obtains device API level
     *
     * @return Device API level.
     */
    fun getDeviceApiLevel(): Int {
        val output = execCmdSync(
            adbCmd = "shell getprop ro.build.version.sdk",
            hasFailed = { stdout.trim().toIntOrNull() == null }
        ).stdout
        return output.trim().toInt()
    }

    /**
     * Runs an instrumentation process on the device asynchronously.
     *
     * Starts the `am instrument` command but returns the [Process] object immediately.
     * The caller is responsible for managing this process, including waiting for completion,
     * handling streams (stdout/stderr), and destroying the process if necessary.
     *
     * @param testApplicationId The package name of the application containing the test runner.
     * @param runner The fully qualified name of the test runner.
     * @param args A map of arguments to pass to the test runner using `-e key value`.
     * @return The [Process] object representing the running instrumentation. The caller owns this process.
     */
    fun runInstrumentation(
        testApplicationId: String,
        runner: String,
        args: Map<String, String> = mapOf(),
    ): Process {
        val argsStr = args.entries.joinToString(separator = " ") { "-e ${it.key} ${it.value}" }
        val cmd = buildAdbCommand("shell am instrument -w $argsStr $testApplicationId/$runner")
        val cmdParts = cmd.trim().split(Regex("\\s+"))
        return ProcessBuilder(cmdParts).start()
    }

    /**
     * Forwards a host port to a device port using `adb forward`.
     *
     * @param hostPort The port on the host machine.
     * @param devicePort The port on the device.
     */
    fun forward(hostPort: Int, devicePort: Int) {
        execCmdSync(
            adbCmd = "forward tcp:$hostPort tcp:$devicePort",
            hasFailed = { exitValue != 0 || !fullOut.contains("$hostPort") }
        )
    }

    /**
     * Removes a specific port forwarding rule using `adb forward --remove`.
     * Checks if the forwarding rule exists before attempting removal.
     *
     * @param hostPort The host port of the forwarding rule to remove.
     */
    fun removeForward(hostPort: Int) {
        val forwardedPorts = execCmdSync(adbCmd = "forward --list").fullOut
        if (forwardedPorts.contains("tcp:$hostPort")) {
            execCmdSync(adbCmd = "forward --remove tcp:$hostPort")
        }
    }

    /**
     * Executes 'dumpsys activity service <serviceIdentifier>' on the device.
     *
     * @param serviceIdentifier The fully qualified service name or identifier for `dumpsys`.
     * @param timeoutSeconds The maximum time to wait for the command to complete.
     * @return The standard output of the dumpsys command.
     */
    fun dumpsys(serviceIdentifier: String, timeoutSeconds: Long = 10): String {
        return execCmdSync(
            adbCmd = "shell dumpsys activity service $serviceIdentifier",
            timeoutSeconds = timeoutSeconds
        ).fullOut
    }

    /**
     * Executes 'shell settings put global [key] [value]' on the device.
     */
    fun setGlobalSettingsValue(key: String, value: String, timeoutSeconds: Long = 10) {
        execCmdSync(
            adbCmd = "shell settings put global $key $value",
            timeoutSeconds = timeoutSeconds
        )
    }

    /**
     * Executes an ADB command synchronously, handling process execution, timeout,
     * stream reading, and result validation.
     *
     * @param adbCmd The specific ADB command.
     * @param timeoutSeconds The maximum time to wait for the process to finish.
     * @param hasFailed A lambda with [ProcessResult] as receiver to determine if the execution failed.
     * Defaults to checking if the exit value is non-zero.
     * @param crashIfTimeout If true, throws [IllegalStateException] on timeout; otherwise, proceeds.
     * @return A [ProcessResult] containing exit code, stdout, and stderr.
     * @throws IllegalStateException if the command times out (and [crashIfTimeout] is true)
     * or if the [hasFailed] predicate returns true.
     */
    private fun execCmdSync(
        adbCmd: String,
        timeoutSeconds: Long = 30,
        hasFailed: ProcessResult.() -> (Boolean) = { exitValue != 0 },
        crashIfTimeout: Boolean = true
    ): ProcessResult {
        val cmd = buildAdbCommand(adbCmd)
        val cmdParts = cmd.trim().split(Regex("\\s+"))
        return execCmdSync(cmdParts, timeoutSeconds, hasFailed, crashIfTimeout)
    }

    /**
     * Executes an ADB command synchronously, handling process execution, timeout,
     * stream reading, and result validation.
     *
     * @param adbCmdParts The adb command split into parts which can be directly fed to ProcessBuilder.
     * @param timeoutSeconds The maximum time to wait for the process to finish.
     * @param hasFailed A lambda with [ProcessResult] as receiver to determine if the execution failed.
     * Defaults to checking if the exit value is non-zero.
     * @param crashIfTimeout If true, throws [IllegalStateException] on timeout; otherwise, proceeds.
     * @return A [ProcessResult] containing exit code, stdout, and stderr.
     * @throws IllegalStateException if the command times out (and [crashIfTimeout] is true)
     * or if the [hasFailed] predicate returns true.
     */
    private fun execCmdSync(
        adbCmdParts: List<String>,
        timeoutSeconds: Long = 30,
        hasFailed: ProcessResult.() -> (Boolean) = { exitValue != 0 },
        crashIfTimeout: Boolean = true
    ): ProcessResult {
        val process = ProcessBuilder(adbCmdParts).start()
        val waitFor = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!waitFor && crashIfTimeout) {
            throw IllegalStateException("Timeout waiting for ${adbCmdParts.joinToString(" ")}")
        }

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val fullOutputForLog = StringBuilder()
            .apply {
                if (stdout.isNotBlank()) append("------ stdout ------\n${stdout}")
                if (stderr.isNotBlank()) append("------ stderr ------\n${stderr}")
            }
            .toString()
        val processResult = ProcessResult(process.exitValue(), stdout, stderr)
        if (hasFailed(processResult)) {
            throw IllegalStateException(
                "Command `${adbCmdParts.joinToString(" ")}` failed (exit code ${processResult.exitValue}) with output:\n$fullOutputForLog"
            )
        }
        return processResult
    }

    /**
     * Builds the complete ADB command string including the path and target device.
     *
     * @param adbCmd The specific ADB command portion.
     * @return The complete command string for execution.
     */
    private fun buildAdbCommand(adbCmd: String): String {
        return "$adbPath -s $deviceId $adbCmd"
    }
}

/**
 * Data class representing the result of a process execution.
 *
 * @param exitValue The exit value of the process.
 * @param stdout The standard output captured from the process.
 * @param stderr The standard error captured from the process.
 */
internal data class ProcessResult(val exitValue: Int, val stdout: String, val stderr: String) {
    val fullOut = "$stdout\n$stderr"
}
