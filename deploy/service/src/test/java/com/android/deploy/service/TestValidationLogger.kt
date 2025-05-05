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
package com.android.deploy.service

import com.android.ddmlib.Log
import com.android.ddmlib.Log.ILogOutput
import java.util.Queue
import java.util.concurrent.LinkedBlockingQueue

class TestValidationLogger : ILogOutput {
    data class LogOutput(val logLevel: Log.LogLevel, val tag: String, val message: String)

    private val myLogQueue: Queue<LogOutput> = LinkedBlockingQueue<LogOutput>()

    override fun printLog(logLevel: Log.LogLevel, tag: String, message: String) {
        myLogQueue.offer(LogOutput(logLevel, tag, message))
    }

    override fun printAndPromptLog(logLevel: Log.LogLevel, tag: String, message: String) {
        myLogQueue.offer(LogOutput(logLevel, tag, message))
    }

    val nextLogLine: LogOutput?
        get() = myLogQueue.poll()
}
