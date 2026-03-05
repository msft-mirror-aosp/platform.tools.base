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

package com.android.tools.androidtest.listener

/** Configuration parameter keys for Android Test Result Listener. */
object AndroidTestResultListenerKeys {
  /** Whether to stream test results as base64-encoded protos. */
  const val STREAM_BASE64_ENCODED_RESULT = "android-test.listener.stream-base64-encoded-result"

  /** The file path to write streaming test results to. */
  const val STREAMING_RESULTS_FILE = "com.android.junit.engine.results.streaming.file"

  /** The file path to write test results proto to. */
  const val TEST_RESULTS_FILE = "com.android.junit.engine.results.file"

  /** The directory path to write test results proto to. */
  const val RESULTS_DIR = "com.android.junit.engine.results.dir"
}
