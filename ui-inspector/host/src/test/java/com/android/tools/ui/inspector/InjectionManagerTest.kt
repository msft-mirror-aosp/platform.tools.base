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

package com.android.tools.ui.inspector

import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.testing.FakeAdbSession
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class InjectionManagerTest {

  private lateinit var fakeSession: FakeAdbSession
  private lateinit var testDeviceServices: TestAdbDeviceServices
  private lateinit var testSession: TestAdbSession
  private lateinit var tempDir: Path
  private lateinit var dummyAgent: Path
  private lateinit var injectionManager: InjectionManager

  private val deviceSerial = "123"
  private val packageName = "com.example"

  @Before
  fun setUp() {
    fakeSession = FakeAdbSession()
    testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    testSession = TestAdbSession(fakeSession, testDeviceServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    tempDir = Files.createTempDirectory("agent_test")
    dummyAgent = tempDir.resolve("lib_ui_inspector_agent.so")
    Files.createFile(dummyAgent)

    val agentPathResolver: (String) -> Path = { abi -> dummyAgent }
    injectionManager = InjectionManager(testSession, agentPathResolver)
  }

  @After
  fun tearDown() {
    Files.deleteIfExists(dummyAgent)
    Files.deleteIfExists(tempDir)
  }

  @Test
  fun testInjectAndAttach() = runBlocking {
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    // Mock expected shell commands for the injection flow
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "getprop ro.product.cpu.abi", "arm64-v8a\n")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "run-as $packageName sh -c 'cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so'",
      "",
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName chmod 444 lib_ui_inspector_agent.so", "")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cmd activity attach-agent $packageName /data/data/$packageName/lib_ui_inspector_agent.so",
      "",
    )

    injectionManager.injectAndAttach(deviceSerial, packageName)

    // Verify that syncSend was called with correct parameters
    assertThat(testDeviceServices.recordedSyncSends).hasSize(1)
    val syncCall = testDeviceServices.recordedSyncSends[0]
    assertThat(syncCall.remoteFilePath).isEqualTo("/data/local/tmp/lib_ui_inspector_agent.so")
  }

  @Test
  fun testInjectAndAttach_CommandFails() = runBlocking {
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "getprop ro.product.cpu.abi", "arm64-v8a\n")

    // Configure this command to FAIL!
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "run-as com.example sh -c 'cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so'",
      stdout = "",
      stderr = "Package is not debuggable",
      exitCode = 1,
    )

    try {
      injectionManager.injectAndAttach(deviceSerial, packageName)
      fail("Expected IllegalStateException was not thrown")
    } catch (e: IllegalStateException) {
      assertThat(e.message)
        .isEqualTo(
          "Command 'run-as com.example sh -c 'cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so'' failed with exit code 1. Stderr: Package is not debuggable"
        )
    }

    // Verify that syncSend was called even if a later step failed
    assertThat(testDeviceServices.recordedSyncSends).hasSize(1)
    val syncCall = testDeviceServices.recordedSyncSends[0]
    assertThat(syncCall.remoteFilePath).isEqualTo("/data/local/tmp/lib_ui_inspector_agent.so")
  }
}
