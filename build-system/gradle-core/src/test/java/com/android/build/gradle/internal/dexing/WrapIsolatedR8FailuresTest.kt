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

package com.android.build.gradle.internal.dexing

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [wrapIsolatedR8Failures]. */
class WrapIsolatedR8FailuresTest {

  @Test
  fun returnsResult() {
    assertThat(wrapIsolatedR8Failures { 42 }).isEqualTo(42)
  }

  @Test
  fun reportsLinkageErrorAsIncompatibleR8() {
    val cause = NoSuchMethodError("missing")
    val error = assertThrows(RuntimeException::class.java) { wrapIsolatedR8Failures { throw cause } }
    assertThat(error).hasMessageThat().contains("may be incompatible with this version of the Android Gradle Plugin: missing")
    assertThat(error).hasCauseThat().isSameInstanceAs(cause)
  }

  @Test
  fun reportsReflectionFailureAsIncompatibleR8() {
    val cause = NoSuchFieldException("missing")
    val error = assertThrows(RuntimeException::class.java) { wrapIsolatedR8Failures { throw cause } }
    assertThat(error).hasCauseThat().isSameInstanceAs(cause)
  }

  @Test
  fun rethrowsOtherFailuresUnchanged() {
    val cause = IllegalStateException("tool failure")
    val error = assertThrows(IllegalStateException::class.java) { wrapIsolatedR8Failures { throw cause } }
    assertThat(error).isSameInstanceAs(cause)
  }
}
