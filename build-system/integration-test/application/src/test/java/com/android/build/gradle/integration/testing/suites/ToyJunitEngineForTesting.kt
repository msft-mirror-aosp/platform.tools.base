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

package com.android.build.gradle.integration.testing.suites

import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.build.api.testsuites.TestSuiteExecutionClient
import java.time.LocalDateTime
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor

import java.io.File
import java.time.format.DateTimeFormatter

class ToyJunitEngineForTesting: TestEngine {

    // load my input properties as a json object, I am only using a handful of those so far.
    private val inputParams = TestSuiteExecutionClient.default()

    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val loggerFile = File(inputParams.getInputParameter(TestEngineInputProperty.LOGGING_FILE))

    // Bare minimum logger, we should move this to a Service class.
    private fun log(level: String, message: String) {
        val timestamp = LocalDateTime.now().format(dateTimeFormatter)
        val logEntry = "[$timestamp] [$level] $message\n"

        try {
            loggerFile.appendText(logEntry)
        } catch (e: Exception) {
            System.err.println("Error writing to log file '${loggerFile.absolutePath}': ${e.message}")
        }
    }

    fun info(message: String) = log("INFO", message)
    fun debug(message: String) = log("DEBUG", message)
    fun warn(message: String) = log("WARN", message)
    fun error(message: String) = log("ERROR", message)

    override fun getId(): String {
        info("getId::called\n")
        return "[engine:toy-junit-engine-for-tests]"
    }

    override fun discover(p0: EngineDiscoveryRequest?, p1: UniqueId?): TestDescriptor {
        info("Test discovery !\n")
        return ToyTestDescriptor(UniqueId.parse("[method: some-test]"))
    }

    override fun execute(p0: ExecutionRequest?) {
        p0?.let { executionRequest ->
            info("Executing toy engine ! ${executionRequest.rootTestDescriptor}")
            inputParams.inputParameters.forEach {
                info("Input : $it")
            }
            val listener: EngineExecutionListener = executionRequest.engineExecutionListener

            val engineDescriptor = executionRequest.rootTestDescriptor
            info("Starting $engineDescriptor test.")
            listener.executionStarted(engineDescriptor)

            // Simulated test execution
            try {
                val testSucceeded = true // Replace with actual test outcome.
                if (testSucceeded) {
                    listener.executionFinished(engineDescriptor, TestExecutionResult.successful())
                } else {
                    listener.executionFinished(
                        engineDescriptor,
                        TestExecutionResult.failed(Exception("Test failed"))
                    )
                }
            } catch (t: Throwable) {
                listener.executionFinished(engineDescriptor, TestExecutionResult.failed(t))
            }
            info("Finished $engineDescriptor test.")
        }
    }
}

class ToyTestDescriptor(uniqueId: UniqueId): AbstractTestDescriptor(uniqueId, "toy descriptor") {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
}
