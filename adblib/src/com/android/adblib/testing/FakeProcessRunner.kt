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
package com.android.adblib.testing

import com.android.adblib.ProcessRunner
import com.android.adblib.ProcessRunner.ProcessResult
import kotlinx.coroutines.delay
import java.nio.file.Path

class FakeProcessRunner() : ProcessRunner {

    var delayByMs: Long = 0
    var lastDirectory: String? = null
    var lastCommand: List<String>? = null
    val allCommands: MutableList<List<String>> = mutableListOf()
    var throwOnNextCommand: Throwable? = null
    var resultToReturn: ProcessResult? = null

    override suspend fun runProcess(
        executable: Path, args: List<String>, envVars: Map<String, String>
    ): ProcessResult {
        delay(delayByMs)
        throwOnNextCommand?.let { throw it }
        val command = listOf(executable.toString()) + args
        lastDirectory = executable.parent.toString()
        lastCommand = command
        allCommands.add(command)
        return resultToReturn ?: ProcessResult(
            stdout = emptyList(),
            stderr = emptyList(),
            exitCode = 0
        )
    }

    fun reset() {
        lastDirectory = null
        lastCommand = null
        allCommands.clear()
    }
}
