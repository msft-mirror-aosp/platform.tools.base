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

import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.AAPT_PATH
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.ADB_PATH
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.APK_INSTALL_OPTIONS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.DEVICE_SERIAL
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTALL_TIMEOUT_MS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTRUMENTATION_RUNNER_CLASS
import com.android.tools.androidtest.testengine.AndroidTestConfigurationKeys.INSTRUMENTATION_TARGET_PACKAGE_ID
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

  private fun get(key: String): java.util.Optional<String> {
    return config.get(key).or { java.util.Optional.ofNullable(System.getProperty(key)) }
  }

  val adb: File = get(ADB_PATH).map { File(it) }.orElseThrow { RuntimeException("$ADB_PATH configuration is required") }
  val aapt: File = get(AAPT_PATH).map { File(it) }.orElseThrow { RuntimeException("$AAPT_PATH configuration is required") }
  val deviceSerial: String = get(DEVICE_SERIAL).orElseThrow { RuntimeException("$DEVICE_SERIAL configuration is required") }
  val installTimeoutMs: Long = get(INSTALL_TIMEOUT_MS).map { it.toLong() }.orElse(0L)

  val testedApks: List<File> = get(TESTED_APKS).map { it.split(",").map { path -> File(path.trim()) } }.orElse(listOf())
  val testApks: List<File> = get(TEST_APKS).map { it.split(",").map { path -> File(path.trim()) } }.orElse(listOf())
  val testUtilApks: List<File> = get(TEST_UTIL_APKS).map { it.split(",").map { path -> File(path.trim()) } }.orElse(listOf())
  val apkInstallOptions: List<String> = get(APK_INSTALL_OPTIONS).map { it.split(",").map { opt -> opt.trim() } }.orElse(listOf())
  val uninstallApksAfterTests: Boolean = get(UNINSTALL_AFTER_TESTS).map { it.toBoolean() }.orElse(true)

  val instrumentationRunnerClass: String =
    get(INSTRUMENTATION_RUNNER_CLASS).orElseThrow { RuntimeException("$INSTRUMENTATION_RUNNER_CLASS configuration is required") }
  val instrumentationTargetPackageId: String =
    get(INSTRUMENTATION_TARGET_PACKAGE_ID).orElseThrow { RuntimeException("$INSTRUMENTATION_TARGET_PACKAGE_ID configuration is required") }
}
