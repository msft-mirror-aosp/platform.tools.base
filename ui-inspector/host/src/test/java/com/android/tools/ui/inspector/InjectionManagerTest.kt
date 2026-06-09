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
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InjectionManagerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var fakeSession: FakeAdbSession
  private lateinit var testDeviceServices: TestAdbDeviceServices
  private lateinit var testHostServices: TestAdbHostServices
  private lateinit var testSession: com.android.adblib.AdbSession
  private lateinit var dummyAgent: Path
  private lateinit var dummyJar: Path
  private lateinit var dummyPayload: Path
  private lateinit var agentPathResolver: (String) -> Path

  private val deviceSerial = "123"
  private val packageName = "com.example"

  @Before
  fun setUp() {
    fakeSession = FakeAdbSession()
    testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    testHostServices = TestAdbHostServices(fakeSession.hostServices)
    testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
    dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()

    agentPathResolver = { abi -> dummyAgent }

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "settings put global debug_view_attributes 1", "")
  }

  @Test
  fun testInjectAndAttach() = runTest {
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    // Mock expected shell commands for the injection flow
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "getprop ro.product.cpu.abi", "arm64-v8a\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pidof $packageName", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    val setupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, setupCmd, "")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cmd activity attach-agent $packageName \"/data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar;/data/data/$packageName/lib_ui_inspector_payload.jar;1234\"",
      "",
    )
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cat /proc/net/unix | grep ui_inspector_1234 || true",
      "ui_inspector_1234\n",
    )

    val port = injectionManager.injectAndAttach()
    assertThat(port).isEqualTo("12345")

    // Verify that syncSend was called with correct parameters
    assertThat(testDeviceServices.recordedSyncSends).hasSize(3)
    val paths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(paths.sorted())
      .containsExactly(
        "/data/local/tmp/lib_ui_inspector_agent.so",
        "/data/local/tmp/lib_ui_inspector_payload.jar",
        "/data/local/tmp/lib_ui_inspector_service.jar",
      )
  }

  @Test
  fun testInjectAndAttach_CommandFails() = runTest {
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "getprop ro.product.cpu.abi", "arm64-v8a\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pidof $packageName", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val setupCmd =
      "run-as com.example sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"

    // Configure this command to FAIL!
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      setupCmd,
      stdout = "",
      stderr = "Package is not debuggable",
      exitCode = 1,
    )

    try {
      injectionManager.injectAndAttach()
      fail("Expected IllegalStateException was not thrown")
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("Command '$setupCmd' failed with exit code 1. Stderr: Package is not debuggable")
    }

    // Verify that both files were pushed to /data/local/tmp before the setup command failed.
    // The push operations occur concurrently and complete before the copy/setup step is executed.
    assertThat(testDeviceServices.recordedSyncSends).hasSize(3)
    val paths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(paths.sorted())
      .containsExactly(
        "/data/local/tmp/lib_ui_inspector_agent.so",
        "/data/local/tmp/lib_ui_inspector_payload.jar",
        "/data/local/tmp/lib_ui_inspector_service.jar",
      )
  }

  @Test
  fun testPushInspectorPayload() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    // Mock injectAndAttach dependencies so we can initialize appDataDir
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "getprop ro.product.cpu.abi", "arm64-v8a\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pidof $packageName", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cat /proc/net/unix | grep ui_inspector_1234 || true",
      "ui_inspector_1234\n",
    )
    val agentSetupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, agentSetupCmd, "")
    val attachCmd =
      "cmd activity attach-agent $packageName \"/data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar;/data/data/$packageName/lib_ui_inspector_payload.jar;1234\""
    fakeSession.deviceServices.configureShellCommand(deviceSelector, attachCmd, "")

    // Initialize appDataDir
    injectionManager.injectAndAttach()

    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val inspector = InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar)

    val inspectorSetupCmd =
      "run-as $packageName sh -c '" +
        "rm -f my-inspector.jar && " +
        "cat /data/local/tmp/my-inspector.jar > my-inspector.jar && " +
        "chmod 444 my-inspector.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, inspectorSetupCmd, "")

    val remotePath = injectionManager.pushInspectorPayload(inspector)

    assertThat(remotePath).isEqualTo("/data/data/$packageName/my-inspector.jar")

    // Verify the inspector jar was pushed to tmp
    val pushedPaths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(pushedPaths).contains("/data/local/tmp/my-inspector.jar")
  }

  @Test
  fun testQueryAppDataDir_Fails() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    fakeSession.deviceServices.configureShellCommand(deviceSelector, "getprop ro.product.cpu.abi", "arm64-v8a\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pidof $packageName", "1234\n")

    // Configure run-as pwd to fail
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "run-as $packageName pwd",
      stdout = "",
      stderr = "run-as: package not debuggable",
      exitCode = 1,
    )

    try {
      injectionManager.injectAndAttach()
      fail("Expected IllegalStateException for failing run-as pwd")
    } catch (e: IllegalStateException) {
      assertThat(e.message)
        .contains(
          "Failed to access the application '$packageName'. Please make sure the app is installed, debuggable, and running under the current user."
        )
    }
  }

  @Test
  fun testGetPid_Fails() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    fakeSession.deviceServices.configureShellCommand(deviceSelector, "getprop ro.product.cpu.abi", "arm64-v8a\n")

    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    // Configure pidof to fail
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pidof $packageName", stdout = "", stderr = "", exitCode = 1)

    try {
      injectionManager.injectAndAttach()
      fail("Expected IllegalStateException for failing pidof")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("The application '$packageName' is not running on the device. Please start the app and try again.")
    }
  }

  @Test
  fun testInvalidPackageName_Throws() {
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        InjectionManager(testSession, deviceSerial, "com.example; id", agentPathResolver, dummyJar, dummyPayload)
      }
    assertThat(exception.message).contains("Invalid package name")
  }

  @Test
  fun testLongPackageName_Throws() {
    val longPackageName = "a".repeat(256)
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        InjectionManager(testSession, deviceSerial, longPackageName, agentPathResolver, dummyJar, dummyPayload)
      }
    assertThat(exception.message).contains("Invalid package name")
  }

  @Test
  fun testInvalidSerial_Throws() {
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        InjectionManager(testSession, "serial; rm -rf /", packageName, agentPathResolver, dummyJar, dummyPayload)
      }
    assertThat(exception.message).contains("Invalid serial number")
  }
}
