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

import com.android.adblib.AdbDeviceSyncServices.ListOptions
import com.android.adblib.AdbDeviceSyncServices.ListV2Options
import com.android.adblib.AdbDeviceSyncServices.StatV2Options
import com.android.adblib.impl.channels.AdbInputStreamChannel
import com.android.adblib.impl.channels.AdbOutputStreamChannel
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.testingutils.TestingAdbSessionHost
import com.android.adblib.testingutils.TimeWaitSocketsThrottler
import com.android.fakeadbserver.DeviceFileState
import com.android.fakeadbserver.DeviceState
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
import java.nio.channels.ClosedChannelException
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
    val fakeAdbRule = FakeAdbServerProviderRule()

    private val fakeAdb get() = fakeAdbRule.fakeAdb
    private val adbSession get() = fakeAdbRule.adbSession

    @Test
    fun testSendFileWorks(): Unit = runBlockingWithTimeout {
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
    fun testSendEmptyFileWorks(): Unit = runBlockingWithTimeout {
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
    fun testSendWithTimeoutWorks(): Unit = runBlockingWithTimeout {
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
    fun testSendRethrowsProgressException(): Unit = runBlockingWithTimeout {
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
    fun testSendTwoFilesInSameSessionWorks(): Unit = runBlockingWithTimeout {
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
    fun testSendFileWithNoDateSetsModifiedDateToCurrentTime(): Unit = runBlockingWithTimeout {
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
    fun testRecvFileWorks(): Unit = runBlockingWithTimeout {
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
    fun testRecvTwoFileInSameSessionWorks(): Unit = runBlockingWithTimeout {
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
    fun testSendThenRecvFileInSameSessionWorks(): Unit = runBlockingWithTimeout {
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
    fun testRecvFileThrowsExceptionIfFileDoesNotExist(): Unit = runBlockingWithTimeout {
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
    fun testRecvRethrowsProgressException(): Unit = runBlockingWithTimeout {
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
    fun testRecvRethrowsOutputChannelException(): Unit = runBlockingWithTimeout {
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
    fun testStatFileWorks(): Unit = runBlockingWithTimeout {
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
    fun testStatFileReturnsNullIfNoFile(): Unit = runBlockingWithTimeout {
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

    @Test
    fun testListWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        (1..9).forEach { index ->
            val filePath = "/sdcard/foo/bar.bin_$index"
            val fileBytes = createFileBytes(1_000)
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
        }

        // Act
        val entries = withSyncServices(deviceSelector) { syncServices ->
            syncServices.list("/sdcard/foo").toList()
        }

        // Assert
        Assert.assertEquals(9, entries.size)
        entries.sortedBy { it.fileName } .forEachIndexed { index, entry ->
            Assert.assertEquals("bar.bin_${index + 1}", entry.fileName)
            Assert.assertEquals(1_000, entry.fileStat.size)
            Assert.assertEquals("rw-------", entry.fileStat.remoteFileMode.posixString)
        }
    }

    @Test
    fun testListFlowCanBeCollectedMultipleTimes(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        (1..9).forEach { index ->
            val filePath = "/sdcard/foo/bar.bin_$index"
            val fileBytes = createFileBytes(1_000)
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
        }

        // Act
        val entriesList = withSyncServices(deviceSelector) { syncServices ->
            listOf(
                syncServices.list("/sdcard/foo").toList(),
                syncServices.list("/sdcard/foo").toList(),
                syncServices.list("/sdcard/foo").toList(),
            )
        }

        // Assert
        entriesList.forEach { entries ->
            Assert.assertEquals(9, entries.size)
            entries.sortedBy { it.fileName }.forEachIndexed { index, entry ->
                Assert.assertEquals("bar.bin_${index + 1}", entry.fileName)
                Assert.assertEquals(1_000, entry.fileStat.size)
                Assert.assertEquals("rw-------", entry.fileStat.remoteFileMode.posixString)
            }
        }
    }

    @Test
    fun testListSkipsDotAndDotDot(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb, sdk = 20)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)

        listOf(".", "..", ".foo", "bar.blah").map { fileName ->
            val filePath = "/sdcard/foo/$fileName"
            val fileBytes = createFileBytes(1_000)
            val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            ).also {
                fakeDevice.createFile(it)
            }
        }

        // Act
        val entries = withSyncServices(deviceSelector) { syncServices ->
            syncServices.list(remoteFilePath = "/sdcard/foo").toList()
        }

        // Assert
        Assert.assertEquals(2, entries.size)
        Assert.assertNotNull(entries.firstOrNull { it.fileName == ".foo"  })
        Assert.assertNotNull(entries.firstOrNull { it.fileName == "bar.blah"  })
    }

    @Test
    fun testListAllowsListingDotAndDotDot(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb, sdk = 20)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)

        listOf(".foo", "bar.blah").map { fileName ->
            val filePath = "/sdcard/foo/$fileName"
            val fileBytes = createFileBytes(1_000)
            val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            ).also {
                fakeDevice.createFile(it)
            }
        }

        // Act
        val entries = withSyncServices(deviceSelector) { syncServices ->
            syncServices.list(
                remoteFilePath = "/sdcard/foo",
                options = ListOptions(skipDotEntries = false)).toList()
        }

        // Assert
        Assert.assertEquals(4, entries.size)
        Assert.assertNotNull(entries.firstOrNull { it.fileName == "."  })
        Assert.assertNotNull(entries.firstOrNull { it.fileName == ".."  })
        Assert.assertNotNull(entries.firstOrNull { it.fileName == ".foo"  })
        Assert.assertNotNull(entries.firstOrNull { it.fileName == "bar.blah"  })
    }

    @Test
    fun testListFlowThrowsAfterSyncServicesIsClosed(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        (1..9).forEach { index ->
            val filePath = "/sdcard/foo/bar.bin_$index"
            val fileBytes = createFileBytes(1_000)
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
        }

        // Act
        val flow = withSyncServices(deviceSelector) { syncServices ->
            syncServices.list("/sdcard/foo")
        }

        // Assert
        exceptionRule.expect(ClosedChannelException::class.java)
        flow.collect {  }
        Assert.fail() // Should not be reached
    }

    @Test
    fun testStatV2FileWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val fileState = DeviceFileState(
            filePath,
            fileMode.modeBits,
            (fileDate.toMillis() / 1_000).toInt(),
            fileBytes
        )
        fakeDevice.createFile(
            fileState
        )

        // Act
        val fileStat = withSyncServices(deviceSelector) {
            it.statV2(filePath)
        }

        // Assert
        Assert.assertEquals(0, fileStat.errno)
        Assert.assertEquals(fileMode, fileStat.mode)
        Assert.assertEquals(128_000, fileStat.size)
        Assert.assertEquals(fileDate, fileStat.lastModifiedTime)
        Assert.assertEquals(fileDate, fileStat.creationTime)
        Assert.assertEquals(fileDate, fileStat.lastAccessTime)
        Assert.assertEquals(fileState.uid, fileStat.uid)
        Assert.assertEquals(fileState.gid, fileStat.gid)
        Assert.assertEquals(fileState.inode, fileStat.inode)
        Assert.assertEquals(fileState.dev, fileStat.dev)
        Assert.assertEquals(fileState.nlink, fileStat.nlink)
    }

    @Test
    fun testStatV2FileReturnsErrorEntryIfNoFile(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"

        // Act
        val fileStat = withSyncServices(deviceSelector) {
            it.statV2(filePath)
        }

        // Assert
        Assert.assertEquals(2, fileStat.errno)
        Assert.assertEquals(0, fileStat.mode.modeBits)
        Assert.assertEquals(0, fileStat.size)
        Assert.assertEquals(FileTime.fromMillis(0), fileStat.lastModifiedTime)
        Assert.assertEquals(FileTime.fromMillis(0), fileStat.creationTime)
        Assert.assertEquals(FileTime.fromMillis(0), fileStat.lastAccessTime)
        Assert.assertEquals(0, fileStat.uid)
        Assert.assertEquals(0, fileStat.gid)
        Assert.assertEquals(0, fileStat.inode)
        Assert.assertEquals(0, fileStat.dev)
        Assert.assertEquals(0, fileStat.nlink)
    }

    @Test
    fun testStatV2FileFallsBackToV1OnOlderDevices(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb, 20)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val fileState = DeviceFileState(
            filePath,
            fileMode.modeBits,
            (fileDate.toMillis() / 1_000).toInt(),
            fileBytes
        )
        fakeDevice.createFile(
            fileState
        )

        // Act
        val fileStat = withSyncServices(deviceSelector) {
            it.statV2(filePath)
        }

        // Assert
        Assert.assertEquals(0, fileStat.errno)
        Assert.assertEquals(fileMode, fileStat.mode)
        Assert.assertEquals(128_000, fileStat.size)
        Assert.assertEquals(fileDate, fileStat.lastModifiedTime)
        Assert.assertEquals(FileTime.fromMillis(0), fileStat.creationTime)
        Assert.assertEquals(FileTime.fromMillis(0), fileStat.lastAccessTime)
        Assert.assertEquals(0, fileStat.uid)
        Assert.assertEquals(0, fileStat.gid)
        Assert.assertEquals(0, fileStat.inode)
        Assert.assertEquals(0, fileStat.dev)
        Assert.assertEquals(0, fileStat.nlink)
    }

    @Test
    fun testStatV2ThrowsOnOlderDeviceWithoutFallback(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb, 20)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val fileState = DeviceFileState(
            filePath,
            fileMode.modeBits,
            (fileDate.toMillis() / 1_000).toInt(),
            fileBytes
        )
        fakeDevice.createFile(
            fileState
        )

        // Act
        withSyncServices(deviceSelector) {
            exceptionRule.expect(AdbDeviceFailResponseException::class.java)
            it.statV2(filePath, StatV2Options(fallbackToStatV1 = false))
        }

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun testListV2Works(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)

        val fileStates = (1..9).map { index ->
            val filePath = "/sdcard/foo/bar.bin_$index"
            val fileBytes = createFileBytes(1_000)
            val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            ).also {
                fakeDevice.createFile(it)
            }
        }

        // Act
        val options = ListV2Options(fallbackToListV1 = false)
        val entries = withSyncServices(deviceSelector) { syncServices ->
            syncServices.listV2(remoteFilePath = "/sdcard/foo", options = options).toList()
        }

        // Assert
        Assert.assertEquals(9, entries.size)
        entries.sortedBy { it.fileName } .forEachIndexed { index, entry ->
            val fileState = fileStates[index]
            Assert.assertEquals("bar.bin_${index + 1}", entry.fileName)
            Assert.assertEquals(0, entry.fileStat.errno)
            Assert.assertEquals(1_000, entry.fileStat.size)
            Assert.assertEquals(fileDate, entry.fileStat.lastModifiedTime)
            Assert.assertEquals(fileDate, entry.fileStat.lastAccessTime)
            Assert.assertEquals(fileDate, entry.fileStat.creationTime)
            Assert.assertEquals("rw-------", entry.fileStat.mode.posixString)
            Assert.assertEquals(fileState.uid, entry.fileStat.uid)
            Assert.assertEquals(fileState.gid, entry.fileStat.gid)
            Assert.assertEquals(fileState.inode, entry.fileStat.inode)
            Assert.assertEquals(fileState.dev, entry.fileStat.dev)
            Assert.assertEquals(fileState.nlink, entry.fileStat.nlink)
        }
    }

    @Test
    fun testListV2FallsBackToListV1OnOlderDevice(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb, sdk = 20)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)

        (1..9).map { index ->
            val filePath = "/sdcard/foo/bar.bin_$index"
            val fileBytes = createFileBytes(1_000)
            val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            ).also {
                fakeDevice.createFile(it)
            }
        }

        // Act
        val entries = withSyncServices(deviceSelector) { syncServices ->
            syncServices.listV2(remoteFilePath = "/sdcard/foo").toList()
        }

        // Assert
        Assert.assertEquals(9, entries.size)
        entries.sortedBy { it.fileName } .forEachIndexed { index, entry ->
            Assert.assertEquals("bar.bin_${index + 1}", entry.fileName)
            Assert.assertEquals(0, entry.fileStat.errno)
            Assert.assertEquals(1_000, entry.fileStat.size)
            Assert.assertEquals(fileDate, entry.fileStat.lastModifiedTime)
            Assert.assertEquals(FileTime.fromMillis(0), entry.fileStat.lastAccessTime)
            Assert.assertEquals(FileTime.fromMillis(0), entry.fileStat.creationTime)
            Assert.assertEquals("rw-------", entry.fileStat.mode.posixString)
            Assert.assertEquals(0, entry.fileStat.uid)
            Assert.assertEquals(0, entry.fileStat.gid)
            Assert.assertEquals(0, entry.fileStat.inode)
            Assert.assertEquals(0, entry.fileStat.dev)
            Assert.assertEquals(0, entry.fileStat.nlink)
        }
    }

    @Test
    fun testListV2ThrowsOnOlderDeviceWithoutFallback(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb, sdk = 20)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)

        (1..9).map { index ->
            val filePath = "/sdcard/foo/bar.bin_$index"
            val fileBytes = createFileBytes(1_000)
            val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            ).also {
                fakeDevice.createFile(it)
            }
        }

        // Act
        withSyncServices(deviceSelector) { syncServices ->
            val options = ListV2Options(fallbackToListV1 = false)
            exceptionRule.expect(AdbDeviceFailResponseException::class.java)
            syncServices.listV2(remoteFilePath = "/sdcard/foo", options).toList()
        }

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun testListV2SkipsDotAndDotDot(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb, sdk = 20)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)

        listOf(".", "..", ".foo", "bar.blah").map { fileName ->
            val filePath = "/sdcard/foo/$fileName"
            val fileBytes = createFileBytes(1_000)
            val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            ).also {
                fakeDevice.createFile(it)
            }
        }

        // Act
        val entries = withSyncServices(deviceSelector) { syncServices ->
            syncServices.listV2(remoteFilePath = "/sdcard/foo").toList()
        }

        // Assert
        Assert.assertEquals(2, entries.size)
        Assert.assertNotNull(entries.firstOrNull { it.fileName == ".foo"  })
        Assert.assertNotNull(entries.firstOrNull { it.fileName == "bar.blah"  })
    }

    @Test
    fun testListV2AllowsListingDotAndDotDot(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeDevice(fakeAdb, sdk = 20)
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)

        listOf(".foo", "bar.blah").map { fileName ->
            val filePath = "/sdcard/foo/$fileName"
            val fileBytes = createFileBytes(1_000)
            val fileMode = RemoteFileMode.fromPosixPermissions(OWNER_READ, OWNER_WRITE)
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            ).also {
                fakeDevice.createFile(it)
            }
        }

        // Act
        val entries = withSyncServices(deviceSelector) { syncServices ->
            syncServices.listV2(remoteFilePath = "/sdcard/foo", options = ListV2Options(skipDotEntries = false)).toList()
        }

        // Assert
        Assert.assertEquals(4, entries.size)
        Assert.assertNotNull(entries.firstOrNull { it.fileName == "."  })
        Assert.assertNotNull(entries.firstOrNull { it.fileName == ".."  })
        Assert.assertNotNull(entries.firstOrNull { it.fileName == ".foo"  })
        Assert.assertNotNull(entries.firstOrNull { it.fileName == "bar.blah"  })
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
