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

package com.android.tools.journeys.testengine.robo.platform

import java.util.concurrent.TimeUnit

/**
 * Data class representing the result of a process execution.
 *
 * @param exitValue The exit value of the process.
 * @param stdout The standard output captured from the process.
 * @param stderr The standard error captured from the process.
 */
data class ProcessResult(val exitValue: Int, val stdout: String, val stderr: String) {
    val fullOut = "$stdout\n$stderr"
}

/**
 * An interface for executing system processes.
 * This abstraction allows for easier testing of classes that interact with external commands.
 */
interface ProcessExecutor {
    /**
     * Executes a command synchronously and returns the result.
     *
     * @param cmdParts The command and its arguments as a list of strings.
     * @param timeoutSeconds The maximum time to wait for the command to complete.
     * @return A [ProcessResult] containing the exit code, stdout, and stderr.
     * @throws IllegalStateException if the command times out.
     */
    fun execCmdSync(cmdParts: List<String>, timeoutSeconds: Long): ProcessResult

    /**
     * Executes a command asynchronously and returns the [Process] object.
     *
     * @param cmdParts The command and its arguments as a list of strings.
     * @return The [Process] object for the started command.
     */
    fun execCmdAsync(cmdParts: List<String>): Process
}

/**
 * The default implementation of [ProcessExecutor] that uses [ProcessBuilder]
 * to execute commands on the local system.
 */
class DefaultProcessExecutor : ProcessExecutor {
    override fun execCmdSync(cmdParts: List<String>, timeoutSeconds: Long): ProcessResult {
        val process = ProcessBuilder(cmdParts).start()
        val waitFor = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!waitFor) {
            throw IllegalStateException("Timeout waiting for ${cmdParts.joinToString(" ")}")
        }

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.exitValue(), stdout, stderr)
    }

    override fun execCmdAsync(cmdParts: List<String>): Process {
        return ProcessBuilder(cmdParts).start()
    }
}
