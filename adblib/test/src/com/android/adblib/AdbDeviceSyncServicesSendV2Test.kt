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
package com.android.adblib

import com.android.adblib.AdbDeviceSyncServices.SendV2Compression
import com.android.adblib.AdbDeviceSyncServices.SendV2Options
import com.android.adblib.impl.channels.AdbInputStreamChannel
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.testingutils.TimeWaitSocketsThrottler
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.SyncSendRequest
import com.android.sdklib.AndroidApiLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for [AdbDeviceSyncServices.sendV2] and [syncSendV2] */
class AdbDeviceSyncServicesSendV2Test {

  @JvmField @Rule val fakeAdbRule = FakeAdbServerProviderRule()

  @JvmField @Rule val temporaryFolder = TemporaryFolder()

  private val fakeAdb
    get() = fakeAdbRule.fakeAdb

  private val adbSession
    get() = fakeAdbRule.adbSession

  private val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)

  private val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)

  @Test
  fun testSendV2UsesLz4ByDefault(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(3_000_000)
    val progress = RecordingSyncProgress()

    // Act
    withSyncServices(deviceSelector) { it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, progress) }

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    val request = fakeDevice.syncSendRequests.single()
    Assert.assertEquals("SND2", request.requestId)
    Assert.assertEquals(filePath, request.path)
    Assert.assertEquals(fileMode.modeBits, request.mode)
    Assert.assertEquals(FLAG_LZ4, request.flags)
    Assert.assertTrue("Payload (${request.payload.size} bytes) should be compressed", request.payload.size < fileBytes.size / 2)
    Assert.assertTrue("Payload should span multiple DATA packets", request.dataPacketSizes.size > 1)
    assertFullDataPackets(request, MAX_DATA_PAYLOAD_SIZE)
    assertSingleLz4Frame(request.payload)
    progress.assertTransferCompleted(filePath, fileBytes.size.toLong())
  }

  @Test
  fun testSendV2Lz4WorksWithIncompressibleData(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.bin"
    val fileBytes = Random(1234).nextBytes(300_000)
    val progress = RecordingSyncProgress()

    // Act
    withSyncServices(deviceSelector) {
      it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, progress, options = SendV2Options(SendV2Compression.LZ4))
    }

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    val request = fakeDevice.syncSendRequests.single()
    Assert.assertEquals(FLAG_LZ4, request.flags)
    // Incompressible blocks are stored uncompressed, i.e. the payload is slightly larger than the file
    Assert.assertTrue(request.payload.size > fileBytes.size)
    assertFullDataPackets(request, MAX_DATA_PAYLOAD_SIZE)
    assertSingleLz4Frame(request.payload)
    progress.assertTransferCompleted(filePath, fileBytes.size.toLong())
  }

  @Test
  fun testSendV2Lz4WorksWithEmptyFile(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = ByteArray(0)
    val progress = RecordingSyncProgress()

    // Act
    withSyncServices(deviceSelector) { it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, progress) }

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    val request = fakeDevice.syncSendRequests.single()
    Assert.assertEquals(FLAG_LZ4, request.flags)
    // The ADB daemon expects a complete LZ4 frame (i.e. header and end mark), even for empty files
    Assert.assertEquals(listOf(LZ4_EMPTY_FRAME_SIZE), request.dataPacketSizes)
    assertSingleLz4Frame(request.payload)
    progress.assertTransferCompleted(filePath, 0)
  }

  @Test
  fun testSendV2Lz4WorksWithSmallBufferSize(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(500_000)
    val progress = RecordingSyncProgress()

    // Act
    withSyncServices(deviceSelector) {
      it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, progress, bufferSize = 1_024)
    }

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    val request = fakeDevice.syncSendRequests.single()
    Assert.assertEquals(FLAG_LZ4, request.flags)
    assertFullDataPackets(request, maxPayloadSize = 1_024 - DATA_HEADER_SIZE)
    assertSingleLz4Frame(request.payload)
    progress.assertTransferCompleted(filePath, fileBytes.size.toLong())
  }

  @Test
  fun testSendV2WithoutCompression(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(200_000)
    val progress = RecordingSyncProgress()

    // Act
    withSyncServices(deviceSelector) {
      it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, progress, options = SendV2Options(SendV2Compression.NONE))
    }

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    val request = fakeDevice.syncSendRequests.single()
    Assert.assertEquals("SND2", request.requestId)
    Assert.assertEquals(FLAG_NONE, request.flags)
    Assert.assertArrayEquals(fileBytes, request.payload)
    request.dataPacketSizes.forEach { size -> Assert.assertTrue("Invalid DATA packet size: $size", size in 1..MAX_DATA_PAYLOAD_SIZE) }
    progress.assertTransferCompleted(filePath, fileBytes.size.toLong())
  }

  @Test
  fun testSendV2DoesNotCompressIfLz4IsNotAvailable(): Unit = runBlockingWithTimeout {
    // Prepare: Note that we need to update features before adblib queries (and caches) them
    fakeAdb.fakeAdbServer.features = fakeAdb.fakeAdbServer.features - AdbFeatures.SENDRECV_V2_LZ4
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(200_000)

    // Act
    withSyncServices(deviceSelector) { it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, null) }

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    val request = fakeDevice.syncSendRequests.single()
    Assert.assertEquals("SND2", request.requestId)
    Assert.assertEquals(FLAG_NONE, request.flags)
    Assert.assertArrayEquals(fileBytes, request.payload)
  }

  @Test
  fun testSendV2UsesExplicitLz4EvenIfLz4IsNotAvailable(): Unit = runBlockingWithTimeout {
    // Prepare: Note that we need to update features before adblib queries (and caches) them
    fakeAdb.fakeAdbServer.features = fakeAdb.fakeAdbServer.features - AdbFeatures.SENDRECV_V2_LZ4
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(200_000)

    // Act
    withSyncServices(deviceSelector) {
      it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, null, options = SendV2Options(SendV2Compression.LZ4))
    }

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    val request = fakeDevice.syncSendRequests.single()
    Assert.assertEquals("SND2", request.requestId)
    Assert.assertEquals(FLAG_LZ4, request.flags)
    assertSingleLz4Frame(request.payload)
  }

  @Test
  fun testSendV2FallsBackToSendOnOlderDevices(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 30)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(200_000)
    val progress = RecordingSyncProgress()

    // Act
    withSyncServices(deviceSelector) { it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, progress) }

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    val request = fakeDevice.syncSendRequests.single()
    Assert.assertEquals("SEND", request.requestId)
    Assert.assertArrayEquals(fileBytes, request.payload)
    progress.assertTransferCompleted(filePath, fileBytes.size.toLong())
  }

  @Test
  fun testSendV2FailsOnOlderDevicesIfFallbackIsDisabled(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 30)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(1_000)

    // Act
    val exception = runCatching {
      withSyncServices(deviceSelector) {
        it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, null, options = SendV2Options(fallbackToSendV1 = false))
      }
    }
      .exceptionOrNull()

    // Assert
    Assert.assertTrue("Unexpected exception: $exception", exception is AdbFailResponseException)
    Assert.assertEquals("unknown command SND2", (exception as AdbFailResponseException).failMessage)
    Assert.assertNull(fakeDevice.getFile(filePath))
  }

  @Test
  fun testSendV2NeverCompressesSymbolicLinks(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val linkPath = "/data/local/tmp/link"
    val linkMode = RemoteFileMode.fromModeBits(0xA1FF) // "lrwxrwxrwx"
    val linkTarget = "/data/local/tmp/target\u0000".toByteArray(Charsets.UTF_8)
    Assert.assertTrue(linkMode.isSymbolicLink)

    // Act: Explicitly ask for LZ4, which should be ignored
    withSyncServices(deviceSelector) {
      it.sendV2(inputChannelOf(linkTarget), linkPath, linkMode, fileDate, null, options = SendV2Options(SendV2Compression.LZ4))
    }

    // Assert
    val request = fakeDevice.syncSendRequests.single()
    Assert.assertEquals("SND2", request.requestId)
    Assert.assertEquals(FLAG_NONE, request.flags)
    Assert.assertArrayEquals(linkTarget, request.payload)
    assertDeviceFile(fakeDevice, linkPath, linkTarget, linkMode)
  }

  @Test
  fun testSendV2MultipleFilesInSameSessionWorks(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath1 = "/sdcard/foo/bar1.txt"
    val fileBytes1 = createCompressibleBytes(300_000)
    val filePath2 = "/sdcard/foo/bar2.txt"
    val fileBytes2 = createCompressibleBytes(100_000)
    val filePath3 = "/sdcard/foo/bar3.txt"
    val fileBytes3 = createCompressibleBytes(250_000)

    // Act
    withSyncServices(deviceSelector) {
      it.sendV2(inputChannelOf(fileBytes1), filePath1, fileMode, fileDate, null)
      it.sendV2(inputChannelOf(fileBytes2), filePath2, fileMode, fileDate, null, options = SendV2Options(SendV2Compression.NONE))
      it.sendV2(inputChannelOf(fileBytes3), filePath3, fileMode, fileDate, null)
    }

    // Assert
    assertDeviceFile(fakeDevice, filePath1, fileBytes1)
    assertDeviceFile(fakeDevice, filePath2, fileBytes2)
    assertDeviceFile(fakeDevice, filePath3, fileBytes3)
    Assert.assertEquals(listOf(FLAG_LZ4, FLAG_NONE, FLAG_LZ4), fakeDevice.syncSendRequests.map { it.flags })
  }

  @Test
  fun testSendV2RejectsInvalidBufferSize(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(1_000)

    // Act
    val exception = runCatching {
      withSyncServices(deviceSelector) {
        it.sendV2(inputChannelOf(fileBytes), filePath, fileMode, fileDate, null, bufferSize = DATA_HEADER_SIZE)
      }
    }
      .exceptionOrNull()

    // Assert
    Assert.assertTrue("Unexpected exception: $exception", exception is IllegalArgumentException)
    Assert.assertTrue(fakeDevice.syncSendRequests.isEmpty())
  }

  @Test
  fun testSyncSendV2Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(200_000)

    // Act
    adbSession.deviceServices.syncSendV2(deviceSelector, inputChannelOf(fileBytes), filePath, fileMode, fileDate)

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    Assert.assertEquals(FLAG_LZ4, fakeDevice.syncSendRequests.single().flags)
  }

  @Test
  fun testSyncSendV2FromPathWorks(): Unit = runBlockingWithTimeout {
    // Prepare
    val fakeDevice = addFakeDevice(fakeAdb, sdk = 31)
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
    val filePath = "/sdcard/foo/bar.txt"
    val fileBytes = createCompressibleBytes(200_000)
    val sourcePath = temporaryFolder.newFile("source.txt").toPath()
    Files.write(sourcePath, fileBytes)

    // Act
    adbSession.deviceServices.syncSendV2(deviceSelector, sourcePath, filePath, fileMode, fileDate)

    // Assert
    assertDeviceFile(fakeDevice, filePath, fileBytes)
    Assert.assertEquals(FLAG_LZ4, fakeDevice.syncSendRequests.single().flags)
  }

  /** Records calls to [SyncProgress] methods */
  private class RecordingSyncProgress : SyncProgress {

    private var startedPath: String? = null
    private val progressValues = mutableListOf<Long>()
    private var doneTotalBytes: Long? = null

    override suspend fun transferStarted(remotePath: String) {
      startedPath = remotePath
    }

    override suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long) {
      progressValues.add(totalBytesSoFar)
    }

    override suspend fun transferDone(remotePath: String, totalBytes: Long) {
      doneTotalBytes = totalBytes
    }

    /** Asserts progress was reported in terms of uncompressed bytes (i.e. bytes of the source file), and that the transfer completed */
    fun assertTransferCompleted(remotePath: String, totalBytes: Long) {
      Assert.assertEquals(remotePath, startedPath)
      Assert.assertEquals(totalBytes, doneTotalBytes)
      Assert.assertEquals("Progress should be increasing", progressValues.sorted(), progressValues)
      Assert.assertEquals(if (totalBytes == 0L) null else totalBytes, progressValues.lastOrNull())
    }
  }

  private fun inputChannelOf(bytes: ByteArray): AdbInputChannel {
    return AdbInputStreamChannel(adbSession.host, bytes.inputStream())
  }

  /** Returns [size] bytes of text that compresses reasonably well, similar to source code or log files */
  private fun createCompressibleBytes(size: Int): ByteArray {
    val random = Random(1234)
    val words = listOf("adb", "sync", "send", "compression", "frame", "block", "device", "android", "studio", "kotlin")
    val builder = StringBuilder(size + 100)
    var lineNumber = 0
    while (builder.length < size) {
      builder.append("line ").append(lineNumber++).append(": the quick brown fox jumps over the lazy ")
      builder.append(words[random.nextInt(words.size)]).append('\n')
    }
    return builder.substring(0, size).toByteArray(Charsets.US_ASCII)
  }

  private fun assertDeviceFile(fakeDevice: DeviceState, filePath: String, fileBytes: ByteArray, mode: RemoteFileMode = fileMode) {
    val file = fakeDevice.getFile(filePath) ?: throw AssertionError("File '$filePath' should exist on the device")
    Assert.assertEquals(filePath, file.path)
    Assert.assertEquals(mode.modeBits, file.permission)
    Assert.assertEquals(fileDate.to(TimeUnit.SECONDS), file.modifiedDate.toLong())
    Assert.assertArrayEquals(fileBytes, file.bytes)
  }

  /** Asserts all `DATA` packets of [request] contain [maxPayloadSize] bytes, except for the last one */
  private fun assertFullDataPackets(request: SyncSendRequest, maxPayloadSize: Int) {
    val sizes = request.dataPacketSizes
    Assert.assertTrue("There should be at least one DATA packet", sizes.isNotEmpty())
    Assert.assertEquals(request.payload.size, sizes.sum())
    sizes.dropLast(1).forEach { size -> Assert.assertEquals("DATA packets should be full", maxPayloadSize, size) }
    Assert.assertTrue("Invalid last DATA packet size: ${sizes.last()}", sizes.last() in 1..maxPayloadSize)
  }

  /**
   * Asserts [payload] contains exactly one [LZ4 frame](https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md) with blocks of at most
   * 64KB, as expected by the ADB daemon.
   */
  private fun assertSingleLz4Frame(payload: ByteArray) {
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    Assert.assertEquals("LZ4 frame magic number", LZ4_FRAME_MAGIC, buffer.getInt())
    val flg = buffer.get().toInt() and 0xFF
    Assert.assertEquals("LZ4 frame version", 1, flg ushr 6)
    val bd = buffer.get().toInt() and 0xFF
    Assert.assertEquals("LZ4 frame block maximum size (64KB)", 4, (bd ushr 4) and 0x07)
    val hasBlockChecksum = (flg and 0x10) != 0
    val hasContentSize = (flg and 0x08) != 0
    val hasContentChecksum = (flg and 0x04) != 0
    val hasDictionaryId = (flg and 0x01) != 0
    if (hasContentSize) {
      buffer.position(buffer.position() + 8)
    }
    if (hasDictionaryId) {
      buffer.position(buffer.position() + 4)
    }
    buffer.get() // Header checksum
    while (true) {
      val blockSize = buffer.getInt()
      if (blockSize == 0) {
        // End mark
        break
      }
      val size = blockSize and 0x7FFFFFFF
      Assert.assertTrue("LZ4 block size ($size) should not exceed 64KB", size <= 64 * 1024)
      buffer.position(buffer.position() + size + (if (hasBlockChecksum) 4 else 0))
    }
    if (hasContentChecksum) {
      buffer.position(buffer.position() + 4)
    }
    Assert.assertEquals("There should be no data after the end of the LZ4 frame", 0, buffer.remaining())
  }

  private fun addFakeDevice(fakeAdb: FakeAdbServerProvider, sdk: Int): DeviceState {
    val fakeDevice = fakeAdb.connectDevice("1234", "test1", "test2", "model", AndroidApiLevel(sdk), DeviceState.HostConnectionType.USB)
    fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
    return fakeDevice
  }

  private suspend inline fun <R> withSyncServices(device: DeviceSelector, block: (AdbDeviceSyncServices) -> R): R {
    return adbSession.deviceServices.withSyncServices(device) { block(it) }
  }

  companion object {

    /** Value of the `flags` field of a `SND2` request without compression */
    private const val FLAG_NONE = 0

    /** Value of the `flags` field of a `SND2` request with LZ4 compression */
    private const val FLAG_LZ4 = 2

    /** Size (in bytes) of the header of a `DATA` packet */
    private const val DATA_HEADER_SIZE = 8

    /** Maximum payload size of `DATA` packets with the default buffer size */
    private const val MAX_DATA_PAYLOAD_SIZE = SYNC_DATA_MAX - DATA_HEADER_SIZE

    private const val LZ4_FRAME_MAGIC = 0x184D2204

    /** Size (in bytes) of an LZ4 frame without blocks, i.e. header (7 bytes) followed by end mark (4 bytes) */
    private const val LZ4_EMPTY_FRAME_SIZE = 11

    @JvmStatic
    @BeforeClass
    fun before() {
      TimeWaitSocketsThrottler.throttleIfNeeded()
    }
  }
}
