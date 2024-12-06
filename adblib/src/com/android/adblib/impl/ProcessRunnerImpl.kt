/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.adblib.impl

import com.android.adblib.AdbSessionHost
import com.android.adblib.ProcessRunner
import com.android.adblib.impl.channels.runInterruptibleIO
import java.io.File
import java.io.IOException
import java.nio.file.Path

internal class ProcessRunnerImpl(private val host: AdbSessionHost) : ProcessRunner {

    override suspend fun runProcess(
        executable: Path,
        args: List<String>,
        envVars: Map<String, String>
    ) {
        if (!executable.isAbsolute) {
            throw IllegalArgumentException("Executable path must be absolute: `$executable`")
        }
        runInterruptibleIO(host.blockingIoDispatcher) {
            val command = listOf(executable.toString()) + args
            host.logger.info { "runProcess: ${command.joinToString(" ")}" }
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(File(executable.parent.toString()))
            val env = processBuilder.environment()
            envVars.forEach { (key, value) -> env[key] = value }
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            host.logger.debug { output }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                host.logger.debug { "${command.joinToString(" ")} failed. Output: $output" }
                throw IOException("adb ${command.joinToString(" ")} failed. Exit code: $exitCode")
            }
        }
    }
}
