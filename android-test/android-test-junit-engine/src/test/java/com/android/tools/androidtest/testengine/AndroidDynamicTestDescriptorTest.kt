/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tools.androidtest.testengine

import com.android.tools.androidtest.testengine.instrument.AmInstrumentationParser
import com.android.tools.androidtest.testengine.instrument.TestIdentifier
import com.android.tools.androidtest.testengine.instrument.TestResult
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.UniqueId
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class AndroidDynamicTestDescriptorTest {

  private fun createMockContext(): AndroidTestExecutionContext {
    val request = mock(ExecutionRequest::class.java)
    val config = mock(org.junit.platform.engine.ConfigurationParameters::class.java)
    whenever(request.configurationParameters).thenReturn(config)
    whenever(config.get(any())).thenReturn(java.util.Optional.empty())
    whenever(config.get(AndroidTestConfigurationKeys.ADB_PATH)).thenReturn(java.util.Optional.of("adb"))
    whenever(config.get(AndroidTestConfigurationKeys.AAPT2_PATH)).thenReturn(java.util.Optional.of("aapt2"))
    whenever(config.get(AndroidTestConfigurationKeys.DEVICE_SERIALS)).thenReturn(java.util.Optional.of("serial"))
    whenever(config.get(AndroidTestConfigurationKeys.INSTRUMENTATION_RUNNER_CLASS)).thenReturn(java.util.Optional.of("runner"))
    whenever(config.get(AndroidTestConfigurationKeys.TEST_PACKAGE_ID)).thenReturn(java.util.Optional.of("test_pkg"))
    whenever(config.get(AndroidTestConfigurationKeys.INSTRUMENTATION_TARGET_PACKAGE_ID)).thenReturn(java.util.Optional.of("target_pkg"))
    return AndroidTestExecutionContext(request)
  }

  @Test
  fun `execute succeeds for OK status`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("test", "com.example.Test.testMethod")
    val descriptor = AndroidDynamicTestDescriptor(uniqueId, "testMethod", "com.example.Test", "testMethod")
    val context = createMockContext()

    val result =
      TestResult(
        testIdentifier = TestIdentifier("com.example", "Test", "testMethod"),
        status = AmInstrumentationParser.STATUS_CODE_OK,
        startTime = Instant.now(),
        endTime = Instant.now(),
        stackTrace = null,
        statusBundle = emptyMap(),
      )
    descriptor.resultDeferred.complete(result)

    descriptor.execute(context, mock(org.junit.platform.engine.support.hierarchical.Node.DynamicTestExecutor::class.java))
    // No exception thrown
  }

  @Test
  fun `execute fails for FAILURE status`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("test", "com.example.Test.testMethod")
    val descriptor = AndroidDynamicTestDescriptor(uniqueId, "testMethod", "com.example.Test", "testMethod")
    val context = createMockContext()

    val result =
      TestResult(
        testIdentifier = TestIdentifier("com.example", "Test", "testMethod"),
        status = AmInstrumentationParser.STATUS_CODE_FAILURE,
        startTime = Instant.now(),
        endTime = Instant.now(),
        stackTrace = "java.lang.AssertionError: expected: true but was: false",
        statusBundle = emptyMap(),
      )
    descriptor.resultDeferred.complete(result)

    val exception =
      assertThrows(RuntimeException::class.java) {
        descriptor.execute(context, mock(org.junit.platform.engine.support.hierarchical.Node.DynamicTestExecutor::class.java))
      }
    assertThat(exception.message).contains("expected: true but was: false")
  }

  @Test
  fun `execute throws TestAbortedException for ASSUMPTION_FAILURE status`() {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("test", "com.example.Test.testMethod")
    val descriptor = AndroidDynamicTestDescriptor(uniqueId, "testMethod", "com.example.Test", "testMethod")
    val context = createMockContext()

    val result =
      TestResult(
        testIdentifier = TestIdentifier("com.example", "Test", "testMethod"),
        status = AmInstrumentationParser.STATUS_CODE_ASSUMPTION_FAILURE,
        startTime = Instant.now(),
        endTime = Instant.now(),
        stackTrace = "org.junit.AssumptionViolatedException: assumption failed",
        statusBundle = emptyMap(),
      )
    descriptor.resultDeferred.complete(result)

    assertThrows(org.opentest4j.TestAbortedException::class.java) {
      descriptor.execute(context, mock(org.junit.platform.engine.support.hierarchical.Node.DynamicTestExecutor::class.java))
    }
  }
}
