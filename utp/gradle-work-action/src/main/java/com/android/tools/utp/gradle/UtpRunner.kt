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

package com.android.tools.utp.gradle

import com.android.tools.utp.gradle.api.UtpDependencies
import com.android.tools.utp.gradle.api.UtpDependency
import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerProto
import com.google.testing.platform.launcher.Launcher
import com.google.testing.platform.proto.api.core.TestSuiteResultProto
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs UTP test suites.
 *
 * This class is designed to be used within a Gradle Worker. It handles the
 * entire lifecycle of running UTP, including:
 * 1. Executing multiple UTP instances in parallel using an [ExecutorService].
 * 2. Isolating UTP execution using separate [URLClassLoader]s to avoid dependency conflicts.
 * 3. Collecting results via the [utpTestResultListenerServer], which generates XML reports and writes
 * the final `test-result.pb` file.
 *
 * @param utpDependencies Resolved UTP dependency artifacts.
 * @param enableUtpTestReportingForAndroidStudio If true, test results are also printed
 * to stdout in a base64-encoded format for Android Studio to parse.
 * @param utpTestResultListenerServer The server that listens for UTP test results. This runner
 * is responsible for setting the listener on this server, but not for managing its lifecycle.
 * @param logger A Gradle logger instance.
 * @param executorServiceFactory A factory function to create the [ExecutorService]
 * used for parallel process execution.
 * @param utpExecutor The function used to execute a single UTP instance given its config file.
 */
class UtpRunner(
    private val utpDependencies: UtpDependencies,
    private val enableUtpTestReportingForAndroidStudio: Boolean,
    private val utpTestResultListenerServer: UtpTestResultListenerServerRunner,
    private val logger: Logger = Logging.getLogger(UtpRunner::class.java),
    private val executorServiceFactory: () -> ExecutorService = Executors::newCachedThreadPool,
    private val utpExecutor: UtpRunner.(File) -> Unit = UtpRunner::execute,
) {
    /**
     * Executes all UTP test runs.
     *
     * This method orchestrates the entire test execution. It starts the result listener
     * server, configures all test runs to report to that server, and then executes
     * them in parallel, waiting for all to complete.
     *
     * @param utpRunnerConfigFileList List of base UTP runner config files (one per shard/run).
     * @param deviceIDs List of device IDs, used to tag results and create listener plugins.
     * @param deviceNames List of device names, used for XML report generation.
     * @param deviceShardNames List of shard names, used for XML report generation.
     * @param projectPath The Gradle project path, passed to the XML report listener.
     * @param variantName The Gradle variant name, passed to the XML report listener.
     * @param xmlTestReportOutputDirectory The final directory for the `TEST-*.xml` reports.
     * @param utpResultProtoOutputFileList List of file paths where the final
     * `test-result.pb` for each run should be written.
     */
    fun execute(
        utpRunnerConfigFileList: List<File>,
        deviceIDs: List<String>,
        deviceNames: List<String>,
        deviceShardNames: List<String>,
        projectPath: String,
        variantName: String,
        xmlTestReportOutputDirectory: File,
        utpResultProtoOutputFileList: List<File>,
    ) {
        val xmlReportCreators = deviceIDs.withIndex().associate { (i, deviceID) ->
            val ddmlibTestResultAdapter = DdmlibTestResultAdapter(
                deviceNames[i],
                CustomTestRunListener(
                    deviceShardNames[i],
                    projectPath,
                    variantName,
                    LoggerWrapper(logger)
                ).apply { setReportDir(xmlTestReportOutputDirectory) }
            )
            deviceID to ddmlibTestResultAdapter
        }

        val utpProtoFileMap = deviceIDs.zip(utpResultProtoOutputFileList).toMap()

        utpTestResultListenerServer.setListener(object: UtpTestResultListener {
            override fun onTestResultEvent(testResultEvent: GradleAndroidTestResultListenerProto.TestResultEvent) {
                xmlReportCreators[testResultEvent.deviceId]?.onTestResultEvent(testResultEvent)
                if (testResultEvent.hasTestSuiteFinished()) {
                    val resultProto = testResultEvent.testSuiteFinished.testSuiteResult
                        .unpack(TestSuiteResultProto.TestSuiteResult::class.java)
                    utpProtoFileMap[testResultEvent.deviceId]?.let { outputFile ->
                        outputFile.outputStream().use { outputFileStream ->
                            resultProto.writeTo(outputFileStream)
                        }
                    }
                }

                if (enableUtpTestReportingForAndroidStudio) {
                    println(
                        "<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>" +
                        Base64.getEncoder().encodeToString(testResultEvent.toByteArray()) +
                        "</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>"
                    )
                }
            }
        })

        execute(utpRunnerConfigFileList)
    }

    /**
     * Executes all the configured UTP test suites in parallel.
     *
     * This private method submits tasks to the [ExecutorService] to run
     * UTP. It waits for all tasks to complete.
     *
     * @param utpRunnerConfigFileList List of base UTP runner config files (one per shard/run).
     * @throws GradleException if any UTP task fails or an exception occurs.
     */
    private fun execute(utpRunnerConfigFileList: List<File>) {
        val executorService = executorServiceFactory()
        try {
            val futures = utpRunnerConfigFileList.map { runConfig ->
                executorService.submit {
                    utpExecutor(runConfig)
                }
            }.toList()

            var hasExecutionFailures = false
            futures.forEach {
                try {
                    it.get()
                } catch (e: ExecutionException) {
                    logger.warn("Test Execution failed", e)
                    hasExecutionFailures = true
                }
            }

            if (hasExecutionFailures) {
                throw GradleException("Test Execution failed")
            }
        } finally {
            executorService.shutdownNow()
            executorService.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    /**
     * Executes a single UTP run for a given configuration.
     *
     * @param utpRunnerConfigFile The runner configuration proto file for this specific UTP execution.
     */
    private fun execute(utpRunnerConfigFile: File) {
        /**
         * UTP core classes needs to be loaded by a separate classloader from UTP launcher classes.
         * UTP launcher classes are loaded in the Gradle worker's class-loader.
         *
         * +------------------------------------+
         * | [Gradle Worker / UTP Base Loader]  |
         * | - Config APIs                      |
         * | - Plugin APIs                      |
         * | - Device APIs                      |
         * +------------------+-----------------+
         * ^
         * |
         * +-------------------------+-------------------------+
         * |                         |                         |
         * +-------------------+     +-------------------+     +-------------------+
         * |    [UTP Core]     |     |   [Plugin Foo]    |     | [Result Listener] |
         * | - Classes         |     | - Classes         |     | - Classes         |
         * | - Deps            |     | - Deps            |     | - Deps            |
         * +-------------------+     +-------------------+     +-------------------+
         */
        val utpCoreClassLoader = URLClassLoader(
            utpDependencies.core.files.map { it.toURI().toURL() }.toTypedArray(),
            Launcher::class.java.classLoader)

        val mainClass = utpCoreClassLoader.loadClass(UtpDependency.CORE.mainClass)

        val onExitCallbackClass = utpCoreClassLoader.loadClass(
            kotlin.jvm.functions.Function1::class.java.name)

        val mainMethod = mainClass.getMethod(
            "main",
            arrayOf<String>()::class.java,
            onExitCallbackClass)

        val onExitCallbackProxy = Proxy.newProxyInstance(
            utpCoreClassLoader,
            arrayOf(onExitCallbackClass)) { _, _, _ -> }

        mainMethod(
            null,
            arrayOf("--proto_config=${utpRunnerConfigFile.absolutePath}"),
            onExitCallbackProxy)
    }
}
