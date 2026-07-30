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

import com.android.adblib.ConnectedDevice
import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.InstallException
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.SimpleConnectedSocket
import com.android.ddmlib.SyncException
import com.android.ddmlib.TimeoutException
import com.android.sdklib.AndroidVersion
import java.io.IOException
import java.io.InputStream
import java.util.Optional
import java.util.concurrent.TimeUnit
import org.junit.Assert
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DeviceHolderTest {

  private val connectedDevice = mock(ConnectedDevice::class.java)
  private val iDevice = mock(IDevice::class.java)
  private val deviceHolder = DeviceHolder(iDevice, Optional.of(connectedDevice))

  @Test
  fun testIDeviceDelegatedProperties() {
    val version = AndroidVersion(30, null)
    `when`(iDevice.version).thenReturn(version)
    `when`(iDevice.serialNumber).thenReturn("serial-123")
    `when`(iDevice.abis).thenReturn(listOf("arm64-v8a"))
    `when`(iDevice.name).thenReturn("FakeDevice")
    `when`(iDevice.clients).thenReturn(emptyArray<Client>())
    `when`(iDevice.isRoot).thenReturn(true)

    Assert.assertEquals(version, deviceHolder.version)
    Assert.assertEquals("serial-123", deviceHolder.serialNumber)
    Assert.assertEquals(listOf("arm64-v8a"), deviceHolder.abis)
    Assert.assertEquals("FakeDevice", deviceHolder.name)
    Assert.assertTrue(deviceHolder.clients.isEmpty())
    Assert.assertTrue(deviceHolder.isRoot)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceRawExec2Success() {
    val socket = mock(SimpleConnectedSocket::class.java)
    `when`(iDevice.rawExec2(eq("exe"), any())).thenReturn(socket)

    Assert.assertEquals(socket, deviceHolder.rawExec2("exe", arrayOf("param")))
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceRawExec2_convertsAdbCommandRejectedExceptionToIOException() {
    `when`(iDevice.rawExec2(eq("exe"), any())).thenThrow(AdbCommandRejectedException("rejected"))

    val e = Assert.assertThrows(IOException::class.java) { deviceHolder.rawExec2("exe", arrayOf("param")) }
    Assert.assertTrue(e.cause is AdbCommandRejectedException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceRawExec2_convertsTimeoutExceptionToIOException() {
    `when`(iDevice.rawExec2(eq("exe"), any())).thenThrow(TimeoutException("timeout"))

    val e = Assert.assertThrows(IOException::class.java) { deviceHolder.rawExec2("exe", arrayOf("param")) }
    Assert.assertTrue(e.cause is TimeoutException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceRawExec2_rethrowsRuntimeException() {
    val runtimeException = RuntimeException("oops")
    `when`(iDevice.rawExec2(eq("exe"), any())).thenThrow(runtimeException)

    val e = Assert.assertThrows(RuntimeException::class.java) { deviceHolder.rawExec2("exe", arrayOf("param")) }
    Assert.assertEquals(runtimeException, e)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteShellCommandWithInputStream() {
    val receiver = mock(IShellOutputReceiver::class.java)
    val inputStream = mock(InputStream::class.java)

    deviceHolder.executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS, inputStream)
    verify(iDevice).executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS, inputStream)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteShellCommandWithInputStream_convertsAdbCommandRejectedExceptionToIOException() {
    val receiver = mock(IShellOutputReceiver::class.java)
    val inputStream = mock(InputStream::class.java)
    Mockito.doThrow(AdbCommandRejectedException("rejected"))
      .`when`(iDevice)
      .executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS, inputStream)

    val e =
      Assert.assertThrows(IOException::class.java) { deviceHolder.executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS, inputStream) }
    Assert.assertTrue(e.cause is AdbCommandRejectedException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteShellCommandWithInputStream_convertsShellCommandUnresponsiveExceptionToIOException() {
    val receiver = mock(IShellOutputReceiver::class.java)
    val inputStream = mock(InputStream::class.java)
    Mockito.doThrow(ShellCommandUnresponsiveException())
      .`when`(iDevice)
      .executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS, inputStream)

    val e =
      Assert.assertThrows(IOException::class.java) { deviceHolder.executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS, inputStream) }
    Assert.assertTrue(e.cause is ShellCommandUnresponsiveException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteShellCommandWithInputStream_convertsTimeoutExceptionToIOException() {
    val receiver = mock(IShellOutputReceiver::class.java)
    val inputStream = mock(InputStream::class.java)
    Mockito.doThrow(TimeoutException("timeout")).`when`(iDevice).executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS, inputStream)

    val e =
      Assert.assertThrows(IOException::class.java) { deviceHolder.executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS, inputStream) }
    Assert.assertTrue(e.cause is TimeoutException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteShellCommandWithoutInputStream() {
    val receiver = mock(IShellOutputReceiver::class.java)

    deviceHolder.executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS)
    verify(iDevice).executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteShellCommandWithoutInputStream_convertsAdbCommandRejectedExceptionToIOException() {
    val receiver = mock(IShellOutputReceiver::class.java)
    Mockito.doThrow(AdbCommandRejectedException("rejected")).`when`(iDevice).executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS)

    val e = Assert.assertThrows(IOException::class.java) { deviceHolder.executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS) }
    Assert.assertTrue(e.cause is AdbCommandRejectedException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteShellCommandWithoutInputStream_convertsShellCommandUnresponsiveExceptionToIOException() {
    val receiver = mock(IShellOutputReceiver::class.java)
    Mockito.doThrow(ShellCommandUnresponsiveException()).`when`(iDevice).executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS)

    val e = Assert.assertThrows(IOException::class.java) { deviceHolder.executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS) }
    Assert.assertTrue(e.cause is ShellCommandUnresponsiveException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteShellCommandWithoutInputStream_convertsTimeoutExceptionToIOException() {
    val receiver = mock(IShellOutputReceiver::class.java)
    Mockito.doThrow(TimeoutException("timeout")).`when`(iDevice).executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS)

    val e = Assert.assertThrows(IOException::class.java) { deviceHolder.executeShellCommand("cmd", receiver, 5L, TimeUnit.SECONDS) }
    Assert.assertTrue(e.cause is TimeoutException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteBinderCommand() {
    val receiver = mock(IShellOutputReceiver::class.java)
    val inputStream = mock(InputStream::class.java)
    val params = arrayOf("param")

    deviceHolder.executeBinderCommand(params, receiver, 5L, TimeUnit.SECONDS, inputStream)
    verify(iDevice).executeBinderCommand(params, receiver, 5L, TimeUnit.SECONDS, inputStream)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteBinderCommand_convertsAdbCommandRejectedExceptionToIOException() {
    val receiver = mock(IShellOutputReceiver::class.java)
    val inputStream = mock(InputStream::class.java)
    val params = arrayOf("param")
    Mockito.doThrow(AdbCommandRejectedException("rejected"))
      .`when`(iDevice)
      .executeBinderCommand(params, receiver, 5L, TimeUnit.SECONDS, inputStream)

    val e =
      Assert.assertThrows(IOException::class.java) {
        deviceHolder.executeBinderCommand(params, receiver, 5L, TimeUnit.SECONDS, inputStream)
      }
    Assert.assertTrue(e.cause is AdbCommandRejectedException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteBinderCommand_convertsShellCommandUnresponsiveExceptionToIOException() {
    val receiver = mock(IShellOutputReceiver::class.java)
    val inputStream = mock(InputStream::class.java)
    val params = arrayOf("param")
    Mockito.doThrow(ShellCommandUnresponsiveException())
      .`when`(iDevice)
      .executeBinderCommand(params, receiver, 5L, TimeUnit.SECONDS, inputStream)

    val e =
      Assert.assertThrows(IOException::class.java) {
        deviceHolder.executeBinderCommand(params, receiver, 5L, TimeUnit.SECONDS, inputStream)
      }
    Assert.assertTrue(e.cause is ShellCommandUnresponsiveException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceExecuteBinderCommand_convertsTimeoutExceptionToIOException() {
    val receiver = mock(IShellOutputReceiver::class.java)
    val inputStream = mock(InputStream::class.java)
    val params = arrayOf("param")
    Mockito.doThrow(TimeoutException("timeout")).`when`(iDevice).executeBinderCommand(params, receiver, 5L, TimeUnit.SECONDS, inputStream)

    val e =
      Assert.assertThrows(IOException::class.java) {
        deviceHolder.executeBinderCommand(params, receiver, 5L, TimeUnit.SECONDS, inputStream)
      }
    Assert.assertTrue(e.cause is TimeoutException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceUninstallPackageSuccess() {
    `when`(iDevice.uninstallPackage("pkg")).thenReturn("success")

    Assert.assertEquals("success", deviceHolder.uninstallPackage("pkg"))
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceUninstallPackage_convertsInstallExceptionToIOException() {
    `when`(iDevice.uninstallPackage("pkg")).thenThrow(InstallException("failed"))

    val e = Assert.assertThrows(IOException::class.java) { deviceHolder.uninstallPackage("pkg") }
    Assert.assertTrue(e.cause is InstallException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceSupportsFeature() {
    val feature = IDevice.Feature.SHELL_V2
    val hardwareFeature = IDevice.HardwareFeature.WATCH

    `when`(iDevice.supportsFeature(feature)).thenReturn(true)
    `when`(iDevice.supportsFeature(hardwareFeature)).thenReturn(false)

    Assert.assertTrue(deviceHolder.supportsFeature(feature))
    Assert.assertFalse(deviceHolder.supportsFeature(hardwareFeature))
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDevicePushFileSuccess() {
    deviceHolder.pushFile("local", "remote")
    verify(iDevice).pushFile("local", "remote")
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDevicePushFile_convertsSyncExceptionToIOException() {
    Mockito.doThrow(SyncException(SyncException.SyncError.TRANSFER_PROTOCOL_ERROR)).`when`(iDevice).pushFile("local", "remote")

    val e = Assert.assertThrows(IOException::class.java) { deviceHolder.pushFile("local", "remote") }
    Assert.assertTrue(e.cause is SyncException)
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceRootSuccess() {
    `when`(iDevice.root()).thenReturn(true)

    Assert.assertTrue(deviceHolder.root())
    Mockito.verifyNoInteractions(connectedDevice)
  }

  @Test
  fun testIDeviceRoot_convertsAdbCommandRejectedExceptionToIOException() {
    `when`(iDevice.root()).thenThrow(AdbCommandRejectedException("rejected"))

    val e = Assert.assertThrows(IOException::class.java) { deviceHolder.root() }
    Assert.assertTrue(e.cause is AdbCommandRejectedException)
    Mockito.verifyNoInteractions(connectedDevice)
  }
}
