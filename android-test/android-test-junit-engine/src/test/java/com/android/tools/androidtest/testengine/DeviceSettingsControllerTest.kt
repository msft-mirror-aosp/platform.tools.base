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

import com.android.tools.androidtest.testengine.AdbController.CommandResult
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit tests for [DeviceSettingsController]. */
class DeviceSettingsControllerTest {

  private lateinit var config: AndroidTestConfiguration
  private lateinit var adbController: AdbController
  private lateinit var controller: DeviceSettingsController
  private val serial = "test-device-123"

  @Before
  fun setUp() {
    config = mock()
    whenever(config.animationsDisabled).thenReturn(true)
    adbController = mock()
    controller = DeviceSettingsController(config, serial, adbController)
  }

  @Test
  fun preInstallationSetupAndPostTestCleanup_success() {
    whenever(adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "get", "global", "window_animation_scale")), isNull()))
      .thenReturn(CommandResult(0, "1.5\n", ""))
    whenever(adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "get", "global", "transition_animation_scale")), isNull()))
      .thenReturn(CommandResult(0, "null\n", ""))
    whenever(adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "get", "global", "animator_duration_scale")), isNull()))
      .thenReturn(CommandResult(1, "", "err"))

    whenever(adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "window_animation_scale", "0")), isNull()))
      .thenReturn(CommandResult(0, "", ""))
    whenever(
        adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "transition_animation_scale", "0")), isNull())
      )
      .thenReturn(CommandResult(0, "", ""))
    whenever(
        adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "animator_duration_scale", "0")), isNull())
      )
      .thenReturn(CommandResult(0, "", ""))

    controller.preInstallationSetup()

    whenever(
        adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "window_animation_scale", "1.5")), isNull())
      )
      .thenReturn(CommandResult(0, "", ""))
    whenever(
        adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "transition_animation_scale", "1.0")), isNull())
      )
      .thenReturn(CommandResult(0, "", ""))
    whenever(
        adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "animator_duration_scale", "1.0")), isNull())
      )
      .thenReturn(CommandResult(0, "", ""))

    controller.postTestCleanup()

    verify(adbController).runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "window_animation_scale", "1.5")), isNull())
    verify(adbController)
      .runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "transition_animation_scale", "1.0")), isNull())
    verify(adbController)
      .runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "animator_duration_scale", "1.0")), isNull())
  }

  @Test
  fun preInstallationSetup_failureWhenPuttingSetting() {
    whenever(adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "get", "global", "window_animation_scale")), isNull()))
      .thenReturn(CommandResult(0, "1.0", ""))
    whenever(adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "get", "global", "transition_animation_scale")), isNull()))
      .thenReturn(CommandResult(0, "1.0", ""))
    whenever(adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "get", "global", "animator_duration_scale")), isNull()))
      .thenReturn(CommandResult(0, "1.0", ""))

    whenever(adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "window_animation_scale", "0")), isNull()))
      .thenReturn(CommandResult(1, "", "error"))

    controller.preInstallationSetup()
    controller.postTestCleanup()

    verify(adbController, never())
      .runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "window_animation_scale", "1.0")), isNull())
  }

  @Test
  fun postTestCleanup_failureDoesNotThrow() {
    whenever(adbController.runAdbShellCommand(any(), any(), anyOrNull())).thenReturn(CommandResult(0, "1.0", ""))
    controller.preInstallationSetup()

    whenever(
        adbController.runAdbShellCommand(eq(serial), eq(listOf("settings", "put", "global", "window_animation_scale", "1.0")), isNull())
      )
      .thenReturn(CommandResult(1, "", "err"))

    controller.postTestCleanup()
  }

  @Test
  fun preInstallationSetupAndPostTestCleanup_skippedWhenAnimationsDisabledIsFalse() {
    whenever(config.animationsDisabled).thenReturn(false)
    controller.preInstallationSetup()
    controller.postTestCleanup()
    verify(adbController, never()).runAdbShellCommand(any(), any(), anyOrNull())
  }
}
