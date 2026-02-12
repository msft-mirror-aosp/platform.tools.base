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
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiGlassesPairingTest {

  @JvmField @Rule val fakeAdbRule = FakeAdbServerProviderRule()

  private val deviceServices
    get() = fakeAdbRule.adbSession.deviceServices

  private val broadcastResponses = ConcurrentHashMap<String, String>()

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

  @Test(expected = java.io.IOException::class)
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
              throw java.io.IOException("Simulated Connection Failure")
            }
          },
        )
      }

      AiGlassesPairing(fakeAdbRule.adbSession).run { device.checkBondState(glassesAddress) }
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

  private val executedCommands = java.util.concurrent.CopyOnWriteArrayList<String>()

  private suspend fun createConnectedDevice(): com.android.adblib.ConnectedDevice {
    val fakeDevice =
      fakeAdbRule.fakeAdb.connectDevice("device1", "test1", "test2", "model", AndroidApiLevel(36), DeviceState.HostConnectionType.USB)
    fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
    // Register handlers (SHELL and SHELL_V2 to be safe)
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(0, FakeBroadcastHandler(ShellProtocolType.SHELL, broadcastResponses, executedCommands))
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeBroadcastHandler(ShellProtocolType.SHELL_V2, broadcastResponses, executedCommands),
    )
    return deviceServices.session.waitForOnlineConnectedDevice(fakeDevice.deviceId)
  }

  private fun registerBroadcastResponse(command: String, response: String) {
    broadcastResponses[command] = response
  }

  /** Fake handler that intercepts `am` commands matching registered responses. */
  class FakeBroadcastHandler(
    shellProtocolType: ShellProtocolType,
    private val responses: Map<String, String>,
    private val commandTracker: MutableList<String>,
  ) : ShellHandler(shellProtocolType) {

    override fun shouldExecute(shellCommand: String, shellCommandArgs: String?): Boolean {
      if (shellCommand != "am") return false
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
