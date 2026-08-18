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
package com.android.tools.deployer.common

import com.android.adblib.connectedDevicesTracker
import com.android.adblib.ddmlibcompatibility.testutils.InitAndroidDebugBridgeRule
import com.android.adblib.ddmlibcompatibility.testutils.UseAdbLibAndroidDebugBridgeRule
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.waitForDevice
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.shellcommandhandlers.ShellConstants
import com.android.sdklib.AndroidApiLevel
import com.android.sdklib.AndroidVersion
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.devices.DeviceId
import com.android.tools.deployer.rules.ApiLevel
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(ApiLevel::class)
class DeviceHolderMigrationTest {

  @JvmField @ApiLevel.Init var deviceId: DeviceId? = null

  val fakeAdbRule = FakeAdbServerProviderRule()

  private val useAdbLibAndroidDebugBridgeRule = UseAdbLibAndroidDebugBridgeRule { fakeAdbRule.adbSession }

  private val initAndroidDebugBridgeRule = InitAndroidDebugBridgeRule(alsoCreateBridge = true) { fakeAdbRule.fakeAdb.port }

  @get:Rule
  val ruleChain: RuleChain = RuleChain.outerRule(fakeAdbRule).around(useAdbLibAndroidDebugBridgeRule).around(initAndroidDebugBridgeRule)

  @Test
  fun testVersionConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    // Act/Assert: Both should return the same version
    assertEquals(deviceHolderLegacy.version, deviceHolderNew.version)
    assertEquals(deviceId!!.api(), deviceHolderNew.version.apiLevel)
  }

  @Test
  fun testVersionFailureReturnsDefault() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolder = createDeviceHolder(iDevice, useConnectedDevice = true)

    // Disconnect the device to cause failures
    fakeAdbRule.fakeAdb.disconnectDevice(iDevice.serialNumber)

    // Act / Assert
    assertEquals(AndroidVersion.DEFAULT.apiLevel, deviceHolder.version.apiLevel)
  }

  @Test
  fun testSupportsRealPkgNameConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    assertEquals(deviceHolderLegacy.isRealPkgNameSupported, deviceHolderNew.isRealPkgNameSupported)

    val api = deviceId!!.api()
    if (api >= 30) {
      assertTrue(deviceHolderNew.isRealPkgNameSupported)
    } else {
      assertFalse(deviceHolderNew.isRealPkgNameSupported)
    }
  }

  @Test
  fun testSupportsSkipVerificationConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    assertEquals(deviceHolderLegacy.isSkipVerificationSupported, deviceHolderNew.isSkipVerificationSupported)

    val api = deviceId!!.api()
    if (api >= 30) {
      assertTrue(deviceHolderNew.isSkipVerificationSupported)
    } else {
      assertFalse(deviceHolderNew.isSkipVerificationSupported)
    }
  }

  @Test
  fun testIsEmbedded_returnsFalse_whenNotEmbedded() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    assertEquals(deviceHolderLegacy.isEmbedded, deviceHolderNew.isEmbedded)
    assertFalse(deviceHolderNew.isEmbedded)
  }

  @Test
  fun testIsEmbedded_returnsTrue_whenEmbedded() = runBlocking {
    val iDevice = connectAndGetDevice(extraProperties = mapOf("ro.build.characteristics" to "embedded"))
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    assertEquals(deviceHolderLegacy.isEmbedded, deviceHolderNew.isEmbedded)
    assertTrue(deviceHolderNew.isEmbedded)
  }

  @Test
  fun testSerialNumberConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    assertEquals(deviceHolderLegacy.serialNumber, deviceHolderNew.serialNumber)
    assertEquals(iDevice.serialNumber, deviceHolderNew.serialNumber)
  }

  @Test
  fun testSerialNumberFallback_whenConnectedDeviceIsEmpty() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolder = DeviceHolder(iDevice, connectedDevice = Optional.empty(), useConnectedDevice = true)

    assertEquals(iDevice.serialNumber, deviceHolder.serialNumber)
  }

  @Test
  fun testMethodsThatThrow_whenConnectedDeviceIsEmpty() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolder = DeviceHolder(iDevice, connectedDevice = Optional.empty(), useConnectedDevice = true)

    assertThrows(IOException::class.java) { deviceHolder.isRoot }
    assertThrows(IOException::class.java) { deviceHolder.rawExec2("cmd", emptyArray()) }
    assertThrows(IOException::class.java) { deviceHolder.executeShellCommand("cmd", CollectingShellOutputReceiver(), 5, TimeUnit.SECONDS) }
    assertThrows(IOException::class.java) {
      deviceHolder.executeBinderCommand(arrayOf("cmd"), CollectingShellOutputReceiver(), 5, TimeUnit.SECONDS, null)
    }
    assertThrows(IOException::class.java) { deviceHolder.uninstallPackage("com.example") }
    assertThrows(IOException::class.java) { deviceHolder.root() }
    Unit
  }

  @Test
  fun testMethodsThatReturnDefault_whenConnectedDeviceIsEmpty() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolder = DeviceHolder(iDevice, connectedDevice = Optional.empty(), useConnectedDevice = true)

    assertFalse(deviceHolder.isRealPkgNameSupported)
    assertFalse(deviceHolder.isSkipVerificationSupported)
    assertFalse(deviceHolder.isEmbedded)
    assertEquals(AndroidVersion.DEFAULT, deviceHolder.version)
    assertEquals(emptyList<String>(), deviceHolder.abis)
    assertEquals(emptyList<Int>(), deviceHolder.getPidsForPackageName("com.example.app"))
    assertEquals(Deploy.Arch.ARCH_UNKNOWN, deviceHolder.getArchForPid(9999))
  }

  @Test
  fun testAbisConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    assertEquals(deviceHolderLegacy.abis, deviceHolderNew.abis)
  }

  @Test
  fun testIsRootConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    assertEquals(deviceHolderLegacy.isRoot, deviceHolderNew.isRoot)
  }

  @Test
  fun testRoot() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolder = createDeviceHolder(iDevice, useConnectedDevice = true)

    // Initially not root
    assertFalse(deviceHolder.isRoot)

    // Elevate to root
    val rootResult = deviceHolder.root()
    assertTrue(rootResult)
    assertTrue(deviceHolder.isRoot)
  }

  @Test
  fun testPushFile() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolder = createDeviceHolder(iDevice, useConnectedDevice = true)

    // Create a temporary file
    val tempFile = File.createTempFile("test_push", ".txt")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath(), "hello world".toByteArray())

    val remotePath = "/data/local/tmp/test_push.txt"
    deviceHolder.pushFile(tempFile.absolutePath, remotePath)

    // Verify file exists on fake device
    val fakeDeviceState = fakeAdbRule.fakeAdb.device(iDevice.serialNumber)
    val remoteFile = fakeDeviceState.getFile(remotePath)
    assertNotNull(remoteFile)
    assertEquals("hello world", String(remoteFile!!.bytes))
  }

  @Test
  fun testExecuteShellCommandConsistency() = runBlocking {
    val command = "echo hello from fake device"
    val expectedOutput = "hello from fake device"

    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    val receiverLegacy = CollectingShellOutputReceiver()
    deviceHolderLegacy.executeShellCommand(command, receiverLegacy, 5, TimeUnit.SECONDS)

    val receiverNew = CollectingShellOutputReceiver()
    deviceHolderNew.executeShellCommand(command, receiverNew, 5, TimeUnit.SECONDS)

    assertEquals(expectedOutput, receiverLegacy.output.trim())
    assertEquals(expectedOutput, receiverNew.output.trim())
  }

  @Test
  @ApiLevel.InRange(min = 21)
  fun testRawExec2Consistency() = runBlocking {
    val command = "echo"
    val args = arrayOf("hello raw output")
    val expectedOutput = "hello raw output"

    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    val socketLegacy = deviceHolderLegacy.rawExec2(command, args)
    val bufferLegacy = ByteBuffer.allocate(100)
    val readLegacy = socketLegacy.read(bufferLegacy, 5000)
    val outputLegacy = String(bufferLegacy.array(), 0, readLegacy)

    val socketNew = deviceHolderNew.rawExec2(command, args)
    val bufferNew = ByteBuffer.allocate(100)
    val readNew = socketNew.read(bufferNew, 5000)
    val outputNew = String(bufferNew.array(), 0, readNew)

    assertEquals(expectedOutput, outputLegacy.trim())
    assertEquals(expectedOutput, outputNew.trim())
  }

  @Test
  @ApiLevel.InRange(min = 21)
  fun testExecuteBinderCommandConsistency() = runBlocking {
    val appId = "com.example.app"
    val expectedOutput = "/data/app/$appId/base.apk"

    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    val receiverLegacy = CollectingShellOutputReceiver()
    deviceHolderLegacy.executeBinderCommand(arrayOf("package", "path", appId), receiverLegacy, 5, TimeUnit.SECONDS, null)

    val receiverNew = CollectingShellOutputReceiver()
    deviceHolderNew.executeBinderCommand(arrayOf("package", "path", appId), receiverNew, 5, TimeUnit.SECONDS, null)

    assertEquals(expectedOutput, receiverLegacy.output.trim())
    assertEquals(expectedOutput, receiverNew.output.trim())
  }

  @Test
  fun testUninstallPackageSuccessConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    // FakeAdbServer considers every package installed unless its name is `ShellConstants.NON_INSTALLED_APP_ID`
    val resultLegacy = deviceHolderLegacy.uninstallPackage("com.example.installed")
    val resultNew = deviceHolderNew.uninstallPackage("com.example.installed")

    assertNull(resultLegacy)
    assertNull(resultNew)
  }

  @Test
  fun testUninstallPackageFailureConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    val resultLegacy = deviceHolderLegacy.uninstallPackage(ShellConstants.NON_INSTALLED_APP_ID)
    val resultNew = deviceHolderNew.uninstallPackage(ShellConstants.NON_INSTALLED_APP_ID)

    assertNotNull(resultLegacy)
    assertNotNull(resultNew)
    // The exact error message might vary slightly depending on API level (stdout vs stderr, usage info) and buffering,
    // but the main error code (first line) should be consistent.
    assertEquals(resultLegacy!!.trim().lines().first(), resultNew!!.trim().lines().first())
  }

  @Test
  fun testPushFileConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    val tempFile = File.createTempFile("test_push_const", ".txt")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath(), "hello consistency".toByteArray())

    val remotePathLegacy = "/data/local/tmp/test_push_legacy.txt"
    val remotePathNew = "/data/local/tmp/test_push_new.txt"

    deviceHolderLegacy.pushFile(tempFile.absolutePath, remotePathLegacy)
    deviceHolderNew.pushFile(tempFile.absolutePath, remotePathNew)

    val fakeDeviceState = fakeAdbRule.fakeAdb.device(iDevice.serialNumber)
    val fileLegacy = fakeDeviceState.getFile(remotePathLegacy)
    val fileNew = fakeDeviceState.getFile(remotePathNew)

    assertNotNull(fileLegacy)
    assertNotNull(fileNew)
    assertEquals("hello consistency", String(fileLegacy!!.bytes))
    assertEquals("hello consistency", String(fileNew!!.bytes))
  }

  @Test
  fun testPushFileFailureConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    val nonExistentFile = "/non/existent/file/path/foo.txt"
    val remotePath = "/data/local/tmp/foo.txt"

    assertThrows(IOException::class.java) { deviceHolderLegacy.pushFile(nonExistentFile, remotePath) }
    assertThrows(IOException::class.java) { deviceHolderNew.pushFile(nonExistentFile, remotePath) }
    Unit
  }

  @Test
  fun testRootAndIsRootLegacy() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    assertFalse(deviceHolderLegacy.isRoot)
    val rootResultLegacy = deviceHolderLegacy.root()
    assertTrue(rootResultLegacy)
    assertTrue(deviceHolderLegacy.isRoot)
  }

  @Test
  fun testRootAndIsRootMigrated() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)
    assertFalse(deviceHolderNew.isRoot)
    val rootResultNew = deviceHolderNew.root()
    assertTrue(rootResultNew)
    assertTrue(deviceHolderNew.isRoot)
  }

  @Test
  fun testGetPidsForPackageNameConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceState = fakeAdbRule.fakeAdb.device(iDevice.serialNumber)

    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    deviceState.startClient(pid = 101, userId = 0, processName = "com.example.app", packageName = "com.example.app", isWaiting = false)
    deviceState.startClient(pid = 102, userId = 0, processName = "com.example.other", packageName = "com.example.other", isWaiting = false)
    deviceState.startClient(
      pid = 103,
      userId = 0,
      processName = "com.example.app:remote",
      packageName = "com.example.app",
      isWaiting = false,
    )

    yieldUntil(timeout = Duration.ofSeconds(5)) {
      deviceHolderLegacy.getPidsForPackageName("com.example.app").size >= 2 &&
        deviceHolderLegacy.getPidsForPackageName("com.example.other").isNotEmpty()
    }
    yieldUntil(timeout = Duration.ofSeconds(5)) {
      deviceHolderNew.getPidsForPackageName("com.example.app").size >= 2 &&
        deviceHolderNew.getPidsForPackageName("com.example.other").isNotEmpty()
    }

    assertEquals(listOf(101, 103), deviceHolderLegacy.getPidsForPackageName("com.example.app"))
    assertEquals(listOf(101, 103), deviceHolderNew.getPidsForPackageName("com.example.app"))

    assertEquals(listOf(102), deviceHolderLegacy.getPidsForPackageName("com.example.other"))
    assertEquals(listOf(102), deviceHolderNew.getPidsForPackageName("com.example.other"))

    assertTrue(deviceHolderLegacy.getPidsForPackageName("com.nonexistent").isEmpty())
    assertTrue(deviceHolderNew.getPidsForPackageName("com.nonexistent").isEmpty())
  }

  @Test
  fun testGetArchForPidConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceState = fakeAdbRule.fakeAdb.device(iDevice.serialNumber)

    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    deviceState.startClient(pid = 301, userId = 0, processName = "com.example.app", packageName = "com.example.app", isWaiting = false)

    yieldUntil(timeout = Duration.ofSeconds(5)) { deviceHolderLegacy.getPidsForPackageName("com.example.app").contains(301) }
    yieldUntil(timeout = Duration.ofSeconds(5)) { deviceHolderNew.getPidsForPackageName("com.example.app").contains(301) }

    // On API 19 and 20, DDMS does not send ABI / instruction set information in the HELO response chunk (ABI reporting in HELO was
    // introduced in API 21).
    val expectedArch = if (deviceId!!.api() >= 21) Deploy.Arch.ARCH_64_BIT else Deploy.Arch.ARCH_UNKNOWN

    assertEquals(expectedArch, deviceHolderLegacy.getArchForPid(301))
    assertEquals(expectedArch, deviceHolderNew.getArchForPid(301))

    assertEquals(Deploy.Arch.ARCH_UNKNOWN, deviceHolderLegacy.getArchForPid(99999))
    assertEquals(Deploy.Arch.ARCH_UNKNOWN, deviceHolderNew.getArchForPid(99999))
  }

  private class CollectingShellOutputReceiver : DeployerIShellOutputReceiver {
    private val builder = StringBuilder()
    val output: String
      get() = builder.toString()

    override fun addOutput(data: ByteArray, offset: Int, length: Int) {
      builder.append(String(data, offset, length))
    }

    override fun flush() {}

    override fun isCancelled(): Boolean = false
  }

  private suspend fun connectAndGetDevice(serialNumber: String = "serial_123", extraProperties: Map<String, String> = emptyMap()): IDevice {
    connectDevice(serialNumber, extraProperties)
    val bridge = AndroidDebugBridge.getBridge() ?: throw IllegalStateException("Bridge not initialized")
    yieldUntil { bridge.devices.any { it.serialNumber == serialNumber } }
    val device = bridge.devices.first { it.serialNumber == serialNumber }
    yieldUntil { device.arePropertiesSet() }
    return device
  }

  private fun connectDevice(serialNumber: String, extraProperties: Map<String, String> = emptyMap()): DeviceState {
    val deviceId = this.deviceId ?: throw IllegalStateException("deviceId not initialized")
    val deviceState =
      fakeAdbRule.fakeAdb.fakeAdbServer
        .connectDevice(
          deviceId = serialNumber,
          manufacturer = "Google",
          deviceModel = "Pixel",
          release = deviceId.api().toString(),
          sdk = AndroidApiLevel(deviceId.api()),
          cpuAbi = "x86_64",
          properties = extraProperties,
          hostConnectionType = DeviceState.HostConnectionType.USB,
          maxSpeedMbps = 0,
          negotiatedSpeedMbps = 0,
        )
        .get()
    deviceState.deviceStatus = DeviceState.DeviceStatus.ONLINE
    return deviceState
  }

  private suspend fun createDeviceHolder(iDevice: IDevice, useConnectedDevice: Boolean): DeviceHolder {
    return if (useConnectedDevice) {
      val session = fakeAdbRule.adbSession
      val connectedDevice = session.connectedDevicesTracker.waitForDevice(iDevice.serialNumber)
      DeviceHolder(iDevice, Optional.of(connectedDevice), useConnectedDevice = true)
    } else {
      DeviceHolder(iDevice, Optional.empty(), useConnectedDevice = false)
    }
  }
}
