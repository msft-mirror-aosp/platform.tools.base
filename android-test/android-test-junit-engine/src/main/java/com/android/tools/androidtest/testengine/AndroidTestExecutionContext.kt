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

import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.AAPT2_PATH
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.ADB_PATH
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.APK_INSTALL_OPTIONS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.DEVICE_SERIALS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTALL_TIMEOUT_MS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTRUMENTATION_RUNNER_CLASS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTRUMENTATION_TARGET_PACKAGE_ID
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.RESULTS_DIR
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.TESTED_APKS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.TEST_APKS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.TEST_UTIL_APKS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.UNINSTALL_AFTER_TESTS
import java.io.File
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.support.hierarchical.EngineExecutionContext

/**
 * Execution context for [AndroidTestEngine].
 *
 * @property request The JUnit platform execution request.
 */
data class AndroidTestExecutionContext(val request: ExecutionRequest) : EngineExecutionContext {
  val configuration = AndroidTestConfiguration(request)
}

/** Configuration for [AndroidTestEngine] extracted from [ExecutionRequest]. */
class AndroidTestConfiguration(request: ExecutionRequest) {
  private val config = request.configurationParameters

  private fun get(key: String, agpTestInput: AgpTestSuiteInput? = null, deviceSerial: String? = null): String? {
    if (deviceSerial != null) {
      val deviceSpecificKey = "$key[$deviceSerial]"
      val value =
        config.get(deviceSpecificKey).orElse(null) ?: System.getProperty(deviceSpecificKey) ?: AgpTestSuiteInput.get(deviceSpecificKey)
      if (value != null) {
        return value
      }

      if (agpTestInput != null) {
        val agpDeviceSpecificKey = "${agpTestInput.key}[$deviceSerial]"
        val agpValue = AgpTestSuiteInput.get(agpDeviceSpecificKey)
        if (agpValue != null) {
          return agpValue
        }
      }
    }
    return config.get(key).orElse(null) ?: System.getProperty(key) ?: agpTestInput?.get() ?: AgpTestSuiteInput.get(key)
  }

  val adb: File =
    get(ADB_PATH, AgpTestSuiteInput.ADB_EXECUTABLE)?.let { File(it) } ?: throw RuntimeException("$ADB_PATH configuration is required")
  val aapt2: File =
    get(AAPT2_PATH, AgpTestSuiteInput.AAPT2_EXECUTABLE)?.let { File(it) } ?: throw RuntimeException("$AAPT2_PATH configuration is required")
  val deviceSerials: List<String> =
    get(DEVICE_SERIALS, AgpTestSuiteInput.SERIAL_IDS)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: throw RuntimeException("$DEVICE_SERIALS configuration is required")
  val installTimeoutMs: Long = get(INSTALL_TIMEOUT_MS)?.toLong() ?: 0L

  val testedApks: List<File> by lazy { getTestedApks() }
  val testApks: List<File> by lazy { getTestApks() }
  val testUtilApks: List<File> by lazy { getTestUtilApks() }
  val apkInstallOptions: List<String> = get(APK_INSTALL_OPTIONS)?.split(",")?.map { opt -> opt.trim() } ?: listOf()
  val uninstallApksAfterTests: Boolean = get(UNINSTALL_AFTER_TESTS)?.toBoolean() ?: true

  val instrumentationRunnerClass: String =
    get(INSTRUMENTATION_RUNNER_CLASS) ?: throw RuntimeException("$INSTRUMENTATION_RUNNER_CLASS configuration is required")
  val instrumentationTargetPackageId: String =
    get(INSTRUMENTATION_TARGET_PACKAGE_ID, AgpTestSuiteInput.TESTED_APPLICATION_ID)
      ?: throw RuntimeException("$INSTRUMENTATION_TARGET_PACKAGE_ID configuration is required")

  val resultsDir: File? = get(RESULTS_DIR, AgpTestSuiteInput.RESULTS_DIR)?.let { File(it) }

  fun getTestedApks(deviceSerial: String? = null): List<File> = resolveApks(get(TESTED_APKS, AgpTestSuiteInput.TESTED_APKS, deviceSerial))

  fun getTestApks(deviceSerial: String? = null): List<File> = resolveApks(get(TEST_APKS, AgpTestSuiteInput.TESTING_APK, deviceSerial))

  fun getTestUtilApks(deviceSerial: String? = null): List<File> = resolveApks(get(TEST_UTIL_APKS, deviceSerial = deviceSerial))

  private fun resolveApks(value: String?): List<File> {
    return value
      ?.split(',')
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.flatMap { path ->
        val file = File(path)
        if (file.isDirectory) {
          file.listFiles { f -> f.extension == "apk" }?.toList() ?: listOf()
        } else {
          listOf(file)
        }
      } ?: listOf()
  }
}
