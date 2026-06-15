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

  /** The package ID of the test APK containing the instrumentation runner. */
  const val TEST_PACKAGE_ID = "android-test.test-package-id"

  /** The package ID of the application being instrumented (the targetPackage). */
  const val INSTRUMENTATION_TARGET_PACKAGE_ID = "android-test.instrumentation-target-package-id"

  /** The application ID of the tested application. */
  const val TESTED_APPLICATION_ID = "com.android.junit.engine.tested.application.id"

  /** Comma-separated list of extra instrumentation arguments in key=value format. */
  const val INSTRUMENTATION_ARGS = "android-test.instrumentation-args"

  /** Path to the results directory. */
  const val RESULTS_DIR = "android-test.results-dir"

  /** The execution mode for the test suite. */
  const val ANDROID_TEST_EXECUTION_MODE = "android-test.execution-mode"

  /** Path to the additional test output directory on host. */
  const val ADDITIONAL_TEST_OUTPUT_DIR_ON_HOST = "android-test.additional-test-output-dir-on-host"

  /** Path to the additional test output directory on device. */
  const val ADDITIONAL_TEST_OUTPUT_DIR_ON_DEVICE = "android-test.additional-test-output-dir-on-device"

  /** Whether to use the test storage service. */
  const val USE_TEST_STORAGE_SERVICE = "android-test.use-test-storage-service"

  /** Whether test coverage is enabled. */
  const val IS_TEST_COVERAGE_ENABLED = "android-test.is-test-coverage-enabled"

  /** Path to the code coverage directory on host. */
  const val COVERAGE_DIR_ON_HOST = "android-test.coverage-dir-on-host"

  /** Path to the single code coverage file on device. */
  const val COVERAGE_FILE_ON_DEVICE = "android-test.coverage-file-on-device"

  /** Path to the multiple code coverage files directory on device. */
  const val COVERAGE_DIR_ON_DEVICE = "android-test.coverage-dir-on-device"

  /** The type of code coverage to use. Values: NONE, ON_THE_FLY. */
  const val COVERAGE_TYPE = "android-test.coverage-type"

  /** Whether to force AOT compilation after installation. */
  const val FORCE_AOT_COMPILATION = "android-test.force-aot-compilation"

  /** The display name of the device, used for directory and file names. */
  const val DEVICE_ID = "android-test.device-id"
}
