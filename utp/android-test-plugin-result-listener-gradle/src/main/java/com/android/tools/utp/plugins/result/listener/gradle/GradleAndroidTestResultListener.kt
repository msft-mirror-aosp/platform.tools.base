/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.utp.plugins.result.listener.gradle

import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerConfigProto.GradleAndroidTestResultListenerConfig
import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerProto.TestResultEvent
import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerProto.TestResultEvent.TestSuiteStarted
import com.google.protobuf.Any
import com.google.testing.platform.api.config.Configurable
import com.google.testing.platform.api.config.ProtoConfig
import com.google.testing.platform.api.context.Context
import com.google.testing.platform.api.result.TestResultListener
import com.google.testing.platform.proto.api.core.TestCaseProto
import com.google.testing.platform.proto.api.core.TestResultProto
import com.google.testing.platform.proto.api.core.TestSuiteResultProto
import org.gradle.api.logging.Logging
import java.io.File
import java.util.Base64

/**
 * A UTP Android test result listener plugin which reports the results to AGP.
 */
class GradleAndroidTestResultListener(
    private val onTestResultEventFunc: GradleAndroidTestResultListener.(TestResultEvent) -> Unit
    = GradleAndroidTestResultListener::onTestResultEvent) : TestResultListener, Configurable {

    private lateinit var deviceId: String
    private lateinit var ddmlibTestResultAdapter: DdmlibTestResultAdapter
    private var enableUtpTestReportingForAndroidStudio: Boolean = false
    private lateinit var utpResultProtoOutputFilePath: String

    override fun configure(context: Context) {
        val config = context[Context.CONFIG_KEY] as ProtoConfig
        val pluginConfig = GradleAndroidTestResultListenerConfig.parseFrom(
                config.configProto!!.value)

        deviceId = pluginConfig.deviceId
        ddmlibTestResultAdapter = DdmlibTestResultAdapter(
            pluginConfig.deviceName,
            CustomTestRunListener(
                pluginConfig.deviceShardName,
                pluginConfig.gradleProjectPath,
                pluginConfig.variantName,
                LoggerWrapper(Logging.getLogger(GradleAndroidTestResultListener::class.java)),
            ).apply { setReportDir(File(pluginConfig.xmlTestReportOutputDirectoryPath)) }
        )
        enableUtpTestReportingForAndroidStudio = pluginConfig.enableUtpTestReportingForAndroidStudio
        utpResultProtoOutputFilePath = pluginConfig.utpResultProtoOutputFilePath
    }

    override fun beforeTestSuite(testSuiteMetaData: TestSuiteResultProto.TestSuiteMetaData?) {
        val suiteStarted = TestSuiteStarted.newBuilder().apply {
            if (testSuiteMetaData != null) {
                this.testSuiteMetadata = Any.pack(testSuiteMetaData)
            }
        }.build()
        val event = createTestResultEvent().apply {
            testSuiteStarted = suiteStarted
        }.build()

        onTestResultEventFunc(event)
    }

    override fun beforeTest(testCase: TestCaseProto.TestCase?) {
        val testCaseStarted = TestResultEvent.TestCaseStarted.newBuilder().apply {
            if (testCase != null) {
                this.testCase = Any.pack(testCase)
            }
        }.build()
        val event = createTestResultEvent().apply {
            this.testCaseStarted = testCaseStarted
        }.build()

        onTestResultEventFunc(event)
    }

    override fun afterTest(testResult: TestResultProto.TestResult) {
        val testCaseFinished = TestResultEvent.TestCaseFinished.newBuilder().apply {
            testCaseResult = Any.pack(testResult)
        }.build()
        val event = createTestResultEvent().apply {
            this.testCaseFinished = testCaseFinished
        }.build()

        onTestResultEventFunc(event)
    }

    override fun afterTestSuite(testSuiteResult: TestSuiteResultProto.TestSuiteResult) {
        val testSuiteFinished = TestResultEvent.TestSuiteFinished.newBuilder().apply {
            this.testSuiteResult = Any.pack(testSuiteResult)
        }.build()
        val event = createTestResultEvent().apply {
            this.testSuiteFinished = testSuiteFinished
        }.build()

        onTestResultEventFunc(event)

        File(utpResultProtoOutputFilePath).outputStream().use { outputFileStream ->
            testSuiteResult.writeTo(outputFileStream)
        }
    }

    private fun createTestResultEvent(): TestResultEvent.Builder {
        return TestResultEvent.newBuilder().apply {
            deviceId = this@GradleAndroidTestResultListener.deviceId
        }
    }

    private fun onTestResultEvent(testResultEvent: TestResultEvent) {
        if (enableUtpTestReportingForAndroidStudio) {
            val encodedEvent = Base64.getEncoder().encodeToString(testResultEvent.toByteArray())
            println("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>$encodedEvent</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
        }
        ddmlibTestResultAdapter.onTestResultEvent(testResultEvent)
    }
}
