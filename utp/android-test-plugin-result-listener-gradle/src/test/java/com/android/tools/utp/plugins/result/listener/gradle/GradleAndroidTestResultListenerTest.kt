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
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.Any
import com.google.testing.platform.api.config.ProtoConfig
import com.google.testing.platform.api.context.Context
import com.google.testing.platform.proto.api.core.ExtensionProto
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Unit test for [GradleAndroidTestResultListener]. */
@RunWith(JUnit4::class)
class GradleAndroidTestResultListenerTest {

  @get:Rule var temporaryFolder = TemporaryFolder()

  private val capturedRequests = mutableListOf<TestResultEvent>()
  private val testListener = GradleAndroidTestResultListener { event -> capturedRequests.add(event) }

  private var utpResultProtoOutputFile: File? = null

  @Before
  fun setUp() {
    utpResultProtoOutputFile = temporaryFolder.newFile()
    val config =
      Any.pack(
        GradleAndroidTestResultListenerConfig.newBuilder()
          .apply {
            deviceId = "deviceIdString"
            utpResultProtoOutputFilePath = utpResultProtoOutputFile!!.absolutePath
            xmlTestReportOutputDirectoryPath = temporaryFolder.newFolder().absolutePath
          }
          .build()
      )
    val protoConfig =
      object : ProtoConfig {
        override val configProto: Any
          get() = config

        override val configResource: ExtensionProto.ConfigResource?
          get() = null
      }

    val context = mock(Context::class.java)
    `when`(context[Context.CONFIG_KEY]).thenReturn(protoConfig)

    testListener.configure(context)
  }

  @Test
  fun testSuiteFinishedSuccessfully() {
    val testSuiteResult = TestSuiteResult.newBuilder().apply { testSuiteMetaDataBuilder.scheduledTestCaseCount = 1 }.build()

    testListener.apply {
      beforeTestSuite(testSuiteResult.testSuiteMetaData)
      beforeTest(null)
      afterTest(TestResult.getDefaultInstance())
      afterTestSuite(testSuiteResult)
    }

    assertThat(capturedRequests)
      .containsExactly(
        TestResultEvent.newBuilder()
          .apply {
            deviceId = "deviceIdString"
            testSuiteStartedBuilder.apply { testSuiteMetadata = Any.pack(testSuiteResult.testSuiteMetaData) }
          }
          .build(),
        TestResultEvent.newBuilder()
          .apply {
            deviceId = "deviceIdString"
            testCaseStarted = TestResultEvent.TestCaseStarted.getDefaultInstance()
          }
          .build(),
        TestResultEvent.newBuilder()
          .apply {
            deviceId = "deviceIdString"
            testCaseFinishedBuilder.apply { testCaseResult = Any.pack(TestResult.getDefaultInstance()) }
          }
          .build(),
        TestResultEvent.newBuilder()
          .apply {
            deviceId = "deviceIdString"
            testSuiteFinishedBuilder.apply { this.testSuiteResult = Any.pack(testSuiteResult) }
          }
          .build(),
      )
      .inOrder()

    val outputFileContent = TestSuiteResult.parseFrom(utpResultProtoOutputFile!!.readBytes())
    assertThat(outputFileContent.toByteArray()).isEqualTo(testSuiteResult.toByteArray())
  }

  @Test
  fun testWithFailedAndIgnoredTests() {
    val failedTestResult =
      TestResult.newBuilder().apply { testStatus = com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus.FAILED }.build()
    val ignoredTestResult =
      TestResult.newBuilder().apply { testStatus = com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus.IGNORED }.build()

    testListener.apply {
      afterTest(failedTestResult)
      afterTest(ignoredTestResult)
    }

    assertThat(capturedRequests).hasSize(2)
    assertThat(capturedRequests[0].testCaseFinished.testCaseResult.unpack(TestResult::class.java)).isEqualTo(failedTestResult)
    assertThat(capturedRequests[1].testCaseFinished.testCaseResult.unpack(TestResult::class.java)).isEqualTo(ignoredTestResult)
  }
}
