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
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.ANDROID_TEST_EXECUTION_MODE
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.ANIMATIONS_DISABLED
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.APK_INSTALL_OPTIONS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.DEVICE_SERIALS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTALL_TIMEOUT_MS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTRUMENTATION_ARGS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTRUMENTATION_RUNNER_CLASS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTRUMENTATION_TARGET_PACKAGE_ID
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTRUMENT_IN_PCC
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.RESULTS_DIR
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.TESTED_APKS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.TESTED_APPLICATION_ID
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.TEST_APKS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.TEST_PACKAGE_ID
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

  /**
   * Resolves configuration property value.
   *
   * @param fallback If false, does not fall back to base key if device-specific key is not found.
   */
  private fun get(key: String, agpTestInput: AgpTestSuiteInput? = null, deviceSerial: String? = null, fallback: Boolean = true): String? {
    if (key.isNotEmpty()) {
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

        // If we only want device-specific overrides, do not fall back to the base key.
        if (!fallback) {
          return null
        }
      }
      val valueFromKey = config.get(key).orElse(null) ?: System.getProperty(key) ?: AgpTestSuiteInput.get(key)
      if (valueFromKey != null) {
        return valueFromKey
      }
    }
    return agpTestInput?.get()
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
  val executionMode: String? = get(ANDROID_TEST_EXECUTION_MODE, AgpTestSuiteInput.ANDROID_TEST_EXECUTION_MODE)
  val animationsDisabled: Boolean = get(ANIMATIONS_DISABLED, AgpTestSuiteInput.ANIMATIONS_DISABLED)?.toBoolean() ?: false
  val instrumentInPcc: Boolean = get(INSTRUMENT_IN_PCC)?.toBoolean() ?: false

  val instrumentationRunnerClass: String =
    get(INSTRUMENTATION_RUNNER_CLASS) ?: throw RuntimeException("$INSTRUMENTATION_RUNNER_CLASS configuration is required")
  val testPackageId: String = get(TEST_PACKAGE_ID) ?: throw RuntimeException("$TEST_PACKAGE_ID configuration is required")
  val instrumentationTargetPackageId: String =
    get(INSTRUMENTATION_TARGET_PACKAGE_ID) ?: throw RuntimeException("$INSTRUMENTATION_TARGET_PACKAGE_ID configuration is required")
  val testedApplicationId: String = get(TESTED_APPLICATION_ID, AgpTestSuiteInput.TESTED_APPLICATION_ID) ?: instrumentationTargetPackageId

  val instrumentationArgs: Map<String, String> =
    get(INSTRUMENTATION_ARGS)
      ?.split(",")
      ?.mapNotNull { arg ->
        val parts = arg.split("=", limit = 2)
        if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
      }
      ?.toMap() ?: emptyMap()

  // We use fallback = false to distinguish between an explicit device-specific directory
  // and falling back to the base directory. This allows the engine to append the device ID
  // when no specific override is provided.
  fun getResultsDir(deviceSerial: String? = null): File? =
    get(RESULTS_DIR, AgpTestSuiteInput.RESULTS_DIR, deviceSerial, fallback = false)?.let { File(it) }

  fun getAdditionalTestOutputDirOnHost(deviceSerial: String? = null): File? =
    get(AndroidTestConfigurationKeys.ADDITIONAL_TEST_OUTPUT_DIR_ON_HOST, deviceSerial = deviceSerial, fallback = false)?.let { File(it) }

  fun getDeviceId(deviceSerial: String? = null): String? = get(AndroidTestConfigurationKeys.DEVICE_ID, deviceSerial = deviceSerial)

  val additionalTestOutputDirOnDevice: String? = get(AndroidTestConfigurationKeys.ADDITIONAL_TEST_OUTPUT_DIR_ON_DEVICE)
  val useTestStorageService: Boolean = get(AndroidTestConfigurationKeys.USE_TEST_STORAGE_SERVICE)?.toBoolean() ?: false

  fun isEmulatorControlEnabled(deviceSerial: String? = null): Boolean {
    val value = get(AndroidTestConfigurationKeys.EMULATOR_CONTROL_ENABLED, deviceSerial = deviceSerial)
    return value?.toBoolean() ?: false
  }

  val isTestCoverageEnabled: Boolean = get(AndroidTestConfigurationKeys.IS_TEST_COVERAGE_ENABLED)?.toBoolean() ?: false
  val coverageType: CoverageType =
    get(AndroidTestConfigurationKeys.COVERAGE_TYPE, AgpTestSuiteInput.COVERAGE_TYPE)?.let {
      try {
        CoverageType.valueOf(it.uppercase())
      } catch (e: Exception) {
        CoverageType.NONE
      }
    } ?: CoverageType.NONE
  val forceAotCompilation: Boolean = get(AndroidTestConfigurationKeys.FORCE_AOT_COMPILATION)?.toBoolean() ?: false

  /** Supported types of code coverage. */
  enum class CoverageType {
    NONE,
    ON_THE_FLY,
  }

  fun getCoverageDirOnHost(deviceSerial: String? = null): File? =
    get(AndroidTestConfigurationKeys.COVERAGE_DIR_ON_HOST, AgpTestSuiteInput.COVERAGE_DIR, deviceSerial)?.let { File(it, "coverage_data") }

  val coverageFileOnDevice: String? = get(AndroidTestConfigurationKeys.COVERAGE_FILE_ON_DEVICE)
  val coverageDirOnDevice: String? = get(AndroidTestConfigurationKeys.COVERAGE_DIR_ON_DEVICE)

  fun getTestedApks(deviceSerial: String? = null): List<File> = resolveApks(get(TESTED_APKS, AgpTestSuiteInput.TESTED_APKS, deviceSerial))

  fun getTestApks(deviceSerial: String? = null): List<File> = resolveApks(get(TEST_APKS, AgpTestSuiteInput.TEST_APKS, deviceSerial))

  fun getTestUtilApks(deviceSerial: String? = null): List<File> =
    resolveApks(get(TEST_UTIL_APKS, AgpTestSuiteInput.TEST_UTIL_APKS, deviceSerial))

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
