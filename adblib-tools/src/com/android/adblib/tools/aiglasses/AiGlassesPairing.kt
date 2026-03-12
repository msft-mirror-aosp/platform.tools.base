/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.adblib.tools.aiglasses

import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.ShellCommandOutput
import com.android.adblib.ShellCommandOutputElement
import com.android.adblib.adbLogger
import com.android.adblib.serialNumber
import com.android.adblib.shell
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.any
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retry

/** A set of functions to facilitate pairing AI glasses to a phone. */
class AiGlassesPairing(val session: AdbSession) {
  private val logger = adbLogger(session)

  /**
   * Checks if both the Glasses companion app and GlassesCore are installed on the device.
   *
   * @throws IOException if a communication error occurs with the device.
   */
  suspend fun ConnectedDevice.hasGlassesCompanionApp(): Boolean {
    var hasCompanion = false
    var hasCore = false
    runCatchingIoException("pm list packages $GLASSES_PKG") {
      shell.executeAsLines(it).collect { element ->
        when (element) {
          is ShellCommandOutputElement.StdoutLine ->
            when (element.contents) {
              "package:$COMPANION_PKG" -> hasCompanion = true
              "package:$CORE_PKG" -> hasCore = true
            }
          else -> {}
        }
      }
    }
    return hasCompanion && hasCore
  }

  /**
   * Polls the current pairing state from the companion app.
   *
   * @return The pairing state as a string, or null if the command fails or returns no state.
   * @throws IOException if a communication error occurs with the device.
   */
  suspend fun ConnectedDevice.pollPairingState(): String? {
    val command = "am broadcast -a $COMPANION_PKG.GET_PAIRING_STATE $COMPANION_PKG"

    logger.info { "Executing: $command" }
    return runCatchingIoException(command) {
      shell
        .executeAsLines(it)
        .mapNotNull { element ->
          when (element) {
            is ShellCommandOutputElement.StdoutLine -> {
              logger.debug { "Output: ${element.contents}" }
              "state=([\\w_]+)".toRegex().find(element.contents)?.groupValues?.get(1)
            }
            is ShellCommandOutputElement.StderrLine -> {
              if (element.contents.isNotBlank()) {
                logger.warn("Poll pairing state error output: ${element.contents}")
              }
              null
            }
            else -> null
          }
        }
        .firstOrNull()
    }
  }

  private suspend fun ConnectedDevice.grantPermission(pkg: String, permission: String) {
    val command = "pm grant $pkg $permission"
    val output = runCatchingIoException(command) { shell.executeAsText(it) }

    if (output.exitCode != 0) {
      throw ShellCommandException("Failed to execute \"$command\": ${output.stderr}")
    }
  }

  /**
   * Launches the Glasses companion app on the device.
   *
   * @throws IOException if a communication error occurs with the device.
   */
  suspend fun ConnectedDevice.launchCompanionApp() {
    val command = "monkey -p $COMPANION_PKG -c android.intent.category.LAUNCHER 1"
    runCatchingIoException(command) { shell.executeAsText(it) }
  }

  /**
   * Checks if the Glasses companion app is in the foreground.
   *
   * @return true if the companion app is in the foreground, false otherwise.
   * @throws IOException if a communication error occurs with the device.
   */
  suspend fun ConnectedDevice.checkCompanionAppInForeground(): Boolean {
    return checkAppInForeground(COMPANION_PKG)
  }

  private suspend fun ConnectedDevice.checkAppInForeground(pkg: String): Boolean {
    val commands = listOf("dumpsys window displays", "dumpsys activity activities")
    val focusKeywords =
      listOf("mCurrentFocus", "mFocusedApp", "mResumedActivity", "mFocusedWindow", "topActivity", "topResumedActivity", "ResumedActivity")

    return commands.any { command ->
      runCatchingIoException(command) {
        shell.executeAsLines(it).any { element ->
          if (element is ShellCommandOutputElement.StdoutLine) {
            val line = element.contents
            val containsFocusKeyword = focusKeywords.any { keyword -> line.contains("$keyword=") || line.contains("$keyword:") }
            containsFocusKeyword && line.contains(pkg)
          } else {
            false
          }
        }
      }
    }
  }

  private suspend fun ConnectedDevice.clearPackage(pkg: String) {
    val command = "pm clear $pkg"
    logger.info { "Executing on $serialNumber: $command" }
    val output = runCatchingIoException(command) { shell.executeAsText(it) }
    if (output.exitCode != 0) {
      throw ShellCommandException("Failed to execute \"$command\": ${output.stderr}")
    }
  }

  /**
   * Clears the state of the Glasses companion app and GlassesCore. This ensures that any polling of the pairing process does not return the
   * state of a prior pairing operation.
   *
   * @throws ShellCommandException if the "pm clear" command fails (exit code != 0).
   * @throws IOException if a communication error occurs with the device.
   */
  suspend fun ConnectedDevice.clearGlassesPackages() {
    clearPackage(COMPANION_PKG)
    clearPackage(CORE_PKG)
  }

  /**
   * Returns the number of paired bluetooth devices, by parsing the output of "dumpsys bluetooth_manager", or null if we fail to find the
   * number in the output.
   *
   * @throws IOException if a communication error occurs with the device.
   */
  suspend fun ConnectedDevice.getPairedBluetoothDeviceCount(): Int? {
    val command = "dumpsys bluetooth_manager | grep 'Bonded devices:'"
    var deviceCount: Int? = null
    runCatchingIoException(command) {
      shell.executeAsLines(it).collect { element ->
        when (element) {
          is ShellCommandOutputElement.StdoutLine ->
            "Bonded devices:\\s+(\\d+)".toRegex().find(element.contents)?.let { deviceCount = it.groupValues[1].toIntOrNull() }
          is ShellCommandOutputElement.StderrLine ->
            if (element.contents.isNotBlank()) {
              logger.warn("dumpsys bluetooth_manager error output: ${element.contents}")
            }
          else -> {}
        }
      }
    }
    return deviceCount
  }

  /**
   * Retrieves the Bluetooth address of the device.
   *
   * @return The Bluetooth MAC address in the format "XX:XX:XX:XX:XX:XX", or null if not found or invalid.
   * @throws IOException if a communication error occurs with the device.
   */
  suspend fun ConnectedDevice.getBluetoothAddress(): String? {
    val command = "settings get secure bluetooth_address"
    logger.info { "Executing on $serialNumber: $command" }
    val result = runCatchingIoException(command) { shell.executeAsText(it) }
    if (result.stderr.isNotEmpty()) {
      logger.warn("Get Bluetooth address command error output: ${result.stderr}")
    }
    return result.stdout.trim().takeIf { it.matches("([0-9A-Fa-f]{2}:){5}([0-9A-Fa-f]{2})".toRegex()) }
  }

  /**
   * Checks the bond state of the glasses with the specified Bluetooth address.
   *
   * @param glassesBluetoothAddress The Bluetooth address of the glasses.
   * @return The bond state, or null if the command output cannot be parsed.
   * @throws IOException if a communication error occurs with the device or the broadcast command fails.
   */
  suspend fun ConnectedDevice.checkBondState(glassesBluetoothAddress: String): String? {
    val command = """am broadcast -a $COMPANION_PKG.CHECK_BOND_STATE --es address "$glassesBluetoothAddress" -p $COMPANION_PKG"""
    return parseBroadcastResultData(executeBroadcastCommand(command).stdout)
  }

  /**
   * Checks the connection state of the glasses with the specified Bluetooth address.
   *
   * @param glassesBluetoothAddress The Bluetooth address of the glasses.
   * @return The connection state, or null if the command output cannot be parsed.
   * @throws IOException if a communication error occurs with the device or the broadcast command fails.
   */
  suspend fun ConnectedDevice.checkConnectionState(glassesBluetoothAddress: String): String? {
    val command = """am broadcast -a $COMPANION_PKG.CHECK_CONNECTION_STATE --es address "$glassesBluetoothAddress" -p $COMPANION_PKG"""
    return parseBroadcastResultData(executeBroadcastCommand(command).stdout)
  }

  /**
   * Sets the display mode for the glasses.
   *
   * @param enableAudioOnly If true, enables audio-only mode. If false, enables full display mode.
   * @throws ShellCommandException if the broadcast command returns a failure result code.
   * @throws IOException if a communication error occurs with the device.
   */
  suspend fun ConnectedDevice.setDisplayMode(enableAudioOnly: Boolean) {
    val command = """am broadcast -a $COMPANION_PKG.SET_DISPLAY_MODE --ez enable_audio_only $enableAudioOnly -p $COMPANION_PKG"""
    val output = executeBroadcastCommand(command)

    // Expected output format: Broadcast completed: result=-1, data="Successfully set audio only mode
    // to: true"
    // We check if it contains the success message.
    val result = parseBroadcastResultCode(output.stdout)
    if (result != -1) {
      throw ShellCommandException("Failed to set display mode. Result code: $result. Output: ${output.stdout}")
    }
  }

  /**
   * Retrieves the current display mode of the glasses.
   *
   * @return The display mode, or null if the command output cannot be parsed.
   * @throws IOException if a communication error occurs with the device or the broadcast command fails.
   */
  suspend fun ConnectedDevice.getDisplayMode(): String? {
    val command = "am broadcast -a $COMPANION_PKG.GET_DISPLAY_MODE -p $COMPANION_PKG"
    return parseBroadcastResultData(executeBroadcastCommand(command).stdout)
  }

  private fun parseBroadcastResultData(stdout: String): String? {
    // Output format: Broadcast completed: result=-1, data="VALUE"
    return "data=\"([^\"]+)\"".toRegex().find(stdout)?.groupValues?.get(1)
  }

  private fun parseBroadcastResultCode(stdout: String): Int? {
    // Output format: Broadcast completed: result=-1, data="VALUE"
    return "result=(-?\\d+)".toRegex().find(stdout)?.groupValues?.get(1)?.toIntOrNull()
  }

  private suspend fun ConnectedDevice.executeBroadcastCommand(command: String): ShellCommandOutput {
    logger.info { "Executing on $serialNumber: $command" }
    val output = runCatchingIoException(command) { shell.executeAsText(it) }
    logger.debug { output.stdout }
    if (output.stderr.isNotEmpty()) {
      logger.warn("Command '$command' error output: ${output.stderr}")
    }
    return output
  }

  private suspend fun ConnectedDevice.sendPairingCommand(glassesBluetoothAddress: String, useCdm: Boolean) {
    val command =
      """am broadcast -a $COMPANION_PKG.ASSISTED_PAIR --es address "$glassesBluetoothAddress" --ez auto_cdm $useCdm -p $COMPANION_PKG"""
    val output = executeBroadcastCommand(command)

    if (output.exitCode != 0) {
      throw ShellCommandException("Failed to send pairing broadcast. Exit code: ${output.exitCode}")
    }
  }

  suspend fun ConnectedDevice.sendUnpairCommand() {
    val command = "am broadcast -a $COMPANION_PKG.UNPAIR -p $COMPANION_PKG"
    val output = executeBroadcastCommand(command)

    if (output.exitCode != 0) {
      throw ShellCommandException("Failed to unpair. Exit code: ${output.exitCode}")
    }
  }

  /**
   * Polls until the companion app is in the foreground or the [POLLING_TIMEOUT] expires.
   *
   * @return true if the companion app is in the foreground, false if the timeout expires.
   */
  private suspend fun ConnectedDevice.waitForCompanionAppInForeground(): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < POLLING_TIMEOUT) {
      if (checkCompanionAppInForeground()) {
        return true
      }
      delay(POLLING_INTERVAL)
    }
    return false
  }

  /**
   * Polls the pairing state until it returns a non-null value or the [POLLING_TIMEOUT] expires.
   *
   * @return The pairing state, or null if the timeout expires. Possible states include: "IDLE", "WORKER_STARTED", "WORKER_BONDING",
   *   "UI_CDM_SCANNING", "UI_CDM_ASSOCIATING", "UI_CDM_ASSOCIATION_FAILED", "UI_WAITING_FOR_WORKER", "WORKER_CONNECTING",
   *   "WORKER_GLASSES_CORE_CONNECTION_FAILED", "WORKER_GLASSES_CORE_CONNECTED", "PAIRED", "WORKER_BOND_FAILED", "WORKER_CONNECTION_FAILED",
   *   "WORKER_CANCELLED", "ERROR".
   */
  private suspend fun ConnectedDevice.waitForPairingState(): String? {
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < POLLING_TIMEOUT) {
      val state = pollPairingState()
      if (state != null) {
        return state
      }
      delay(POLLING_INTERVAL)
    }
    return null
  }

  /**
   * Initiates the pairing process with the glasses and emits the pairing state updates.
   *
   * @param glassesBluetoothAddress The Bluetooth address of the glasses to pair with.
   * @param useCdm Whether to use the Companion Device Manager pairing flow.
   * @return A flow emitting the pairing state updates.
   * @throws IOException if a communication error occurs with the device.
   * @throws ShellCommandException if a shell command fails (exit code != 0).
   */
  fun ConnectedDevice.pairToGlasses(glassesBluetoothAddress: String, useCdm: Boolean): Flow<String> =
    flow {
        grantPermission(COMPANION_PKG, "android.permission.NEARBY_WIFI_DEVICES")
        grantPermission(COMPANION_PKG, "android.permission.BLUETOOTH_CONNECT")
        grantPermission(CORE_PKG, "android.permission.ACCESS_FINE_LOCATION")

        launchCompanionApp()

        // Wait for the app to appear in the foreground
        emit(AWAITING_FOREGROUND)
        if (!waitForCompanionAppInForeground()) {
          emit("POLLING_FAILED")
          return@flow
        }

        // Wait 3 seconds once it is in the foreground
        delay(3.seconds)

        emit("POLLING")
        while (true) {
          val state = waitForPairingState()

          if (state == null) {
            emit("POLLING_FAILED")
            return@flow
          }

          emit(state)
          when (state) {
            "IDLE" -> {
              sendPairingCommand(glassesBluetoothAddress, useCdm)
              delay(PAIRING_COMMAND_DELAY)
            }
            in TERMINAL_STATES -> return@flow
            else -> delay(POLLING_INTERVAL)
          }
        }
      }
      .retry(1) { cause -> cause is IOException || cause is ShellCommandException }
      .catch { cause ->
        if (cause is IOException && cause !is DeviceConnectionException) {
          throw DeviceConnectionException(serialNumber, "pairToGlasses flow", cause)
        }
        throw cause
      }

  private suspend inline fun <T> ConnectedDevice.runCatchingIoException(command: String, block: (String) -> T): T {
    try {
      return block(command)
    } catch (e: IOException) {
      throw DeviceConnectionException(serialNumber, command, e)
    }
  }

  companion object {

    private const val GLASSES_PKG = "com.google.android.glasses"
    private const val COMPANION_PKG = "com.google.android.glasses.companion"
    private const val CORE_PKG = "com.google.android.glasses.core"

    // Time to wait for the pairing state to change after sending the pairing command
    private val PAIRING_COMMAND_DELAY = 4.seconds

    // Time to wait between polling attempts
    private val POLLING_INTERVAL = 2.seconds

    // Max time to retry polling if it fails (returns null) continuously
    internal var POLLING_TIMEOUT = 30.seconds

    // All possible terminal states for the pairing process
    val TERMINAL_STATES =
      setOf(
        "ERROR",
        "PAIRED",
        "POLLING_FAILED",
        "UI_CDM_ASSOCIATION_FAILED",
        "WORKER_BOND_FAILED",
        "WORKER_CONNECTION_FAILED",
        "WORKER_GLASSES_CORE_CONNECTION_FAILED",
        "WORKER_CANCELLED",
      )

    const val AWAITING_FOREGROUND = "AWAITING_FOREGROUND"
  }
}

class ShellCommandException(message: String) : Exception(message)

class DeviceConnectionException(val serialNumber: String, val command: String, cause: Throwable) :
  IOException("Connection to $serialNumber lost while executing '$command'.", cause)
