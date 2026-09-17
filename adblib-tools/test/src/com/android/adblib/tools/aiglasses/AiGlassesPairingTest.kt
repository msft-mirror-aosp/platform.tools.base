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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AiGlassesPairingTest {

  @JvmField @Rule val fakeAdbRule = FakeAdbServerProviderRule()

  private val deviceServices
    get() = fakeAdbRule.adbSession.deviceServices

  private val broadcastResponses = ConcurrentHashMap<String, String>()
  private val dumpsysResponses = ConcurrentHashMap<String, String>()

  @Before
  fun setUp() {
    AiGlassesPairing.setDelaysForTest(
      foregroundDelay = Duration.ZERO,
      pairingCommandDelay = Duration.ZERO,
      pollingInterval = Duration.ZERO,
      pollingTimeout = 5.seconds,
    )
  }

  @After
  fun tearDown() {
    AiGlassesPairing.setDelaysForTest()
  }

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
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=IDLE\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/MainActivity}\n",
    )

    setupDeviceMocks()

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

  // TODO: Remove this test once http://b/505111138 is fixed
  @Test
  fun pairToGlasses_tapsButtonWhenStuckInIdle() = runBlockingWithTimeout {
    val device = createConnectedDevice(api = 37)
    val glassesAddress = "AA:BB:CC:DD:EE:FF"

    // Setup to simulate stuck in IDLE
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=IDLE\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    // Mock location hardening
    setupDeviceMocks()

    // Mock focus-driven UI bypass
    registerBroadcastResponse("input keyevent KEYCODE_TAB", "")
    registerBroadcastResponse("input keyevent KEYCODE_DPAD_CENTER", "")

    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    // Mock foreground check to succeed immediately
    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/com.google.android.glasses.companion.setup.ui.SetupActivity}\n",
    )

    val states = mutableListOf<String>()
    val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }

    var idleCount = 0
    flow
      .takeWhile {
        states.add(it)
        if (it == "IDLE") {
          idleCount++
        }
        idleCount < 4
      }
      .collect()

    // Verify that focus navigation was executed
    assertTrue(executedCommands.contains("input keyevent KEYCODE_TAB"))
    assertTrue(executedCommands.contains("input keyevent KEYCODE_DPAD_CENTER"))
  }

  // TODO: Remove this test once http://b/505111138 is fixed
  @Test
  fun pairToGlasses_tapsButtonWhenStuckInUiCdmAssociating() = runBlockingWithTimeout {
    val device = createConnectedDevice(api = 37)
    val glassesAddress = "AA:BB:CC:DD:EE:FF"

    // Setup to simulate stuck in UI_CDM_ASSOCIATING
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=UI_CDM_ASSOCIATING\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    // Mock location hardening
    setupDeviceMocks()

    // Mock focus-driven UI bypass
    registerBroadcastResponse("input keyevent KEYCODE_TAB", "")
    registerBroadcastResponse("input keyevent KEYCODE_DPAD_CENTER", "")

    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    // Mock foreground check to succeed immediately
    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/com.google.android.glasses.companion.setup.ui.SetupActivity}\n",
    )

    val states = mutableListOf<String>()
    val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }

    var cdmCount = 0
    flow
      .takeWhile {
        states.add(it)
        if (it == "UI_CDM_ASSOCIATING") {
          cdmCount++
        }
        cdmCount < 3
      }
      .collect()

    // Verify that focus navigation was executed
    assertTrue(executedCommands.contains("input keyevent KEYCODE_TAB"))
    assertTrue(executedCommands.contains("input keyevent KEYCODE_DPAD_CENTER"))
  }

  // TODO: Remove this test once http://b/505111138 is fixed
  @Test
  fun pairToGlasses_idleStateThrottlesFocusNavigationTaps() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val glassesAddress = "AA:BB:CC:DD:EE:FF"

    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=IDLE\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    setupDeviceMocks()

    // Mock focus-driven UI bypass
    registerBroadcastResponse("input keyevent KEYCODE_TAB", "")
    registerBroadcastResponse("input keyevent KEYCODE_DPAD_CENTER", "")

    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/com.google.android.glasses.companion.setup.ui.SetupActivity}\n",
    )

    val states = mutableListOf<String>()
    val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }

    var idleCount = 0
    flow
      .takeWhile {
        states.add(it)
        if (it == "IDLE") {
          idleCount++
        }
        idleCount < 5
      }
      .collect()

    // Key events should be sent at idleCount == 2 and idleCount == 4 (total 2 times for 5 idle polls)
    val tabCount = executedCommands.count { it == "input keyevent KEYCODE_TAB" }
    val dpadCenterCount = executedCommands.count { it == "input keyevent KEYCODE_DPAD_CENTER" }
    assertEquals(2, tabCount)
    assertEquals(2, dpadCenterCount)
  }

  // TODO: Remove this test once http://b/505111138 is fixed
  @Test
  fun pairToGlasses_doesNotTapButtonWhenSetupActivityNotInForeground() = runBlockingWithTimeout {
    val device = createConnectedDevice(api = 37)
    val glassesAddress = "AA:BB:CC:DD:EE:FF"

    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=IDLE\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    setupDeviceMocks()

    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/com.google.android.glasses.companion.MainActivity}\n",
    )

    val states = mutableListOf<String>()
    val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }

    var idleCount = 0
    flow
      .takeWhile {
        states.add(it)
        if (it == "IDLE") {
          idleCount++
        }
        idleCount < 4
      }
      .collect()

    // Verify that focus navigation was NOT executed
    assertFalse(executedCommands.contains("input keyevent KEYCODE_TAB"))
    assertFalse(executedCommands.contains("input keyevent KEYCODE_DPAD_CENTER"))
  }

  @Test
  fun pairToGlasses_enablesBluetoothIfDisabled() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val glassesAddress = "AA:BB:CC:DD:EE:FF"

    // Mock the enable command
    registerBroadcastResponse("cmd bluetooth_manager enable", "")

    // Initial state for pairing
    registerDumpsysResponse("dumpsys window displays", "  mCurrentFocus=Window{12345 u0 com.another.app/MainActivity}\n")
    registerDumpsysResponse("dumpsys activity activities", "  ResumedActivity: ActivityRecord{... com.another.app/MainActivity}\n")
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=IDLE\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    setupDeviceMocks()
    // Override the default mock from setupDeviceMocks
    registerBroadcastResponse("settings get global bluetooth_on", "0")

    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/MainActivity}\n",
    )

    val states = mutableListOf<String>()
    val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }
    flow
      .takeWhile {
        states.add(it)
        it != "IDLE"
      }
      .collect()

    // Verify that the enable command was executed
    assertTrue(executedCommands.contains("cmd bluetooth_manager enable"))
    // Verify we checked the status
    assertTrue(executedCommands.contains("settings get global bluetooth_on"))
  }

  @Test
  fun pairToGlasses_doesNotEnableBluetoothIfEnabled() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val glassesAddress = "AA:BB:CC:DD:EE:FF"

    // Initial state for pairing
    registerDumpsysResponse("dumpsys window displays", "  mCurrentFocus=Window{12345 u0 com.another.app/MainActivity}\n")
    registerDumpsysResponse("dumpsys activity activities", "  ResumedActivity: ActivityRecord{... com.another.app/MainActivity}\n")
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=IDLE\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    setupDeviceMocks() // This also sets it to "1"

    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/MainActivity}\n",
    )

    val states = mutableListOf<String>()
    val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }
    flow
      .takeWhile {
        states.add(it)
        it != "IDLE"
      }
      .collect()

    // Verify that the enable command was NOT executed
    assertFalse(executedCommands.contains("cmd bluetooth_manager enable"))
    // Verify we checked the status
    assertTrue(executedCommands.contains("settings get global bluetooth_on"))
  }

  @Test
  fun sendUnpairCommand_sendsCorrectCommand() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val glassesAddress = "AA:BB:CC:DD:EE:FF"
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1\n",
    )

    AiGlassesPairing(fakeAdbRule.adbSession).run { device.sendUnpairCommand(glassesAddress) }

    assertEquals(
      listOf(
        "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion"
      ),
      executedCommands,
    )
  }

  @Test
  fun pairToGlasses_warnsAndContinuesWhenPreCleanseUnpairFails() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val glassesAddress = "AA:BB:CC:DD:EE:FF"

    registerDumpsysResponse("dumpsys window displays", "  mCurrentFocus=Window{12345 u0 com.another.app/MainActivity}\n")
    registerDumpsysResponse("dumpsys activity activities", "  ResumedActivity: ActivityRecord{... com.another.app/MainActivity}\n")
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=IDLE\n",
    )
    // Simulate unpair command failure (e.g. broadcast failure / non-zero exit)
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Error: Broadcast failed\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    setupDeviceMocks()
    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/MainActivity}\n",
    )

    val states = mutableListOf<String>()
    val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }
    var idleCount = 0
    flow
      .takeWhile {
        states.add(it)
        if (it == "IDLE") {
          idleCount++
        }
        idleCount < 2
      }
      .collect()

    // Verify pairing command was still executed despite unpair error
    assertTrue(
      executedCommands.contains(
        "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion"
      )
    )
  }

  @Test
  fun pollPairingState_sendsBroadcastWithoutAddressFilter() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=IDLE\n",
    )

    val state = AiGlassesPairing(fakeAdbRule.adbSession).run { device.pollPairingState() }

    assertEquals("IDLE", state)
    assertEquals(
      listOf("am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion"),
      executedCommands,
    )
  }

  @Test
  fun pairToGlasses_idleStatePollingDoesNotReissuePairingCommand() = runBlockingWithTimeout {
    val device = createConnectedDevice()
    val glassesAddress = "AA:BB:CC:DD:EE:FF"

    registerDumpsysResponse("dumpsys window displays", "  mCurrentFocus=Window{12345 u0 com.another.app/MainActivity}\n")
    registerDumpsysResponse("dumpsys activity activities", "  ResumedActivity: ActivityRecord{... com.another.app/MainActivity}\n")
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
      "state=IDLE\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1\n",
    )
    registerBroadcastResponse(
      "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
      "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
    )

    setupDeviceMocks()
    val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

    registerDumpsysResponse(
      "dumpsys window displays",
      "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/MainActivity}\n",
    )

    val states = mutableListOf<String>()
    val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }
    var idleCount = 0
    flow
      .takeWhile {
        states.add(it)
        if (it == "IDLE") {
          idleCount++
        }
        idleCount < 4
      }
      .collect()

    // UNPAIR and ASSISTED_PAIR should each only be executed ONCE during setup, not re-triggered in the polling loop
    val unpairCount = executedCommands.count { it.contains("UNPAIR") }
    val assistedPairCount = executedCommands.count { it.contains("ASSISTED_PAIR") }
    assertEquals(1, unpairCount)
    assertEquals(1, assistedPairCount)
  }

  @Test
  fun pairToGlasses_exceedingMaxIdlePollsEmitsPollingFailed() =
    runBlockingWithTimeout(java.time.Duration.ofSeconds(60)) {
      val device = createConnectedDevice()
      val glassesAddress = "AA:BB:CC:DD:EE:FF"

      registerDumpsysResponse("dumpsys window displays", "  mCurrentFocus=Window{12345 u0 com.another.app/MainActivity}\n")
      registerDumpsysResponse("dumpsys activity activities", "  ResumedActivity: ActivityRecord{... com.another.app/MainActivity}\n")
      registerBroadcastResponse(
        "am broadcast -a com.google.android.glasses.companion.GET_PAIRING_STATE -p com.google.android.glasses.companion",
        "state=IDLE\n",
      )
      registerBroadcastResponse(
        "am broadcast -a com.google.android.glasses.companion.UNPAIR --es address \"$glassesAddress\" -p com.google.android.glasses.companion",
        "Broadcast completed: result=-1\n",
      )
      registerBroadcastResponse(
        "am broadcast -a com.google.android.glasses.companion.ASSISTED_PAIR --es address \"$glassesAddress\" --ez auto_cdm true --ez force true -p com.google.android.glasses.companion",
        "Broadcast completed: result=-1, data=\"Successfully sent pairing broadcast\"\n",
      )

      setupDeviceMocks()
      val pairing = AiGlassesPairing(fakeAdbRule.adbSession)

      registerDumpsysResponse(
        "dumpsys window displays",
        "  mCurrentFocus=Window{12345 u0 com.google.android.glasses.companion/MainActivity}\n",
      )

      val states = mutableListOf<String>()
      val flow = pairing.run { device.pairToGlasses(glassesAddress, true) }
      flow.collect { states.add(it) }

      val expectedStates = listOf(AiGlassesPairing.AWAITING_FOREGROUND, "POLLING") + List(15) { "IDLE" } + listOf("POLLING_FAILED")
      assertEquals(expectedStates, states)
    }

  private fun setupDeviceMocks() {
    registerBroadcastResponse("settings get global bluetooth_on", "1")
    registerBroadcastResponse("cmd location set-location-enabled true", "")
    registerBroadcastResponse("pm grant com.google.android.glasses.companion android.permission.NEARBY_WIFI_DEVICES", "")
    registerBroadcastResponse("pm grant com.google.android.glasses.companion android.permission.BLUETOOTH_CONNECT", "")
    registerBroadcastResponse("pm grant com.google.android.glasses.core android.permission.ACCESS_FINE_LOCATION", "")
    registerBroadcastResponse("monkey -p com.google.android.glasses.companion -c android.intent.category.LAUNCHER 1", "")
  }

  private val executedCommands = CopyOnWriteArrayList<String>()
  private val executedDumpsysCommands = CopyOnWriteArrayList<String>()

  private suspend fun createConnectedDevice(api: Int = 37): ConnectedDevice {
    val fakeDevice =
      fakeAdbRule.fakeAdb.connectDevice("device1", "test1", "test2", "model", AndroidApiLevel(api), DeviceState.HostConnectionType.USB)
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
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL, "input", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL_V2, "input", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL, "settings", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL_V2, "settings", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL, "cmd", broadcastResponses, executedCommands),
    )
    fakeAdbRule.fakeAdb.fakeAdbServer.handlers.add(
      0,
      FakeShellCommandHandler(ShellProtocolType.SHELL_V2, "cmd", broadcastResponses, executedCommands),
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
