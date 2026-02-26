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

import java.io.File
import java.util.Properties

/** Standard AGP property keys used for [AndroidTestConfigurationKeys]. */
enum class AgpTestSuiteInput(val key: String) {
  /** Alias for [AndroidTestConfigurationKeys.ADB_PATH]. */
  ADB_EXECUTABLE("com.android.agp.test.ADB_EXECUTABLE"),

  /** Alias for [AndroidTestConfigurationKeys.AAPT2_PATH]. */
  AAPT2_EXECUTABLE("com.android.agp.test.AAPT2_EXECUTABLE"),

  /** Alias for [AndroidTestConfigurationKeys.DEVICE_SERIALS]. */
  SERIAL_IDS("com.android.junit.engine.serial.ids"),

  /** Alias for [AndroidTestConfigurationKeys.TESTED_APKS]. */
  TESTED_APKS("com.android.agp.test.TESTED_APKS"),

  /** Alias for [AndroidTestConfigurationKeys.TEST_APKS]. */
  TESTING_APK("com.android.agp.test.TESTING_APK"),

  /** Alias for [AndroidTestConfigurationKeys.INSTRUMENTATION_TARGET_PACKAGE_ID]. */
  TESTED_APPLICATION_ID("com.android.junit.engine.tested.application.id");

  /** Returns the property value from the input parameters file. */
  fun get(): String? = fileProperties.getProperty(key)

  companion object {
    /** The environment variable name that specifies the path to the input parameters file. */
    private const val INPUT_PARAMETERS_PATH_ENV_VAR = "com.android.junit.engine.input.parameters"

    /** Returns the property value for the given [key] from the input parameters file. */
    fun get(key: String): String? = fileProperties.getProperty(key)

    /** Properties loaded from the file specified by [INPUT_PARAMETERS_PATH_ENV_VAR]. */
    private val fileProperties: Properties by lazy {
      val properties = Properties()
      val path = System.getenv(INPUT_PARAMETERS_PATH_ENV_VAR)
      if (path != null) {
        val file = File(path)
        if (file.exists()) {
          file.inputStream().use { properties.load(it) }
        }
      }
      properties
    }
  }
}
