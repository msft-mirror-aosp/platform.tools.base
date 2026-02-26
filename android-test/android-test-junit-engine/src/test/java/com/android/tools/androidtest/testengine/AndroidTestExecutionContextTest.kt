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

import com.google.common.truth.Truth.assertThat
import java.util.Optional
import org.junit.Test
import org.junit.platform.engine.ConfigurationParameters
import org.junit.platform.engine.ExecutionRequest
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AndroidTestExecutionContextTest {

  @Test
  fun `AndroidTestConfiguration parses multiple device serials`() {
    val configParams = mock<ConfigurationParameters>()
    whenever(configParams.get(AndroidTestConfigurationKeys.ADB_PATH)).thenReturn(Optional.of("/path/to/adb"))
    whenever(configParams.get(AndroidTestConfigurationKeys.AAPT2_PATH)).thenReturn(Optional.of("/path/to/aapt2"))
    whenever(configParams.get(AndroidTestConfigurationKeys.DEVICE_SERIALS)).thenReturn(Optional.of("serial1, serial2 ,serial3"))
    whenever(configParams.get(AndroidTestConfigurationKeys.INSTRUMENTATION_RUNNER_CLASS)).thenReturn(Optional.of("com.example.Runner"))
    whenever(configParams.get(AndroidTestConfigurationKeys.INSTRUMENTATION_TARGET_PACKAGE_ID)).thenReturn(Optional.of("com.example.app"))

    val request = mock<ExecutionRequest>()
    whenever(request.configurationParameters).thenReturn(configParams)

    val context = AndroidTestExecutionContext(request)
    assertThat(context.configuration.deviceSerials).containsExactly("serial1", "serial2", "serial3").inOrder()
  }

  @Test
  fun `AndroidTestConfiguration parses single device serial`() {
    val configParams = mock<ConfigurationParameters>()
    whenever(configParams.get(AndroidTestConfigurationKeys.ADB_PATH)).thenReturn(Optional.of("/path/to/adb"))
    whenever(configParams.get(AndroidTestConfigurationKeys.AAPT2_PATH)).thenReturn(Optional.of("/path/to/aapt2"))
    whenever(configParams.get(AndroidTestConfigurationKeys.DEVICE_SERIALS)).thenReturn(Optional.of("serial1"))
    whenever(configParams.get(AndroidTestConfigurationKeys.INSTRUMENTATION_RUNNER_CLASS)).thenReturn(Optional.of("com.example.Runner"))
    whenever(configParams.get(AndroidTestConfigurationKeys.INSTRUMENTATION_TARGET_PACKAGE_ID)).thenReturn(Optional.of("com.example.app"))

    val request = mock<ExecutionRequest>()
    whenever(request.configurationParameters).thenReturn(configParams)

    val context = AndroidTestExecutionContext(request)
    assertThat(context.configuration.deviceSerials).containsExactly("serial1")
  }
}
