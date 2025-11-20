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
import com.android.adblib.ShellCommandOutputElement
import com.android.adblib.adbLogger
import com.android.adblib.selector
import com.android.adblib.serialNumber
import com.android.adblib.shellCommand
import com.android.adblib.withLineCollector
import com.android.adblib.withTextCollector
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.single

/** A set of functions to facilitate pairing AI glasses to a phone. */
class AiGlassesPairing(val session: AdbSession) {
  private val logger = adbLogger(session)

  suspend fun ConnectedDevice.hasGlassesCompanionApp(): Boolean {
    var hasCompanion = false
    var hasCore = false
    session.deviceServices
      .shellCommand(selector, "pm list packages $GLASSES_PKG")
      .withLineCollector()
      .execute()
      .collect {
        when (it) {
          is ShellCommandOutputElement.StdoutLine ->
            when (it.contents) {
              "package:$COMPANION_PKG" -> hasCompanion = true
              "package:$CORE_PKG" -> hasCore = true
            }
          else -> {}
        }
      }
    return hasCompanion && hasCore
  }

  suspend fun ConnectedDevice.pollPairingState(): String? {
    val command = "am broadcast -a $COMPANION_PKG.GET_PAIRING_STATE $COMPANION_PKG"

    logger.info { "Executing: $command" }
    return session.deviceServices
      .shellCommand(selector, command)
      .withLineCollector()
      .execute()
      .mapNotNull {
        when (it) {
          is ShellCommandOutputElement.StdoutLine -> {
            logger.debug { "Output: ${it.contents}" }
            "state=([\\w_]+)".toRegex().find(it.contents)?.groupValues?.get(1)
          }
          is ShellCommandOutputElement.StderrLine -> {
            if (it.contents.isNotBlank()) {
              logger.warn("Poll pairing state error output: ${it.contents}")
            }
            null
          }
          else -> null
        }
      }
      .firstOrNull()
  }

  private suspend fun ConnectedDevice.grantPermission(pkg: String, permission: String) {
    val command = "pm grant $pkg $permission"
    val output =
      session.deviceServices.shellCommand(selector, command).withTextCollector().execute().single()

    if (output.exitCode != 0) {
      throw ShellCommandException("Failed to execute \"$command\": ${output.stderr}")
    }
  }

  suspend fun ConnectedDevice.launchCompanionApp() {
    val command = "monkey -p $COMPANION_PKG -c android.intent.category.LAUNCHER 1"
    session.deviceServices.shellCommand(selector, command).withTextCollector().execute().single()
  }

  private suspend fun ConnectedDevice.clearPackage(pkg: String) {
    val command = "pm clear $pkg"
    logger.info { "Executing on $serialNumber: $command" }
    val output =
      session.deviceServices.shellCommand(selector, command).withTextCollector().execute().single()
    if (output.exitCode != 0) {
      throw ShellCommandException("Failed to execute \"$command\": ${output.stderr}")
    }
  }

  /**
   * Clears the state of the Glasses companion app and GlassesCore. This ensures that any polling of
   * the pairing process does not return the state of a prior pairing operation.
   */
  suspend fun ConnectedDevice.clearGlassesPackages() {
    clearPackage(COMPANION_PKG)
    clearPackage(CORE_PKG)
  }

  /**
   * Returns the number of paired bluetooth devices, by parsing the output of "dumpsys
   * bluetooth_manager", or null if we fail to find the number in the output.
   */
  suspend fun ConnectedDevice.getPairedBluetoothDeviceCount(): Int? {
    val command = "dumpsys bluetooth_manager | grep 'Bonded devices:'"
    var deviceCount: Int? = null
    session.deviceServices.shellCommand(selector, command).withLineCollector().execute().collect {
      when (it) {
        is ShellCommandOutputElement.StdoutLine ->
          "Bonded devices:\\s+(\\d+)".toRegex().find(it.contents)?.let {
            deviceCount = it.groupValues[1].toIntOrNull()
          }
        is ShellCommandOutputElement.StderrLine ->
          if (it.contents.isNotBlank()) {
            logger.warn("dumpsys bluetooth_manager error output: ${it.contents}")
          }
        else -> {}
      }
    }
    return deviceCount
  }

  suspend fun ConnectedDevice.getBluetoothAddress(): String? {
    val command = "settings get secure bluetooth_address"
    logger.info { "Executing on $serialNumber: $command" }
    val result =
      session.deviceServices.shellCommand(selector, command).withTextCollector().execute().single()
    if (result.stderr.isNotEmpty()) {
      logger.warn("Get Bluetooth address command error output: ${result.stderr}")
    }
    return result.stdout.trim().takeIf {
      it.matches("([0-9A-Fa-f]{2}:){5}([0-9A-Fa-f]{2})".toRegex())
    }
  }

  private suspend fun ConnectedDevice.sendPairingCommand(
    glassesBluetoothAddress: String,
    useCdm: Boolean,
  ) {
    val command =
      """am broadcast -a $COMPANION_PKG.ASSISTED_PAIR --es "address" "$glassesBluetoothAddress" --ez "auto_cdm" $useCdm -p $COMPANION_PKG"""
    logger.info { "Executing on $serialNumber: $command" }
    val output =
      session.deviceServices.shellCommand(selector, command).withTextCollector().execute().single()
    logger.debug { output.stdout }
    if (output.stderr.isNotEmpty()) {
      logger.warn("Pairing command error output: ${output.stderr}")
    }

    if (output.exitCode != 0) {
      throw ShellCommandException("Failed to send pairing broadcast. Exit code: ${output.exitCode}")
    }
  }

  suspend fun ConnectedDevice.sendUnpairCommand() {
    val command = "am broadcast -a $COMPANION_PKG.UNPAIR -p $COMPANION_PKG"
    val output =
      session.deviceServices.shellCommand(selector, command).withTextCollector().execute().single()
    logger.debug { output.stdout }
    if (output.stderr.isNotEmpty()) {
      logger.warn("Unpair command error output: ${output.stderr}")
    }

    if (output.exitCode != 0) {
      throw ShellCommandException("Failed to unpair. Exit code: ${output.exitCode}")
    }
  }

  fun ConnectedDevice.pairToGlasses(
    glassesBluetoothAddress: String,
    useCdm: Boolean,
  ): Flow<String> = flow {
    grantPermission(COMPANION_PKG, "android.permission.NEARBY_WIFI_DEVICES")
    grantPermission(COMPANION_PKG, "android.permission.BLUETOOTH_CONNECT")
    grantPermission(CORE_PKG, "android.permission.ACCESS_FINE_LOCATION")

    launchCompanionApp()

    emit("POLLING")
    while (true) {
      val state =
        pollPairingState()
          ?: run {
            emit("POLLING_FAILED")
            return@flow
          }

      emit(state)
      when (state) {
        "IDLE" -> {
          sendPairingCommand(glassesBluetoothAddress, useCdm)
          delay(4.seconds)
        }
        in TERMINAL_STATES -> return@flow
        else -> delay(2.seconds)
      }
    }
  }

  companion object {

    private const val GLASSES_PKG = "com.google.android.glasses"
    private const val COMPANION_PKG = "com.google.android.glasses.companion"
    private const val CORE_PKG = "com.google.android.glasses.core"

    val TERMINAL_STATES =
      setOf(
        "ERROR",
        "PAIRED",
        "POLLING_FAILED",
        "UI_CDM_FAILED",
        "WORKER_BOND_FAILED",
        "WORKER_CONNECTION_FAILED",
        "WORKER_CANCELLED",
      )
  }
}

class ShellCommandException(message: String) : Exception(message)
