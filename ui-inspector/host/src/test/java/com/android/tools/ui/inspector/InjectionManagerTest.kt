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
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InjectionManagerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var fakeSession: FakeAdbSession
  private lateinit var testDeviceServices: TestAdbDeviceServices
  private lateinit var testSession: TestAdbSession
  private lateinit var dummyAgent: Path
  private lateinit var dummyJar: Path
  private lateinit var agentPathResolver: (String) -> Path

  private val deviceSerial = "123"
  private val packageName = "com.example"

  @Before
  fun setUp() {
    fakeSession = FakeAdbSession()
    testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    testSession = TestAdbSession(fakeSession, testDeviceServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()

    agentPathResolver = { abi -> dummyAgent }
  }

  @Test
  fun testInjectAndAttach() = runTest {
    val injectionManager = InjectionManager(testSession, agentPathResolver, dummyJar)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    // Mock expected shell commands for the injection flow
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "getprop ro.product.cpu.abi", "arm64-v8a\n")
    val setupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, setupCmd, "")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cmd activity attach-agent $packageName /data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar",
      "",
    )

    injectionManager.injectAndAttach(deviceSerial, packageName)

    // Verify that syncSend was called with correct parameters
    assertThat(testDeviceServices.recordedSyncSends).hasSize(2)
    val paths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(paths.sorted()).containsExactly("/data/local/tmp/lib_ui_inspector_agent.so", "/data/local/tmp/lib_ui_inspector_service.jar")
  }

  @Test
  fun testInjectAndAttach_CommandFails() = runTest {
    val injectionManager = InjectionManager(testSession, agentPathResolver, dummyJar)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "getprop ro.product.cpu.abi", "arm64-v8a\n")

    val setupCmd =
      "run-as com.example sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar'"

    // Configure this command to FAIL!
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      setupCmd,
      stdout = "",
      stderr = "Package is not debuggable",
      exitCode = 1,
    )

    try {
      injectionManager.injectAndAttach(deviceSerial, packageName)
      fail("Expected IllegalStateException was not thrown")
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("Command '$setupCmd' failed with exit code 1. Stderr: Package is not debuggable")
    }

    // Verify that both files were pushed to /data/local/tmp before the setup command failed.
    // The push operations occur concurrently and complete before the copy/setup step is executed.
    assertThat(testDeviceServices.recordedSyncSends).hasSize(2)
    val paths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(paths.sorted()).containsExactly("/data/local/tmp/lib_ui_inspector_agent.so", "/data/local/tmp/lib_ui_inspector_service.jar")
  }
}
