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

import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.waitForDevice
import com.android.adblib.waitUntilOnline
import com.android.ddmlib.IDevice
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.FakeAdbServer
import com.android.fakeadbserver.devicecommandhandlers.DeviceCommandHandler
import com.android.fakeadbserver.services.ShellCommandOutput
import com.android.fakeadbserver.services.StatusWriter
import com.android.fakeadbserver.shellcommandhandlers.ShellConstants
import com.android.sdklib.AndroidApiLevel
import com.android.sdklib.AndroidVersion
import com.android.tools.deploy.proto.Deploy.Arch
import com.android.tools.deployer.devices.DeviceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.InterruptedByTimeoutException
import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [DeviceHolder] backed by an adblib [ConnectedDevice].
 *
 * The tests run against a [FakeAdbServer]. Unless the behavior under test depends on the API level of the device, tests use an API 30
 * device.
 */
class DeviceHolderTest {

  private val getPropCounter = GetPropCommandCounter()

  @get:Rule
  val fakeAdbRule = FakeAdbServerProviderRule {
    installDeviceHandler(getPropCounter)
    installDeviceHandler(UnresponsiveExecCommandHandler())
  }

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testVersion(): Unit = runBlockingWithTimeout {
    connectDevice(DeviceId.API_30)
    val version = createDeviceHolder().version

    assertEquals(30, version.apiLevel)
    assertNull(version.codename)
  }

  @Test
  fun testSupportedFeatures_onApi30(): Unit = runBlockingWithTimeout {
    connectDevice(DeviceId.API_30)
    val deviceHolder = createDeviceHolder()

    assertTrue(deviceHolder.isRealPkgNameSupported)
    assertTrue(deviceHolder.isSkipVerificationSupported)
  }

  @Test
  fun testSupportedFeatures_onApi29(): Unit = runBlockingWithTimeout {
    connectDevice(DeviceId.API_29)
    val deviceHolder = createDeviceHolder()

    assertFalse(deviceHolder.isRealPkgNameSupported)
    assertFalse(deviceHolder.isSkipVerificationSupported)
  }

  @Test
  fun testVersionAndSupportedFeatures_onAndroidRPreview(): Unit = runBlockingWithTimeout {
    connectDevice(DeviceId.API_29, properties = mapOf("ro.build.version.codename" to "R"))
    val deviceHolder = createDeviceHolder()

    assertEquals(29, deviceHolder.version.apiLevel)
    assertEquals("R", deviceHolder.version.codename)
    assertTrue(deviceHolder.isRealPkgNameSupported)
    // Skip verification is only supported starting with the Android R release
    assertFalse(deviceHolder.isSkipVerificationSupported)
  }

  @Test
  fun testReadonlyDeviceProperties_areOnlyRetrievedOnce(): Unit = runBlockingWithTimeout {
    connectDevice()
    val deviceHolder = createDeviceHolder()

    assertEquals(DEFAULT_DEVICE_ID.api(), deviceHolder.version.apiLevel)
    assertEquals(listOf("x86_64"), deviceHolder.abis)
    assertFalse(deviceHolder.isEmbedded)

    // Readonly device properties are cached after being retrieved from the device for the first time
    assertEquals(1, getPropCounter.count)
  }

  @Test
  fun testDeviceProperties_returnDefaults_whenPropertiesCannotBeRetrieved(): Unit = runBlockingWithTimeout {
    connectDevice()
    val deviceHolder = createDeviceHolder()

    // Disconnect the device before any device property is retrieved
    fakeAdbRule.fakeAdb.fakeAdbServer.disconnectDevice(SERIAL_NUMBER).get()

    assertEquals(AndroidVersion.DEFAULT, deviceHolder.version)
    assertEquals(emptyList<String>(), deviceHolder.abis)
    assertFalse(deviceHolder.isEmbedded)
    assertFalse(deviceHolder.isRealPkgNameSupported)
    assertFalse(deviceHolder.isSkipVerificationSupported)
  }

  @Test
  fun testSerialNumber(): Unit = runBlockingWithTimeout {
    connectDevice()

    assertEquals(SERIAL_NUMBER, createDeviceHolder().serialNumber)
  }

  @Test
  fun testAbis_fromAbiList(): Unit = runBlockingWithTimeout {
    connectDevice(cpuAbi = "arm64-v8a", properties = mapOf("ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a,armeabi"))

    assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), createDeviceHolder().abis)
  }

  @Test
  fun testAbis_withoutAbiList_fallsBackToAbiAndAbi2(): Unit = runBlockingWithTimeout {
    connectDevice(cpuAbi = "armeabi-v7a", properties = mapOf("ro.product.cpu.abi2" to "armeabi"))

    assertEquals(listOf("armeabi-v7a", "armeabi"), createDeviceHolder().abis)
  }

  @Test
  fun testIsEmbedded_returnsFalse_whenNotEmbedded(): Unit = runBlockingWithTimeout {
    connectDevice(properties = mapOf("ro.build.characteristics" to "default"))

    assertFalse(createDeviceHolder().isEmbedded)
  }

  @Test
  fun testIsEmbedded_returnsTrue_whenEmbedded(): Unit = runBlockingWithTimeout {
    connectDevice(properties = mapOf("ro.build.characteristics" to "default,embedded"))

    assertTrue(createDeviceHolder().isEmbedded)
  }

  @Test
  fun testRoot(): Unit = runBlockingWithTimeout {
    connectDevice()
    val deviceHolder = createDeviceHolder()
    assertFalse(deviceHolder.isRoot)

    assertTrue(deviceHolder.root())
    assertTrue(deviceHolder.isRoot)
  }

  @Test
  fun testRoot_whenAlreadyRoot(): Unit = runBlockingWithTimeout {
    connectDevice(isRoot = true)
    val deviceHolder = createDeviceHolder()
    assertTrue(deviceHolder.isRoot)

    assertTrue(deviceHolder.root())
    assertTrue(deviceHolder.isRoot)
  }

  @Test
  fun testExecuteShellCommand(): Unit = runBlockingWithTimeout {
    connectDevice()
    val receiver = CollectingShellOutputReceiver()

    createDeviceHolder().executeShellCommand("echo hello from fake device", receiver, 5, TimeUnit.SECONDS)

    assertEquals("hello from fake device", receiver.output.trim())
    assertTrue(receiver.isFlushed)
  }

  @Test
  fun testExecuteShellCommand_convertsTimeoutExceptionToIOException(): Unit = runBlockingWithTimeout {
    val deviceState = connectDevice()
    val deviceHolder = createDeviceHolder()
    // Run the command once while the device is responsive, so that the data adblib caches (e.g. device properties) is available when
    // the device stops producing output.
    deviceHolder.executeShellCommand("echo hello", CollectingShellOutputReceiver(), 5, TimeUnit.SECONDS)
    // Now make the device unresponsive
    deviceState.delayStdout = 10.seconds

    val e =
      assertThrows(IOException::class.java) {
        deviceHolder.executeShellCommand("echo hello", CollectingShellOutputReceiver(), 100, TimeUnit.MILLISECONDS)
      }
    assertTrue(e.cause is TimeoutException)
  }

  @Test
  fun testExecuteBinderCommand_usesAbbExec_whenSupported(): Unit = runBlockingWithTimeout {
    // `abb_exec` is supported starting with API 30
    val deviceState = connectDevice(DeviceId.API_30)
    val receiver = CollectingShellOutputReceiver()

    createDeviceHolder().executeBinderCommand(arrayOf("package", "path", "com.example.app"), receiver, 5, TimeUnit.SECONDS, null)

    assertEquals("/data/app/com.example.app/base.apk", receiver.output.trim())
    assertTrue(receiver.isFlushed)
    assertEquals(listOf("package\u0000path\u0000com.example.app"), deviceState.abbLogs)
    assertEquals(emptyList<String>(), deviceState.cmdLogs)
  }

  @Test
  fun testExecuteBinderCommand_usesCmd_whenAbbExecIsNotSupported(): Unit = runBlockingWithTimeout {
    val deviceState = connectDevice(DeviceId.API_29)
    val receiver = CollectingShellOutputReceiver()

    createDeviceHolder().executeBinderCommand(arrayOf("package", "path", "com.example.app"), receiver, 5, TimeUnit.SECONDS, null)

    assertEquals("/data/app/com.example.app/base.apk", receiver.output.trim())
    assertTrue(receiver.isFlushed)
    assertEquals(emptyList<String>(), deviceState.abbLogs)
    assertEquals(listOf("package path com.example.app"), deviceState.cmdLogs)
  }

  @Test
  fun testExecuteBinderCommand_withInputStream_usingAbbExec(): Unit = runBlockingWithTimeout {
    assertInstallWriteWithInputStream(DeviceId.API_30)
  }

  @Test
  fun testExecuteBinderCommand_withInputStream_usingCmd(): Unit = runBlockingWithTimeout {
    assertInstallWriteWithInputStream(DeviceId.API_29)
  }

  @Test
  fun testExecuteBinderCommand_convertsTimeoutExceptionToIOException(): Unit = runBlockingWithTimeout {
    val deviceState = connectDevice()
    val deviceHolder = createDeviceHolder()
    val parameters = arrayOf("package", "path", "com.example.app")
    // Run the command once while the device is responsive, so that the data adblib caches (e.g. device features) is available when the
    // device stops producing output.
    deviceHolder.executeBinderCommand(parameters, CollectingShellOutputReceiver(), 5, TimeUnit.SECONDS, null)
    // Now make the device unresponsive
    deviceState.delayStdout = 10.seconds

    val e =
      assertThrows(IOException::class.java) {
        deviceHolder.executeBinderCommand(parameters, CollectingShellOutputReceiver(), 100, TimeUnit.MILLISECONDS, null)
      }
    assertTrue(e.cause is TimeoutException)
  }

  @Test
  fun testRawExec2(): Unit = runBlockingWithTimeout {
    connectDevice()

    createDeviceHolder().rawExec2("echo", arrayOf("hello raw output")).use { socket ->
      assertTrue(socket.isOpen)
      assertEquals("hello raw output", socket.readText().trim())
    }
  }

  @Test
  fun testRawExec2_throwsIOException_whenDeviceDoesNotRespond(): Unit = runBlockingWithTimeout {
    connectDevice()
    // Use a session with a short connection timeout, so that the unresponsive `exec` service request times out quickly
    AdbSession.create(fakeAdbRule.host, fakeAdbRule.createChannelProvider(), Duration.ofMillis(500)).use { session ->
      val deviceHolder = createDeviceHolder(session)

      // Note: adblib's AdbSocketChannelImpl delegates socket reads with a timeout directly to JDK NIO's
      // `AsynchronousSocketChannel.read`, which throws InterruptedByTimeoutException on timeouts.
      val e = assertThrows(IOException::class.java) { deviceHolder.rawExec2(UNRESPONSIVE_COMMAND, emptyArray()) }
      assertTrue(e is InterruptedByTimeoutException)
    }
  }

  @Test
  fun testUninstallPackage_returnsNull_onSuccess(): Unit = runBlockingWithTimeout {
    connectDevice()

    // FakeAdbServer considers every package installed unless its name is `ShellConstants.NON_INSTALLED_APP_ID`
    assertNull(createDeviceHolder().uninstallPackage("com.example.app"))
  }

  @Test
  fun testUninstallPackage_returnsErrorCode_onFailure(): Unit = runBlockingWithTimeout {
    connectDevice()

    assertEquals("DELETE_FAILED_INTERNAL_ERROR", createDeviceHolder().uninstallPackage(ShellConstants.NON_INSTALLED_APP_ID))
  }

  @Test
  fun testUninstallPackage_returnsUnknownFailure_whenErrorCodeIsMissing(): Unit = runBlockingWithTimeout {
    // On API 25-27, uninstalling a package that is not installed reports an exception instead of a `Failure [<error code>]` message
    connectDevice(DeviceId.API_26)

    assertEquals(
      "Unknown failure: Exception occurred while executing:",
      createDeviceHolder().uninstallPackage(ShellConstants.NON_INSTALLED_APP_ID),
    )
  }

  @Test
  fun testPushFile(): Unit = runBlockingWithTimeout {
    val deviceState = connectDevice()
    val localFile = temporaryFolder.newFile("test_push.txt").apply { writeText("hello world") }

    createDeviceHolder().pushFile(localFile.absolutePath, "/data/local/tmp/test_push.txt")

    assertEquals("hello world", deviceState.getFile("/data/local/tmp/test_push.txt")?.bytes?.let { String(it) })
  }

  @Test
  fun testPushFile_throwsIOException_whenLocalFileDoesNotExist(): Unit = runBlockingWithTimeout {
    connectDevice()
    val deviceHolder = createDeviceHolder()
    val nonExistentFile = temporaryFolder.root.resolve("non_existent.txt")

    assertThrows(IOException::class.java) { deviceHolder.pushFile(nonExistentFile.absolutePath, "/data/local/tmp/test_push.txt") }
  }

  @Test
  fun testPushFile_throwsIOException_whenDeviceRejectsSyncRequest(): Unit = runBlockingWithTimeout {
    val deviceState = connectDevice()
    deviceState.acceptsSyncServiceRequests = false
    val deviceHolder = createDeviceHolder()
    val localFile = temporaryFolder.newFile("test_push.txt").apply { writeText("hello world") }

    assertThrows(IOException::class.java) { deviceHolder.pushFile(localFile.absolutePath, "/data/local/tmp/test_push.txt") }
  }

  @Test
  fun testGetPidsForPackageName_matchesPackageNames(): Unit = runBlockingWithTimeout {
    // Starting with API 30, devices report the package name of processes
    val deviceState = connectDevice(DeviceId.API_30)
    val deviceHolder = createDeviceHolder()

    startProcesses(deviceState)
    yieldUntil {
      deviceHolder.getPidsForPackageName("com.example.app").size == 3 &&
        deviceHolder.getPidsForPackageName("com.example.other").isNotEmpty()
    }

    assertEquals(listOf(101, 103, 104), deviceHolder.getPidsForPackageName("com.example.app").sorted())
    assertEquals(listOf(102), deviceHolder.getPidsForPackageName("com.example.other"))
    assertEquals(emptyList<Int>(), deviceHolder.getPidsForPackageName("com.example.sync"))
    assertEquals(emptyList<Int>(), deviceHolder.getPidsForPackageName("com.nonexistent"))
  }

  @Test
  fun testGetPidsForPackageName_matchesProcessNames_whenPackageNamesAreNotSupported(): Unit = runBlockingWithTimeout {
    val deviceState = connectDevice(DeviceId.API_29)
    val deviceHolder = createDeviceHolder()

    startProcesses(deviceState)
    yieldUntil {
      deviceHolder.getPidsForPackageName("com.example.app").size == 2 &&
        deviceHolder.getPidsForPackageName("com.example.other").isNotEmpty() &&
        deviceHolder.getPidsForPackageName("com.example.sync").isNotEmpty()
    }

    // The process name suffix (e.g. ":remote") is ignored when matching process names
    assertEquals(listOf(101, 103), deviceHolder.getPidsForPackageName("com.example.app").sorted())
    assertEquals(listOf(102), deviceHolder.getPidsForPackageName("com.example.other"))
    assertEquals(listOf(104), deviceHolder.getPidsForPackageName("com.example.sync"))
    assertEquals(emptyList<Int>(), deviceHolder.getPidsForPackageName("com.nonexistent"))
  }

  @Test
  fun testGetArchForPid_64BitProcess(): Unit = runBlockingWithTimeout {
    assertArchForPid(cpuAbi = "x86_64", expectedArch = Arch.ARCH_64_BIT)
  }

  @Test
  fun testGetArchForPid_32BitProcess(): Unit = runBlockingWithTimeout {
    assertArchForPid(cpuAbi = "x86", expectedArch = Arch.ARCH_32_BIT)
  }

  @Test
  fun testGetArchForPid_returnsUnknown_whenProcessAbiIsNotReported(): Unit = runBlockingWithTimeout {
    // DDMS `HELO` packets only include the ABI of the process starting with API 21
    assertArchForPid(DeviceId.API_19, cpuAbi = "x86_64", expectedArch = Arch.ARCH_UNKNOWN)
  }

  @Test
  fun testGetArchForPid_returnsUnknown_forNonExistentPid(): Unit = runBlockingWithTimeout {
    connectDevice()

    assertEquals(Arch.ARCH_UNKNOWN, createDeviceHolder().getArchForPid(9999))
  }

  @Test
  fun testSerialNumber_whenConnectedDeviceIsMissing() {
    assertEquals(SERIAL_NUMBER, createDeviceHolderWithoutConnectedDevice().serialNumber)
  }

  @Test
  fun testMethodsThatThrow_whenConnectedDeviceIsMissing() {
    val deviceHolder = createDeviceHolderWithoutConnectedDevice()
    val localFile = temporaryFolder.newFile("test_push.txt")

    assertThrows(IOException::class.java) { deviceHolder.isRoot }
    assertThrows(IOException::class.java) { deviceHolder.root() }
    assertThrows(IOException::class.java) { deviceHolder.rawExec2("echo", emptyArray()) }
    assertThrows(IOException::class.java) { deviceHolder.executeShellCommand("echo", CollectingShellOutputReceiver(), 5, TimeUnit.SECONDS) }
    assertThrows(IOException::class.java) {
      deviceHolder.executeBinderCommand(arrayOf("package", "path"), CollectingShellOutputReceiver(), 5, TimeUnit.SECONDS, null)
    }
    assertThrows(IOException::class.java) { deviceHolder.uninstallPackage("com.example.app") }
    assertThrows(IOException::class.java) { deviceHolder.pushFile(localFile.absolutePath, "/data/local/tmp/test_push.txt") }
  }

  @Test
  fun testMethodsThatReturnDefault_whenConnectedDeviceIsMissing() {
    val deviceHolder = createDeviceHolderWithoutConnectedDevice()

    assertEquals(AndroidVersion.DEFAULT, deviceHolder.version)
    assertEquals(emptyList<String>(), deviceHolder.abis)
    assertFalse(deviceHolder.isRealPkgNameSupported)
    assertFalse(deviceHolder.isSkipVerificationSupported)
    assertFalse(deviceHolder.isEmbedded)
    assertEquals(emptyList<Int>(), deviceHolder.getPidsForPackageName("com.example.app"))
    assertEquals(Arch.ARCH_UNKNOWN, deviceHolder.getArchForPid(9999))
  }

  private suspend fun assertInstallWriteWithInputStream(deviceId: DeviceId) {
    connectDevice(deviceId)
    val deviceHolder = createDeviceHolder()
    val createSessionReceiver = CollectingShellOutputReceiver()
    deviceHolder.executeBinderCommand(arrayOf("package", "install-create"), createSessionReceiver, 5, TimeUnit.SECONDS, null)
    val sessionId = Regex("""Success: .*\[(\d+)]""").find(createSessionReceiver.output)!!.groupValues[1]
    val apkBytes = "fake apk content".toByteArray()
    val receiver = CollectingShellOutputReceiver()

    deviceHolder.executeBinderCommand(
      arrayOf("package", "install-write", "-S", apkBytes.size.toString(), sessionId, "base.apk", "-"),
      receiver,
      5,
      TimeUnit.SECONDS,
      ByteArrayInputStream(apkBytes),
    )

    assertEquals("Success: streamed ${apkBytes.size} bytes", receiver.output.trim())
  }

  private suspend fun assertArchForPid(deviceId: DeviceId = DEFAULT_DEVICE_ID, cpuAbi: String, expectedArch: Arch) {
    val deviceState = connectDevice(deviceId, cpuAbi = cpuAbi)
    val deviceHolder = createDeviceHolder()

    deviceState.startClient(pid = 301, userId = 0, processName = "com.example.app", packageName = "com.example.app", isWaiting = false)
    yieldUntil { deviceHolder.getPidsForPackageName("com.example.app").contains(301) }

    assertEquals(expectedArch, deviceHolder.getArchForPid(301))
  }

  /**
   * Starts processes for the `com.example.app` and `com.example.other` packages, including processes whose name differs from the name of
   * their package.
   */
  private fun startProcesses(deviceState: DeviceState) {
    deviceState.startClient(pid = 101, userId = 0, processName = "com.example.app", packageName = "com.example.app", isWaiting = false)
    deviceState.startClient(pid = 102, userId = 0, processName = "com.example.other", packageName = "com.example.other", isWaiting = false)
    deviceState.startClient(
      pid = 103,
      userId = 0,
      processName = "com.example.app:remote",
      packageName = "com.example.app",
      isWaiting = false,
    )
    deviceState.startClient(pid = 104, userId = 0, processName = "com.example.sync", packageName = "com.example.app", isWaiting = false)
  }

  private fun connectDevice(
    deviceId: DeviceId = DEFAULT_DEVICE_ID,
    cpuAbi: String = "x86_64",
    properties: Map<String, String> = emptyMap(),
    isRoot: Boolean = false,
  ): DeviceState {
    val deviceState =
      fakeAdbRule.fakeAdb.fakeAdbServer
        .connectDevice(
          deviceId = SERIAL_NUMBER,
          manufacturer = "Google",
          deviceModel = "Pixel",
          release = deviceId.version(),
          sdk = AndroidApiLevel(deviceId.api()),
          cpuAbi = cpuAbi,
          properties = properties,
          hostConnectionType = DeviceState.HostConnectionType.USB,
          isRoot = isRoot,
        )
        .get()
    deviceState.deviceStatus = DeviceState.DeviceStatus.ONLINE
    return deviceState
  }

  private suspend fun createDeviceHolder(session: AdbSession = fakeAdbRule.adbSession): DeviceHolder {
    val connectedDevice = session.connectedDevicesTracker.waitForDevice(SERIAL_NUMBER)
    connectedDevice.waitUntilOnline()
    return DeviceHolder(connectedDevice)
  }

  /** Creates a [DeviceHolder] for a device whose [ConnectedDevice] could not be found, e.g. because the device got disconnected. */
  private fun createDeviceHolderWithoutConnectedDevice(): DeviceHolder {
    // The IDevice is only used to retrieve the serial number of the device
    val iDevice = mock<IDevice>()
    whenever(iDevice.serialNumber).thenReturn(SERIAL_NUMBER)
    return DeviceHolder(iDevice, Optional.empty(), useConnectedDevice = true)
  }

  private fun DeployerConnectedSocket.readText(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteBuffer.allocate(1024)
    while (true) {
      buffer.clear()
      // Returns -1 when the end of the stream is reached, or 0 on timeout
      val count = read(buffer, 5_000)
      if (count == -1) {
        break
      }
      if (count == 0) {
        throw TimeoutException("Timed out reading from socket")
      }
      output.write(buffer.array(), 0, count)
    }
    return String(output.toByteArray(), Charsets.UTF_8)
  }

  private class CollectingShellOutputReceiver : DeployerIShellOutputReceiver {
    private val builder = StringBuilder()

    val output: String
      get() = builder.toString()

    var isFlushed = false
      private set

    override fun addOutput(data: ByteArray, offset: Int, length: Int) {
      builder.append(String(data, offset, length))
    }

    override fun flush() {
      isFlushed = true
    }

    override fun isCancelled(): Boolean = false
  }

  /** Counts the `getprop` commands received by the fake devices, without handling them. */
  private class GetPropCommandCounter : DeviceCommandHandler("") {
    private val invocations = AtomicInteger()

    val count: Int
      get() = invocations.get()

    override fun accept(
      server: FakeAdbServer,
      socketScope: CoroutineScope,
      socket: Socket,
      device: DeviceState,
      command: String,
      args: String,
      statusWriter: StatusWriter,
      shellCommandOutputProvider: (() -> ShellCommandOutput)?,
    ): Boolean {
      if (args.startsWith("getprop")) {
        invocations.incrementAndGet()
      }
      // Let the default handlers execute the command
      return false
    }
  }

  /** Simulates an unresponsive device by never replying to `exec:[UNRESPONSIVE_COMMAND]` service requests. */
  private class UnresponsiveExecCommandHandler : DeviceCommandHandler("exec") {
    override fun invoke(server: FakeAdbServer, socketScope: CoroutineScope, socket: Socket, device: DeviceState, args: String) {
      if (args != UNRESPONSIVE_COMMAND) {
        throw NextHandlerException()
      }
      // Wait for the client to give up and close the connection
      socket.getInputStream().read()
    }
  }

  companion object {
    private const val SERIAL_NUMBER = "serial-123"
    private const val UNRESPONSIVE_COMMAND = "unresponsive"
    private val DEFAULT_DEVICE_ID = DeviceId.API_30
  }
}
