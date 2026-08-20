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

package com.android.tools.androidtest.testengine.adb

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.assertFailsWith
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** Unit tests for [DeviceApiLevelProvider]. */
class DeviceApiLevelProviderTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var adb: File
  private val deviceSerial = "test-serial"

  @Before
  fun setUp() {
    adb = tempFolder.newFile("adb")
  }

  private fun createAdbController(exitCode: Int, output: String = "", error: String = "", onRun: () -> Unit = {}): AdbController {
    val process =
      mock<Process> {
        on { it.exitValue() } doReturn exitCode
        on { it.inputStream } doReturn output.byteInputStream()
        on { it.errorStream } doReturn error.byteInputStream()
        on { it.outputStream } doReturn java.io.ByteArrayOutputStream()
        on { it.waitFor() } doReturn exitCode
        on { it.waitFor(any(), any()) } doReturn true
      }
    return AdbController(
      adb,
      processBuilder = {
        onRun()
        mock { on { start() } doReturn process }
      },
    )
  }

  @Test
  fun getDeviceApiLevel_success() {
    val controller = createAdbController(0, output = "33")
    val provider = DeviceApiLevelProvider(controller, deviceSerial)

    assertThat(provider.deviceApiLevel).isEqualTo(33)
  }

  @Test
  fun getDeviceApiLevel_cachesResult() {
    var commandRunCount = 0
    val controller = createAdbController(0, output = "33") { commandRunCount++ }
    val provider = DeviceApiLevelProvider(controller, deviceSerial)

    assertThat(provider.deviceApiLevel).isEqualTo(33)
    assertThat(provider.deviceApiLevel).isEqualTo(33)
    assertThat(provider.deviceApiLevel).isEqualTo(33)
    assertThat(commandRunCount).isEqualTo(1)
  }

  @Test
  fun getDeviceApiLevel_failedToParse_throwsException() {
    val controller = createAdbController(0, output = "not-a-number")
    val provider = DeviceApiLevelProvider(controller, deviceSerial)

    val exception = assertFailsWith<RuntimeException> { provider.deviceApiLevel }
    assertThat(exception.message).contains("Failed to parse device API level")
  }

  @Test
  fun getDeviceApiLevel_adbCommandFails_throwsException() {
    val controller = createAdbController(1, error = "device offline")
    val provider = DeviceApiLevelProvider(controller, deviceSerial)

    val exception = assertFailsWith<RuntimeException> { provider.deviceApiLevel }
    assertThat(exception.message).contains("Failed to get device API level")
  }
}
