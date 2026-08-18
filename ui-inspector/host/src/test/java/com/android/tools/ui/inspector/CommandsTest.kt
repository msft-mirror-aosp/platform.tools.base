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
import com.android.tools.ui.inspector.model.UiNode
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.runBlocking
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandsTest {

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
  fun testResolveDeviceSerialReturnsRequestedSerialUnchanged() {
    fakeSession.hostServices.devices = DeviceList(emptyList(), emptyList())

    val serial = runBlocking { resolveDeviceSerial(fakeSession, "requested-serial") }

    assertThat(serial).isEqualTo("requested-serial")
  }

  @Test
  fun testResolveDeviceSerialUsesSoleOnlineDevice() {
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo("abc", DeviceState.ONLINE)), emptyList())

    val serial = runBlocking { resolveDeviceSerial(fakeSession, null) }

    assertThat(serial).isEqualTo("abc")
  }

  @Test
  fun testResolveDeviceSerialIgnoresNonOnlineDevices() {
    fakeSession.hostServices.devices =
      DeviceList(listOf(DeviceInfo("offline-device", DeviceState.OFFLINE), DeviceInfo("online-device", DeviceState.ONLINE)), emptyList())

    val serial = runBlocking { resolveDeviceSerial(fakeSession, null) }

    assertThat(serial).isEqualTo("online-device")
  }

  @Test
  fun testResolveDeviceSerialFailsWithNoDevices() {
    fakeSession.hostServices.devices = DeviceList(emptyList(), emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveDeviceSerial(fakeSession, null) } }

    assertThat(exception).hasMessageThat().contains("No connected devices found")
  }

  @Test
  fun testResolveDeviceSerialFailsWithNoOnlineDevices() {
    fakeSession.hostServices.devices =
      DeviceList(listOf(DeviceInfo("xyz", DeviceState.OFFLINE), DeviceInfo("abc", DeviceState.UNAUTHORIZED)), emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveDeviceSerial(fakeSession, null) } }

    assertThat(exception).hasMessageThat().contains("No online devices found")
    assertThat(exception).hasMessageThat().contains("abc (unauthorized), xyz (offline)")
  }

  @Test
  fun testResolveDeviceSerialFailsWithMultipleOnlineDevices() {
    fakeSession.hostServices.devices =
      DeviceList(listOf(DeviceInfo("device-b", DeviceState.ONLINE), DeviceInfo("device-a", DeviceState.ONLINE)), emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveDeviceSerial(fakeSession, null) } }

    assertThat(exception).hasMessageThat().contains("Multiple online devices found: device-a, device-b")
    assertThat(exception).hasMessageThat().contains("--device")
  }

  @Test
  fun testResolveTargetPackageReturnsRequestedPackageUnchanged() {
    // No shell command is configured: FakeAdbSession throws on any shell request, so success proves no device query.
    val packageName = runBlocking { resolveTargetPackage(fakeSession, "abc", "com.requested") }

    assertThat(packageName).isEqualTo("com.requested")
  }

  @Test
  fun testResolveTargetPackageUsesUidForFullyQualifiedProcessName() {
    configureTopActivityOutput("    APP  UID 1234:com.example.uiprocess/u0a123 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configurePackageUids(PackageUid("com.example.qaviews", 10123))

    val packageName = runBlocking { resolveTargetPackage(fakeSession, "abc", null) }

    assertThat(packageName).isEqualTo("com.example.qaviews")
  }

  @Test
  fun testResolveTargetPackageUsesUidForColonSuffixProcessName() {
    configureTopActivityOutput("    APP  UID 1234:com.example:ui/u0a123 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configurePackageUids(PackageUid("com.example", 10123))

    val packageName = runBlocking { resolveTargetPackage(fakeSession, "abc", null) }

    assertThat(packageName).isEqualTo("com.example")
  }

  @Test
  fun testResolveTargetPackageAcceptsMultipleProcessesWithSameUid() {
    configureTopActivityOutput(
      "    APP  UID 1234:com.example/u0a123 (top-activity)\n    APP  UID 5678:com.example:render/u0a123 (top-activity)\n"
    )
    configureProcessUid("1234", "10123")
    configureProcessUid("5678", "10123")
    configurePackageUids(PackageUid("com.example", 10123))

    val packageName = runBlocking { resolveTargetPackage(fakeSession, "abc", null) }

    assertThat(packageName).isEqualTo("com.example")
  }

  @Test
  fun testResolveTargetPackageFailsWhenNoForegroundApp() {
    // grep exits with code 1 and empty stdout when nothing matches.
    configureTopActivityOutput("", exitCode = 1)

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveTargetPackage(fakeSession, "abc", null) } }

    assertThat(exception).hasMessageThat().contains("Could not determine the foreground app")
    assertThat(exception).hasMessageThat().contains("--package")
  }

  @Test
  fun testResolveTargetPackageFailsWithMultipleForegroundApps() {
    configureTopActivityOutput("    APP  UID 1234:com.second/u0a123 (top-activity)\n    APP  UID 5678:com.first/u0a124 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configureProcessUid("5678", "10456")
    configurePackageUids(PackageUid("com.second", 10123), PackageUid("com.first", 10456))

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveTargetPackage(fakeSession, "abc", null) } }

    assertThat(exception).hasMessageThat().contains("Multiple foreground apps found: com.first, com.second")
    assertThat(exception).hasMessageThat().contains("--package")
  }

  @Test
  fun testResolveTargetPackageFailsWhenUidMatchesMultiplePackages() {
    configureTopActivityOutput("    APP  UID 1234:arbitrary.process/u0a123 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configurePackageUids(PackageUid("com.second", 10123), PackageUid("com.first", 10123))

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveTargetPackage(fakeSession, "abc", null) } }

    assertThat(exception)
      .hasMessageThat()
      .isEqualTo("The foreground process UID matches multiple packages: com.first, com.second. Select one with --package.")
  }

  @Test
  fun testResolveTargetPackageFailsWhenProcessUidIsMissing() {
    configureTopActivityOutput("    APP  UID 1234:com.example/u0a123 (top-activity)\n")
    fakeSession.deviceServices.configureShellCommand(selector, "ps -o UID= -p 1234", "")

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveTargetPackage(fakeSession, "abc", null) } }

    assertThat(exception).hasMessageThat().contains("Could not determine the foreground app")
  }

  @Test
  fun testResolveTargetPackageFailsWhenUidPackageMappingIsMissing() {
    configureTopActivityOutput("    APP  UID 1234:com.example/u0a123 (top-activity)\n")
    configureProcessUid("1234", "10123")
    configurePackageUids()

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveTargetPackage(fakeSession, "abc", null) } }

    assertThat(exception).hasMessageThat().contains("Could not determine the foreground app")
  }

  @Test
  fun testMergeComposeRootsWarnsOnStderrWhenTargetViewIsMissing() {
    val viewRoot = viewNode(1)
    val composeRoots = listOf(composableRoot(targetViewId = 999, composableId = 100))

    val stderr = captureStderr {
      mergeComposeRoots(
        viewRoot,
        composeRoots,
        stringTable = emptyMap(),
        parameters = null,
        includeParameters = false,
        includeSemantics = false,
      )
    }

    assertThat(stderr).contains("target view id 999")
    assertThat(stderr).contains("view root 1")
    assertThat(viewRoot.children).isEmpty()
  }

  @Test
  fun testMergeComposeRootsContinuesAfterFailedAttachment() {
    val composeView = viewNode(20)
    val viewRoot = viewNode(1, composeView)
    val composeRoots = listOf(composableRoot(targetViewId = 999, composableId = 100), composableRoot(targetViewId = 20, composableId = 200))

    val stderr = captureStderr {
      mergeComposeRoots(
        viewRoot,
        composeRoots,
        stringTable = emptyMap(),
        parameters = null,
        includeParameters = false,
        includeSemantics = false,
      )
    }

    // The missing target is warned about; the valid root after it still attaches.
    assertThat(stderr).contains("target view id 999")
    assertThat(composeView.children.map { it.id }).containsExactly(200L)
  }

  @Test
  fun testMergeComposeRootsAttachesNestedRootsInEitherResponseOrder() {
    // The inner AndroidComposeView (30) sits below the outer one (20); both orders must fully attach.
    for (reversed in listOf(false, true)) {
      val innerComposeView = viewNode(30)
      val viewRoot = viewNode(1, viewNode(20, innerComposeView))
      val composeRoots =
        listOf(composableRoot(targetViewId = 20, composableId = 100), composableRoot(targetViewId = 30, composableId = 200))

      val stderr = captureStderr {
        mergeComposeRoots(
          viewRoot,
          if (reversed) composeRoots.reversed() else composeRoots,
          stringTable = emptyMap(),
          parameters = null,
          includeParameters = false,
          includeSemantics = false,
        )
      }

      assertThat(stderr).isEmpty()
      assertThat(innerComposeView.children.map { it.id }).containsExactly(200L)
    }
  }

  private fun viewNode(id: Long, vararg children: UiNode): UiNode.ViewNode =
    UiNode.ViewNode(
      id = id,
      className = "android.view.View",
      bounds = UiNode.Bounds(0, 0, 100, 100),
      idResource = null,
      layoutResource = null,
      attributes = emptyList(),
      children = children.toMutableList(),
    )

  private fun composableRoot(targetViewId: Long, composableId: Long): LayoutInspectorComposeProtocol.ComposableRoot =
    LayoutInspectorComposeProtocol.ComposableRoot.newBuilder()
      .setViewId(targetViewId)
      .addNodes(LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(composableId))
      .build()

  private fun captureStderr(block: () -> Unit): String {
    val original = System.err
    val buffer = ByteArrayOutputStream()
    System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
    try {
      block()
    } finally {
      System.setErr(original)
    }
    return buffer.toString(Charsets.UTF_8)
  }
}
