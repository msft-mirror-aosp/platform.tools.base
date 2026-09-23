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
import com.android.tools.ui.inspector.device.PackageUid
import com.android.tools.ui.inspector.device.TOP_ACTIVITY_SHELL_COMMAND
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceResolutionTest {

  private val fakeSession = FakeAdbSession()
  private val selector = DeviceSelector.fromSerialNumber("abc")

  private fun configureTopActivityOutput(stdout: String, exitCode: Int = 0) {
    fakeSession.deviceServices.configureShellCommand(selector, TOP_ACTIVITY_SHELL_COMMAND, stdout, exitCode = exitCode)
  }

  private fun configureProcessUid(pid: String, uid: String) {
    fakeSession.deviceServices.configureShellCommand(selector, "ps -o UID= -p $pid", "$uid\n")
  }

  private fun configurePackageUids(vararg packageUids: PackageUid) {
    val output =
      packageUids.joinToString(separator = "\n", postfix = if (packageUids.isEmpty()) "" else "\n") {
        "package:${it.packageName} uid:${it.uid}"
      }
    fakeSession.deviceServices.configureShellCommand(selector, "pm list packages -U --user 0", output)
  }

  @Test
  fun testResolveSoleOnlineDeviceReturnsItsSerial() {
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo("abc", DeviceState.ONLINE)), emptyList())

    val serial = runBlocking { resolveSoleOnlineDevice(fakeSession) }

    assertThat(serial).isEqualTo("abc")
  }

  @Test
  fun testResolveSoleOnlineDeviceIgnoresNonOnlineDevices() {
    fakeSession.hostServices.devices =
      DeviceList(listOf(DeviceInfo("offline-device", DeviceState.OFFLINE), DeviceInfo("online-device", DeviceState.ONLINE)), emptyList())

    val serial = runBlocking { resolveSoleOnlineDevice(fakeSession) }

    assertThat(serial).isEqualTo("online-device")
  }

  @Test
  fun testResolveSoleOnlineDeviceFailsWithNoDevices() {
    fakeSession.hostServices.devices = DeviceList(emptyList(), emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveSoleOnlineDevice(fakeSession) } }

    assertThat(exception).hasMessageThat().contains("No connected devices found")
  }

  @Test
  fun testResolveSoleOnlineDeviceFailsWithNoOnlineDevices() {
    fakeSession.hostServices.devices =
      DeviceList(listOf(DeviceInfo("xyz", DeviceState.OFFLINE), DeviceInfo("abc", DeviceState.UNAUTHORIZED)), emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveSoleOnlineDevice(fakeSession) } }

    assertThat(exception).hasMessageThat().contains("No online devices found")
    assertThat(exception).hasMessageThat().contains("abc (unauthorized), xyz (offline)")
  }

  @Test
  fun testResolveSoleOnlineDeviceFailsWithMultipleOnlineDevices() {
    fakeSession.hostServices.devices =
      DeviceList(listOf(DeviceInfo("device-b", DeviceState.ONLINE), DeviceInfo("device-a", DeviceState.ONLINE)), emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveSoleOnlineDevice(fakeSession) } }

    assertThat(exception).hasMessageThat().isEqualTo("Multiple online devices found: device-a, device-b.")
  }

  @Test
  fun testResolveForegroundPackageUsesUidForFullyQualifiedProcessName() {
    configureTopActivityOutput("    APP  UID 1234:com.example.uiprocess/u0a123 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configurePackageUids(PackageUid("com.example.qaviews", 10123))

    val packageName = runBlocking { resolveForegroundPackage(fakeSession, "abc") }

    assertThat(packageName).isEqualTo("com.example.qaviews")
  }

  @Test
  fun testResolveForegroundPackageUsesUidForColonSuffixProcessName() {
    configureTopActivityOutput("    APP  UID 1234:com.example:ui/u0a123 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configurePackageUids(PackageUid("com.example", 10123))

    val packageName = runBlocking { resolveForegroundPackage(fakeSession, "abc") }

    assertThat(packageName).isEqualTo("com.example")
  }

  @Test
  fun testResolveForegroundPackageAcceptsMultipleProcessesWithSameUid() {
    configureTopActivityOutput(
      "    APP  UID 1234:com.example/u0a123 (top-activity)\n    APP  UID 5678:com.example:render/u0a123 (top-activity)\n"
    )
    configureProcessUid("1234", "10123")
    configureProcessUid("5678", "10123")
    configurePackageUids(PackageUid("com.example", 10123))

    val packageName = runBlocking { resolveForegroundPackage(fakeSession, "abc") }

    assertThat(packageName).isEqualTo("com.example")
  }

  @Test
  fun testResolveForegroundPackageFailsWhenNoForegroundApp() {
    // grep exits with code 1 and empty stdout when nothing matches.
    configureTopActivityOutput("", exitCode = 1)

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveForegroundPackage(fakeSession, "abc") } }

    assertThat(exception).hasMessageThat().contains("Could not determine the foreground app")
  }

  @Test
  fun testResolveForegroundPackageFailsWithMultipleForegroundApps() {
    configureTopActivityOutput("    APP  UID 1234:com.second/u0a123 (top-activity)\n    APP  UID 5678:com.first/u0a124 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configureProcessUid("5678", "10456")
    configurePackageUids(PackageUid("com.second", 10123), PackageUid("com.first", 10456))

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveForegroundPackage(fakeSession, "abc") } }

    assertThat(exception).hasMessageThat().isEqualTo("Multiple foreground apps found: com.first, com.second.")
  }

  @Test
  fun testResolveForegroundPackageFailsWhenUidMatchesMultiplePackages() {
    configureTopActivityOutput("    APP  UID 1234:arbitrary.process/u0a123 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configurePackageUids(PackageUid("com.second", 10123), PackageUid("com.first", 10123))

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveForegroundPackage(fakeSession, "abc") } }

    assertThat(exception).hasMessageThat().isEqualTo("The foreground process UID matches multiple packages: com.first, com.second.")
  }

  @Test
  fun testResolveForegroundPackageFailsWhenProcessUidIsMissing() {
    configureTopActivityOutput("    APP  UID 1234:com.example/u0a123 (top-activity)\n")
    fakeSession.deviceServices.configureShellCommand(selector, "ps -o UID= -p 1234", "")

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveForegroundPackage(fakeSession, "abc") } }

    assertThat(exception).hasMessageThat().contains("Could not determine the foreground app")
  }

  @Test
  fun testResolveForegroundPackageFailsWhenUidPackageMappingIsMissing() {
    configureTopActivityOutput("    APP  UID 1234:com.example/u0a123 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configurePackageUids()

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveForegroundPackage(fakeSession, "abc") } }

    assertThat(exception).hasMessageThat().contains("Could not determine the foreground app")
  }
}
