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

/** Report entry keys for Android Test Engine. */
object AndroidTestReportKeys {
  /** The report entry key for the test count. */
  const val TEST_COUNT = "android-test.test-count"

  /** The report entry key for the device display name. */
  const val DEVICE_DISPLAY_NAME = "android-test.device-display-name"

  /** The report entry key for the logcat path. */
  const val LOGCAT_PATH = "android-test.logcat-path"

  /** The report entry key for the device info path. */
  const val DEVICE_INFO_PATH = "android-test.device-info-path"
}
