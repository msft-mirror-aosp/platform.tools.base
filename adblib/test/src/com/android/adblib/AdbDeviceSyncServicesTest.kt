/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.adblib.impl.channels.AdbInputStreamChannel
import com.android.adblib.impl.channels.AdbOutputStreamChannel
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.testingutils.TestingAdbSessionHost
import com.android.adblib.testingutils.TimeWaitSocketsThrottler
import com.android.fakeadbserver.DeviceFileState
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.devicecommandhandlers.SyncCommandHandler
import com.android.sdklib.AndroidApiLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AdbDeviceSyncServicesTest {

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @JvmField
    @Rule
    val fakeAdbRule = FakeAdbServerProviderRule {
        installDefaultCommandHandlers()
        installDeviceHandler(SyncCommandHandler())
    }

    private val fakeAdb get() = fakeAdbRule.fakeAdb
    private val adbSession get() = fakeAdbRule.adbSession

    @Test
    fun testSyncSendFileWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val progress = TestSyncProgress()

        // Act
        withSyncServices(deviceSelector) {
            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes.inputStream()),
                filePath,
                fileMode,
                fileDate,
                progress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertTrue(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertNotNull(fakeDevice.getFile(filePath))
        fakeDevice.getFile(filePath)?.run {
            Assert.assertEquals(filePath, path)
            Assert.assertEquals(fileMode.modeBits, permission)
            Assert.assertEquals(fileDate.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertEquals(fileBytes.size, bytes.size)
        }
    }

    @Test
    fun testSyncSendEmptyFileWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(0)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val progress = TestSyncProgress()

        // Act
        withSyncServices(deviceSelector) {
            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes.inputStream()),
                filePath,
                fileMode,
                fileDate,
                progress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertFalse(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertNotNull(fakeDevice.getFile(filePath))
        fakeDevice.getFile(filePath)?.run {
            Assert.assertEquals(filePath, path)
            Assert.assertEquals(fileMode.modeBits, permission)
            Assert.assertEquals(fileDate.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertEquals(fileBytes.size, bytes.size)
        }
    }

    @Test
    fun testSyncSendWithTimeoutWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val progress = TestSyncProgress()
        val slowInputChannel = object : AdbInputChannel {
            var firstCall = true
            override suspend fun readBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
                if (firstCall) {
                    firstCall = false
                    buffer.putInt(5)
                } else {
                    delay(200)
                }
            }

            override fun close() {
            }
        }

        // Act
        exceptionRule.expect(TimeoutException::class.java)
        adbSession.host.timeProvider.withErrorTimeout(Duration.ofMillis(100)) {
            withSyncServices(deviceSelector) {
                it.send(
                    slowInputChannel,
                    filePath,
                    fileMode,
                    fileDate,
                    progress,
                    bufferSize = 1_024
                )
            }
        }

        // Assert
        Assert.fail() // Should not be reached
    }

    @Test
    fun testSyncSendRethrowsProgressException(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(1_024)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val progress = object : TestSyncProgress() {
            override suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long) {
                throw MyTestException("An error in progress callback")
            }
        }

        // Act
        exceptionRule.expect(MyTestException::class.java)
        withSyncServices(deviceSelector) {
            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes.inputStream()),
                filePath,
                fileMode,
                fileDate,
                progress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.fail() // Should not be reached
    }

    @Test
    fun testSyncSendTwoFilesInSameSessionWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val fileProgress = TestSyncProgress()

        val filePath2 = "/sdcard/foo/bar2.bin"
        val fileBytes2 = createFileBytes(96_000)
        val fileMode2 = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate2 = FileTime.from(2_000_000, TimeUnit.SECONDS)
        val fileProgress2 = TestSyncProgress()

        // Act
        withSyncServices(deviceSelector) {
            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes.inputStream()),
                filePath,
                fileMode,
                fileDate,
                fileProgress,
                bufferSize = 1_024
            )

            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes2.inputStream()),
                filePath2,
                fileMode2,
                fileDate2,
                fileProgress2,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(fileProgress.started)
        Assert.assertTrue(fileProgress.progress)
        Assert.assertTrue(fileProgress.done)

        Assert.assertNotNull(fakeDevice.getFile(filePath))
        fakeDevice.getFile(filePath)?.run {
            Assert.assertEquals(filePath, path)
            Assert.assertEquals(fileMode.modeBits, permission)
            Assert.assertEquals(fileDate.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertArrayEquals(fileBytes, bytes)
        }

        Assert.assertTrue(fileProgress2.started)
        Assert.assertTrue(fileProgress2.progress)
        Assert.assertTrue(fileProgress2.done)

        Assert.assertNotNull(fakeDevice.getFile(filePath2))
        fakeDevice.getFile(filePath2)?.run {
            Assert.assertEquals(filePath2, path)
            Assert.assertEquals(fileMode2.modeBits, permission)
            Assert.assertEquals(fileDate2.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertArrayEquals(fileBytes2, bytes)
        }
    }

    @Test
    fun testSyncSendFileWithNoDateSetsModifiedDateToCurrentTime(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(1_024)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val progress = TestSyncProgress()

        val currentTime = Instant.ofEpochSecond(1_234_567L)
        (adbSession.host as TestingAdbSessionHost).overrideUtcNow = currentTime

        // Act
        withSyncServices(deviceSelector) {
            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes.inputStream()),
                filePath,
                fileMode,
                null,
                progress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertTrue(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertNotNull(fakeDevice.getFile(filePath))
        fakeDevice.getFile(filePath)?.run {
            Assert.assertEquals(currentTime.epochSecond, modifiedDate.toLong())
        }
    }

    @Test
    fun testSyncRecvFileWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        fakeDevice.createFile(
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            )
        )
        val progress = TestSyncProgress()
        val outputStream = ByteArrayOutputStream()
        val outputChannel = AdbOutputStreamChannel(adbSession, outputStream)

        // Act
        withSyncServices(deviceSelector) {
            it.recv(
                filePath,
                outputChannel,
                progress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertTrue(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertArrayEquals(fileBytes, outputStream.toByteArray())
    }

    @Test
    fun testSyncRecvTwoFileInSameSessionWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(10)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        fakeDevice.createFile(
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            )
        )
        val progress = TestSyncProgress()
        val outputStream = ByteArrayOutputStream()
        val outputChannel = AdbOutputStreamChannel(adbSession, outputStream)

        val filePath2 = "/sdcard/foo/bar2.bin"
        val fileBytes2 = createFileBytes(12)
        val fileMode2 = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate2 = FileTime.from(2_000_000, TimeUnit.SECONDS)
        fakeDevice.createFile(
            DeviceFileState(
                filePath2,
                fileMode2.modeBits,
                (fileDate2.toMillis() / 1_000).toInt(),
                fileBytes2
            )
        )
        val progress2 = TestSyncProgress()
        val outputStream2 = ByteArrayOutputStream()
        val outputChannel2 = AdbOutputStreamChannel(adbSession, outputStream2)

        // Act
        withSyncServices(deviceSelector) {
            it.recv(
                filePath,
                outputChannel,
                progress,
                bufferSize = 1_024
            )

            it.recv(
                filePath2,
                outputChannel2,
                progress2,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertTrue(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertArrayEquals(fileBytes, outputStream.toByteArray())

        Assert.assertTrue(progress2.started)
        Assert.assertTrue(progress2.progress)
        Assert.assertTrue(progress2.done)

        Assert.assertArrayEquals(fileBytes2, outputStream2.toByteArray())
    }

    @Test
    fun testSyncSendThenRecvFileInSameSessionWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(10_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val sendProgress = TestSyncProgress()

        val recvProgress = TestSyncProgress()
        val outputStream = ByteArrayOutputStream()
        val outputChannel = AdbOutputStreamChannel(adbSession, outputStream)

        // Act
        withSyncServices(deviceSelector) {
            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes.inputStream()),
                filePath,
                fileMode,
                fileDate,
                sendProgress,
                bufferSize = 1_024
            )
            it.recv(
                filePath,
                outputChannel,
                recvProgress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(sendProgress.started)
        Assert.assertTrue(sendProgress.progress)
        Assert.assertTrue(sendProgress.done)

        Assert.assertNotNull(fakeDevice.getFile(filePath))
        fakeDevice.getFile(filePath)?.run {
            Assert.assertEquals(filePath, path)
            Assert.assertEquals(fileMode.modeBits, permission)
            Assert.assertEquals(fileDate.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertArrayEquals(fileBytes, bytes)
        }

        Assert.assertTrue(recvProgress.started)
        Assert.assertTrue(recvProgress.progress)
        Assert.assertTrue(recvProgress.done)

        Assert.assertArrayEquals(fileBytes, outputStream.toByteArray())
    }

    /**
     * This test tries do download a file that does not exist on the device
     */
    @Test
    fun testSyncRecvFileThrowsExceptionIfFileDoesNotExist(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val progress = TestSyncProgress()
        val outputStream = ByteArrayOutputStream()
        val outputChannel = AdbOutputStreamChannel(adbSession, outputStream)

        // Act
        exceptionRule.expect(AdbFailResponseException::class.java)
        withSyncServices(deviceSelector) {
            it.recv(
                filePath,
                outputChannel,
                progress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.fail() // Should not be reachable
    }

    @Test
    fun testSyncRecvRethrowsProgressException(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        fakeDevice.createFile(
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            )
        )
        val progress = object : TestSyncProgress() {
            override suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long) {
                throw MyTestException("An error in progress callback")
            }
        }
        val outputStream = ByteArrayOutputStream()
        val outputChannel = AdbOutputStreamChannel(adbSession, outputStream)

        // Act
        exceptionRule.expect(MyTestException::class.java)
        withSyncServices(deviceSelector) {
            it.recv(
                filePath,
                outputChannel,
                progress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.fail() // Should not be reached
    }

    @Test
    fun testSyncRecvRethrowsOutputChannelException(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        fakeDevice.createFile(
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            )
        )
        val progress = TestSyncProgress()
        val outputChannel = object : AdbOutputChannel {
            override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
                throw MyTestException("this stream simulates an error writing to local storage")
            }

            override fun close() {
            }
        }

        // Act
        exceptionRule.expect(MyTestException::class.java)
        withSyncServices(deviceSelector) {
            it.recv(
                filePath,
                outputChannel,
                progress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.fail() // Should not be reached
    }

    @Test
    fun testSyncStatFileWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        fakeDevice.createFile(
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            )
        )

        // Act
        val fileStat = withSyncServices(deviceSelector) {
            it.stat(filePath)
        }

        // Assert
        Assert.assertNotNull(fileStat)
        Assert.assertEquals(fileMode.modeBits, fileStat!!.remoteFileMode.modeBits)
        Assert.assertEquals(128_000, fileStat.size)
        Assert.assertEquals(1_000_000, fileStat.lastModified.toInstant().epochSecond)
    }

    @Test
    fun testSyncStatFileReturnsNullIfNoFile(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"

        // Act
        val fileStat = withSyncServices(deviceSelector) {
            it.stat(filePath)
        }

        // Assert
        Assert.assertNull(fileStat)
    }

    open class TestSyncProgress : SyncProgress {

        var started = false
        var progress = false
        var done = false

        override suspend fun transferStarted(remotePath: String) {
            started = true
        }

        override suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long) {
            progress = true
        }

        override suspend fun transferDone(remotePath: String, totalBytes: Long) {
            done = true
        }
    }

    private fun createFileBytes(size: Int): ByteArray {
        val result = ByteArray(size)
        for (i in 0 until size) {
            result[i] = (i and 0xff).toByte()
        }
        return result
    }

    private fun addFakeDevice(fakeAdb: FakeAdbServerProvider, sdk: Int = 30): DeviceState {
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                AndroidApiLevel(sdk),
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        return fakeDevice
    }

    suspend inline fun <R> withSyncServices(
        device: DeviceSelector,
        block: (AdbDeviceSyncServices) -> R
    ): R {
        return fakeAdbRule.adbSession.deviceServices.withSyncServices(device) {
            block(it)
        }
    }

    class MyTestException(message: String) : IOException(message)

    companion object {

        @JvmStatic
        @BeforeClass
        fun before() {
            TimeWaitSocketsThrottler.throttleIfNeeded()
        }
    }
}
