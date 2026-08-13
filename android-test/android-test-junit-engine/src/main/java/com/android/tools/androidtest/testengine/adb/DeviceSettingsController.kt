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

import com.android.tools.androidtest.testengine.config.AndroidTestConfiguration
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A controller for managing device global settings during test execution.
 *
 * This class handles modifying device settings before tests run (such as disabling window animations for test stability) and internally
 * records their previous state so they can be restored automatically when tests finish.
 *
 * @property config The test execution configuration.
 * @property deviceSerial The serial number of the target Android device.
 * @property adbController Helper used to execute `adb shell settings` commands.
 * @property logger Logger instance for recording actions and warnings.
 */
class DeviceSettingsController(
  private val config: AndroidTestConfiguration,
  private val deviceSerial: String,
  private val adbController: AdbController = AdbController(config.adb),
  private val logger: Logger = Logger.getLogger(DeviceSettingsController::class.java.name),
) {

  /** Holds the original animation scale values retrieved from the device. */
  private data class SavedAnimationSettings(
    val windowAnimationScale: String,
    val transitionAnimationScale: String,
    val animatorDurationScale: String,
  )

  private var savedAnimationSettings: SavedAnimationSettings? = null

  /**
   * Executes pre-installation setup on the target device.
   *
   * Currently retrieves existing global animation scale settings and disables window, transition, and animator duration scales by setting
   * them to 0.
   */
  fun preInstallationSetup() {
    if (!config.animationsDisabled) return
    logger.info("Disabling window animations on device $deviceSerial")
    try {
      val window = getSetting("window_animation_scale")
      val transition = getSetting("transition_animation_scale")
      val animator = getSetting("animator_duration_scale")

      setSetting("window_animation_scale", "0")
      setSetting("transition_animation_scale", "0")
      setSetting("animator_duration_scale", "0")

      savedAnimationSettings = SavedAnimationSettings(window, transition, animator)
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Failed to disable window animations on device $deviceSerial", e)
      savedAnimationSettings = null
    }
  }

  /**
   * Restores device settings to their previous state after test execution completes.
   *
   * Restores the animation scales recorded during [preInstallationSetup].
   */
  fun postTestCleanup() {
    val settings = savedAnimationSettings ?: return
    logger.info("Restoring window animations on device $deviceSerial to $settings")
    try {
      setSetting("window_animation_scale", settings.windowAnimationScale)
      setSetting("transition_animation_scale", settings.transitionAnimationScale)
      setSetting("animator_duration_scale", settings.animatorDurationScale)
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Failed to restore window animations on device $deviceSerial", e)
    } finally {
      savedAnimationSettings = null
    }
  }

  /** Retrieves a global setting value from the device via `adb shell settings get global`. Defaults to "1.0" if unset or null. */
  private fun getSetting(key: String): String {
    val result = adbController.runAdbShellCommand(deviceSerial, listOf("settings", "get", "global", key))
    val output = if (result.exitCode == 0) result.output.trim() else ""
    return if (output.isEmpty() || output.equals("null", ignoreCase = true)) "1.0" else output
  }

  /** Sets a global setting value on the device via `adb shell settings put global`. */
  private fun setSetting(key: String, value: String) {
    val result = adbController.runAdbShellCommand(deviceSerial, listOf("settings", "put", "global", key, value))
    if (result.exitCode != 0) {
      throw RuntimeException("Failed to set setting $key to $value (exit code: ${result.exitCode}, err: ${result.errorOutput})")
    }
  }
}
