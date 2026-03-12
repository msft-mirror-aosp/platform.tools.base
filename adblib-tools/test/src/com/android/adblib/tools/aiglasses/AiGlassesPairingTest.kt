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
package com.android.adblib.tools.aiglasses

import com.android.adblib.ConnectedDevice
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.testutils.waitForOnlineConnectedDevice
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.FakeAdbServer
import com.android.fakeadbserver.ShellProtocolType
import com.android.fakeadbserver.services.ShellCommandOutput
import com.android.fakeadbserver.services.StatusWriter
import com.android.fakeadbserver.shellcommandhandlers.ShellHandler
import com.android.sdklib.AndroidApiLevel
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class AiGlassesPairingTest {

  @JvmField @Rule val fakeAdbRule = FakeAdbServerProviderRule()

  private val deviceServices
    get() = fakeAdbRule.adbSession.deviceServices

  private val broadcastResponses = ConcurrentHashMap<String, String>()
  private val dumpsysResponses = ConcurrentHashMap<String, String>()

  @Test
  fun checkBondState_sendsCorrectCommandAndParsesResult() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val glassesAddress = "AA:BB:CC:DD:EE:FF"
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.CHECK_BOND_STATE --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"TRULY_BONDED\"\n",
    )

    val result = AiGlassesPairing(fakeAdbRule.adbSession).run { device.checkBondState(glassesAddress) }

    assertEquals("TRULY_BONDED", result)
  }

  @Test
  fun checkBondState_propagatesIoException() {
    runBlockingWithTimeout {
      val device = createConnectedDevice()
      val glassesAddress = "AA:BB:CC:DD:EE:FF"

      // Register a handler that fails for the specific command to simulate IOException
      listOf(ShellProtocolType.SHELL, ShellProtocolType.SHELL_V2).forEach { type ->
        fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
          0,
          object : ShellHandler(type) {
            override fun shouldExecute(shellCommand: String, shellCommandArgs: String?): Boolean {
              return shellCommand == "am" && shellCommandArgs?.contains("CHECK_BOND_STATE") == true
            }

            override fun execute(
              fakeAdbServer: FakeAdbServer,
              statusWriter: StatusWriter,
              shellCommandOutput: ShellCommandOutput,
              device: DeviceState,
              shellCommand: String,
              shellCommandArgs: String?,
            ) {
              throw IOException("Simulated Connection Failure")
            }
          },
        )
      }

      try {
        AiGlassesPairing(fakeAdbRule.adbSession).run { device.checkBondState(glassesAddress) }
        fail("Expected DeviceConnectionException")
      } catch (e: DeviceConnectionException) {
        assertEquals("device1", e.serialNumber)
        assertEquals(
          "am broadcast -a com.google.android.glasses.companion.CHECK_BOND_STATE --es address \"AA:BB:CC:DD:EE:FF\" -p com.google.android.glasses.companion",
          e.command,
        )
      }
    }
  }

  @Test
  fun checkConnectionState_sendsCorrectCommandAndParsesResult() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val glassesAddress = "AA:BB:CC:DD:EE:FF"
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.CHECK_CONNECTION_STATE --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"CONNECTED\"\n",
    )

    val result = AiGlassesPairing(fakeAdbRule.adbSession).run { device.checkConnectionState(glassesAddress) }

    assertEquals("CONNECTED", result)
  }

  @Test
  fun getDisplayMode_sendsCorrectCommandAndParsesResult() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_DISPLAY_MODE -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"AUDIO_ONLY\"\n",
    )

    val result = AiGlassesPairing(fakeAdbRule.adbSession).run { device.getDisplayMode() }

    assertEquals("AUDIO_ONLY", result)
  }

  @Test
  fun setDisplayMode_sendsCorrectCommandAndVerifiesSuccess() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.SET_DISPLAY_MODE --ez enable_audio_only true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully set audio only mode to: true\"\n",
    )

    AiGlassesPairing(fakeAdbRule.adbSession).run { device.setDisplayMode(true) }

    assertEquals(
      listOf(
        "am broadcast -a com.google.android.glasses.companion.SET_DISPLAY_MODE --ez enable_audio_only true -p com.google.android.glasses.companion"
      ),
      executedCommands,
    )
  }

  @Test(expected = ShellCommandException::class)
  fun setDisplayMode_throwsExceptionOnFailure() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.SET_DISPLAY_MODE --ez enable_audio_only true -p com.google.android.glasses.companion",
      "Broadcast completed: result=0, data=\"Failure\"\n",
    )

    AiGlassesPairing(fakeAdbRule.adbSession).run { device.setDisplayMode(true) }
  }

  @Test
  fun checkCompanionAppInForeground_returnsTrueIfAppInForeground() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/MainActivity}\n",
    )

    val result = AiGlassesPairing(fakeAdbRule.adbSession).run { device.checkCompanionAppInForeground() }

    assertEquals(true, result)
    assertEquals(listOf("dumpsys window displays"), executedDumpsysCommands)
  }

  @Test
  fun checkCompanionAppInForeground_returnsFalseIfAppNotInForeground() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    registerDumpsysResponse("dumpsys window displays", "  mCurrentFocus=Window{12345 u0 com.another.app/MainActivity}\n")
    registerDumpsysResponse("dumpsys activity activities", "  mResumedActivity: ActivityRecord{... com.another.app/MainActivity}\n")

    val result = AiGlassesPairing(fakeAdbRule.adbSession).run { device.checkCompanionAppInForeground() }

    assertEquals(false, result)
  }

  @Test
  fun checkCompanionAppInForeground_fallsBackToActivityActivities() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    registerDumpsysResponse("dumpsys window displays", "  mCurrentFocus=Window{12345 u0 com.another.app/MainActivity}\n")
    registerDumpsysResponse(
      "dumpsys activity activities",
      "  ResumedActivity: ActivityRecord{... com.google.android.glasses.companion/MainActivity}\n",
    )

    val result = pairing.run { device.checkCompanionAppInForeground() }

    assertEquals(true, result)
    assertEquals(listOf("dumpsys window displays", "dumpsys activity activities"), executedDumpsysCommands)
  }

  @Test
  fun pairToGlasses_waitsForForegroundAndStartsPairing() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val glassesAddress = "AA:BB:CC:DD:EE:FF"

    // Initial state: not in foreground
    registerDumpsysResponse("dumpsys window displays", "  mCurrentFocus=Window{12345 u0 com.another.app/MainActivity}\n")
    registerDumpsysResponse("dumpsys activity activities", "  ResumedActivity: ActivityRecord{... com.another.app/MainActivity}\n")

    // Commands emitted by pairToGlasses
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE com.google.android.glasses.companion",
      "state=IDLE\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)
    AiGlassesPairing.POLLING_TIMEOUT = 5.seconds

    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/MainActivity}\n",
    )

    registerBroadcastResponse("pm grant com.google.android.glasses.companion android.permission.NEARBY_WIFI_DEVICES", "")
    registerBroadcastResponse("pm grant com.google.android.glasses.companion android.permission.BLUETOOTH_CONNECT", "")
    registerBroadcastResponse("pm grant com.google.android.glasses.core android.permission.ACCESS_FINE_LOCATION", "")
    registerBroadcastResponse("monkey -p com.google.android.glasses.companion -c android.intent.category.LAUNCHER 1", "")

    val states = mutableListOf<String>()
    val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }
    try {
      flow.collect {
        states.add(it)
        if (it == "IDLE") {
          throw Exception("Stop test")
        }
      }
    } catch (e: Exception) {
      if (e.message != "Stop test") {
        throw e
      }
    }

    assertEquals(listOf(AiGlassesPairing.AWAITING_FOREGROUND, "POLLING", "IDLE"), states)
  }

  @Test
  fun checkCompanionAppInForeground_throwsIoExceptionOnCommFailure() {
    runBlockingWithTimeout {
      val device = createConnectedDevice()

      listOf(ShellProtocolType.SHELL, ShellProtocolType.SHELL_V2).forEach { type ->
        fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
          0,
          object : ShellHandler(type) {
            override fun shouldExecute(shellCommand: String, shellCommandArgs: String?): Boolean {
              return shellCommand == "dumpsys" && shellCommandArgs?.contains("window") == true
            }

            override fun execute(
              fakeAdbServer: FakeAdbServer,
              statusWriter: StatusWriter,
              shellCommandOutput: ShellCommandOutput,
              device: DeviceState,
              shellCommand: String,
              shellCommandArgs: String?,
            ) {
              throw IOException("Simulated Connection Failure")
            }
          },
        )
      }

      try {
        AiGlassesPairing(fakeAdbRule.adbSession).run { device.checkCompanionAppInForeground() }
        fail("Expected DeviceConnectionException")
      } catch (e: DeviceConnectionException) {
        assertEquals("device1", e.serialNumber)
        assertEquals("dumpsys window displays", e.command)
      }
    }
  }

  private val executedCommands = CopyOnWriteArrayList<String>()
  private val executedDumpsysCommands = CopyOnWriteArrayList<String>()

  private suspend fun createConnectedDevice(): ConnectedDevice {
    val fakeDevice =
      fakeAdbRule.fakeAdb.connectDevice("device1", "test1", "test2", "model", AndroidApiLevel(36), DeviceState.HostConnectionType.USB)
    fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
    // Register handlers (SHELL and SHELL_V2 to be safe)
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL, "am", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL_V2, "am", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL, "dumpsys", dumpsysResponses, executedDumpsysCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL_V2, "dumpsys", dumpsysResponses, executedDumpsysCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL, "pm", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL_V2, "pm", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL, "monkey", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL_V2, "monkey", broadcastResponses, executedCommands),
    )
    return deviceServices.session.waitForOnlineConnectedDevice(fakeDevice.deviceId)
  }

  private fun registerDumpsysResponse(command: String, response: String) {
    dumpsysResponses[command] = response
  }

  private fun registerBroadcastResponse(command: String, response: String) {
    broadcastResponses[command] = response
  }

  /** Fake handler that intercepts a target command matching registered responses. */
  class FakeShellCommandHandler(
    shellProtocolType: ShellProtocolType,
    private val targetCommand: String,
    private val responses: Map<String, String>,
    private val commandTracker: MutableList<String>,
  ) : ShellHandler(shellProtocolType) {

    override fun shouldExecute(shellCommand: String, shellCommandArgs: String?): Boolean {
      if (shellCommand != targetCommand) return false
      // Reconstruct the full command to check against responses
      val fullCommand = if (shellCommandArgs == null) shellCommand else "$shellCommand $shellCommandArgs"
      return responses.containsKey(fullCommand)
    }

    override fun execute(
      fakeAdbServer: FakeAdbServer,
      statusWriter: StatusWriter,
      shellCommandOutput: ShellCommandOutput,
      device: DeviceState,
      shellCommand: String,
      shellCommandArgs: String?,
    ) {
      statusWriter.writeOk()
      val fullCommand = if (shellCommandArgs == null) shellCommand else "$shellCommand $shellCommandArgs"
      commandTracker.add(fullCommand)
      val response = responses[fullCommand] ?: ""
      shellCommandOutput.writeStdout(response)
      shellCommandOutput.writeExitCode(0)
    }
  }
}
