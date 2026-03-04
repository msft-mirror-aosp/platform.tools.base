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

/** Configuration parameter keys for Android Test Platform. */
object AndroidTestConfigurationKeys {
  /** Path to the ADB executable. */
  const val ADB_PATH = "android-test.adb-path"

  /** Path to the AAPT2 executable. */
  const val AAPT2_PATH = "android-test.aapt2-path"

  /** Comma-separated serial numbers of the devices to run tests on. */
  const val DEVICE_SERIALS = "android-test.device-serials"

  /** Timeout in milliseconds for APK installation. */
  const val INSTALL_TIMEOUT_MS = "android-test.install-timeout-ms"

  /** Comma-separated list of paths to the APKs to be tested. */
  const val TESTED_APKS = "android-test.tested-apks"

  /** Comma-separated list of paths to the test APKs. */
  const val TEST_APKS = "android-test.test-apks"

  /** Comma-separated list of paths to utility APKs to be installed before testing. */
  const val TEST_UTIL_APKS = "android-test.test-util-apks"

  /** Comma-separated list of options to pass to `adb install`. */
  const val APK_INSTALL_OPTIONS = "android-test.apk-install-options"

  /** Whether to uninstall the APKs from the device after tests have finished. */
  const val UNINSTALL_AFTER_TESTS = "android-test.uninstall-after-tests"

  /** The fully qualified class name of the instrumentation runner to use. */
  const val INSTRUMENTATION_RUNNER_CLASS = "android-test.instrumentation-runner-class"

  /** The package ID of the instrumentation target. */
  const val INSTRUMENTATION_TARGET_PACKAGE_ID = "android-test.instrumentation-target-package-id"

  /** Path to the results directory. */
  const val RESULTS_DIR = "android-test.results-dir"
}
