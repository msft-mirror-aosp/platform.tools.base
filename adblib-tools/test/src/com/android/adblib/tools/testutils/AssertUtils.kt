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
package com.android.adblib.tools.testutils

import org.junit.Assert

/**
 * Similar to [Assert.assertThrows] but allows for asserting over a `suspend` function call (i.e. coroutine) as well as asserting the
 * exception type ([expectedException]) and optionally calling [additionalAssertions] with the actual exception thrown.
 */
internal suspend fun <T : Throwable> assertSuspendingThrows(
  expectedException: Class<T>,
  additionalAssertions: (T) -> Unit = {},
  block: suspend () -> Unit,
) {
  fun <T : Throwable> Class<T>.reportedName(): String {
    return canonicalName ?: name
  }

  runCatching { block() }
    .onSuccess {
      // Success is an error in this case
      Assert.fail("expected ${expectedException.reportedName()} to be thrown, but nothing was thrown")
    }
    .onFailure { actualThrown ->
      // Check exception class
      if (!expectedException.isInstance(actualThrown)) {
        val mismatchMessage =
          "expected ${expectedException.reportedName()} to be thrown, but ${actualThrown::class.java.reportedName()} was thrown instead"
        val error = AssertionError(mismatchMessage).also { it.initCause(actualThrown) }
        throw error
      }

      // Evaluate additional assertions
      @Suppress("UNCHECKED_CAST") additionalAssertions(actualThrown as T)
    }
}
