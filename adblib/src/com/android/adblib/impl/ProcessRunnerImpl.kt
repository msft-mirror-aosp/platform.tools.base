/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.adblib.AdbLogger
import com.android.adblib.AdbSessionHost
import com.android.adblib.ProcessRunner
import com.android.adblib.ProcessRunner.ProcessResult
import com.android.adblib.adbLogger
import com.android.adblib.impl.channels.runInterruptibleIO
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A coroutine friendly implementation of executing a process and collecting its `stdout`, `stderr` and exit code. */
internal class ProcessRunnerImpl(private val host: AdbSessionHost) : ProcessRunner {

  private val logger: AdbLogger
    get() = adbLogger(host)

  override suspend fun runProcess(executable: Path, args: List<String>, envVars: Map<String, String>): ProcessResult {
    if (!executable.isAbsolute) {
      throw IllegalArgumentException("Executable path must be absolute: `$executable`")
    }
    val directory = executable.parent
    val command = listOf(executable.toString()) + args
    logger.debug { "runProcess: '${command.joinToString(" ")}'" }

    val processResult = execute(command, directory, envVars)
    if (processResult.exitCode != 0) {
      logger.warn(
        "'${command.joinToString(" ")}' exited with code ${processResult.exitCode}\n" +
          "Stdout: ${processResult.stdout}\nStderr: ${processResult.stderr}"
      )
    }
    return processResult
  }

  internal suspend fun execute(command: List<String>, directory: Path?, envVars: Map<String, String>): ProcessResult {
    return coroutineScope {
      val process = startProcess(command, directory, envVars)
      try {
        val stdout = async { collectStream(process.inputStream) }
        val stderr = async { collectStream(process.errorStream) }
        val exitCode = async { waitForProcessEnd(process) }

        // Await all coroutines to ensure any exception is thrown as soon as possible
        awaitAll(stdout, stderr, exitCode)

        ProcessResult(stdout.await(), stderr.await(), exitCode.await())
      } catch (t: Throwable) {
        runCatching {
            if (process.isAlive) {
              process.destroyForcibly()
            }
          }
          .onFailure { t.addSuppressed(it) }
        throw t
      }
    }
  }

  private suspend fun startProcess(command: List<String>, directory: Path?, envVars: Map<String, String>): Process {
    return withContext(host.blockingIoDispatcher) {
      val processBuilder = ProcessBuilder(command)
      directory?.let { processBuilder.directory(File(directory.toString())) }
      val env = processBuilder.environment()
      envVars.forEach { (key, value) -> env[key] = value }
      processBuilder.start()
    }
  }

  private suspend fun waitForProcessEnd(process: Process): Int {
    return runInterruptibleIO(host.blockingIoDispatcher) {
      val exitCode = process.waitFor()
      exitCode
    }
  }

  private suspend fun collectStream(stream: InputStream): List<String> {
    return withContext(host.blockingIoDispatcher) {
      InputStreamReader(stream, StandardCharsets.UTF_8).use {
        BufferedReader(it).use { reader ->
          val lines = mutableListOf<String>()
          reader.lines().forEach { line ->
            coroutineContext.ensureActive()
            lines.add(line)
          }
          lines
        }
      }
    }
  }
}
