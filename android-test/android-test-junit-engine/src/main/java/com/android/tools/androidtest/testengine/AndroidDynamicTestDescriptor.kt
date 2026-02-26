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
import com.android.tools.androidtest.testengine.instrument.TestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.engine.support.hierarchical.Node

/**
 * A dynamic test descriptor representing a single test case running on an Android device.
 *
 * This descriptor is created dynamically when the instrumentation runner reports that a test has started. It uses a [CompletableDeferred]
 * to wait for the test result, which is delivered by the instrumentation listener.
 */
class AndroidDynamicTestDescriptor(uniqueId: UniqueId, displayName: String, className: String, methodName: String) :
  AbstractTestDescriptor(uniqueId, displayName, MethodSource.from(className, methodName)), Node<AndroidTestExecutionContext> {

  /**
   * A deferred result that is completed by the instrumentation listener when the test ends. The [execute] method awaits this deferred value
   * to report the final status to JUnit.
   */
  val resultDeferred = CompletableDeferred<TestResult>()

  override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST

  /**
   * Blocks (suspends) until the test result is available via [resultDeferred].
   *
   * Once the result is received, it evaluates the status code. If the test failed (i.e., status is not OK, IGNORED, or ASSUMPTION_FAILURE),
   * it throws a [RuntimeException] with the stack trace, which JUnit captures as a test failure.
   */
  override fun execute(context: AndroidTestExecutionContext, dynamicTestExecutor: Node.DynamicTestExecutor): AndroidTestExecutionContext {
    val result = runBlocking { resultDeferred.await() } // Suspends until testEnded is called in the listener
    when (result.status) {
      AmInstrumentationParser.STATUS_CODE_OK -> {
        // Success
      }
      AmInstrumentationParser.STATUS_CODE_ASSUMPTION_FAILURE,
      AmInstrumentationParser.STATUS_CODE_IGNORED -> {
        // TODO: Mark as skipped/ignored. For now, we just complete successfully.
      }
      else -> {
        throw RuntimeException(result.stackTrace ?: "Test failed with status ${result.status}")
      }
    }
    return context
  }
}
