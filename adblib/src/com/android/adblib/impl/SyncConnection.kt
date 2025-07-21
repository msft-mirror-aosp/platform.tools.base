/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.adblib.impl

import com.android.adblib.AdbBufferedOutputChannel
import com.android.adblib.AdbChannel
import com.android.adblib.AdbInputChannel
import com.android.adblib.AdbOutputChannel
import com.android.adblib.AdbProtocolErrorException
import com.android.adblib.AdbSession
import com.android.adblib.AutoShutdown
import com.android.adblib.DeviceSelector
import com.android.adblib.FileStat
import com.android.adblib.RemoteFileMode
import com.android.adblib.adbLogger
import com.android.adblib.impl.services.AdbServiceRunner
import com.android.adblib.read
import com.android.adblib.utils.AdbProtocolUtils
import com.android.adblib.utils.ResizableBuffer
import com.android.adblib.withPrefix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * Helper class for all SyncXxxHandler classes
 */
internal class SyncConnection(
    val device: DeviceSelector,
    private val serviceRunner: AdbServiceRunner,
    private val workBuffer: ResizableBuffer,
    private val deviceChannel: AdbChannel,
    readAheadBufferSize: Int = -1,
    writeBackBufferSize: Int = -1
) : AutoShutdown {

    private val logger = adbLogger(session).withPrefix("device:$device")

    val session: AdbSession
        get() = serviceRunner.session

    private val inputChannel: AdbInputChannel = if (readAheadBufferSize > 0) {
        session.channelFactory.createBufferedInputChannel(
            input = deviceChannel,
            bufferSize = readAheadBufferSize,
            closeInputChannel = false
        )
    } else {
        deviceChannel
    }

    private val outputChannel: AdbOutputChannel = if (writeBackBufferSize > 0) {
        session.channelFactory.createWriteBackChannel(
            output = deviceChannel,
            bufferSize = writeBackBufferSize
        )
    } else {
        deviceChannel
    }

    init {
        // All "sync" services use LITTLE_ENDIAN for integer values
        workBuffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun close() {
        outputChannel.close()
        inputChannel.close()
        deviceChannel.close()
    }

    override suspend fun shutdown() {
        (outputChannel as? AutoShutdown)?.shutdown()
        (inputChannel as? AutoShutdown)?.shutdown()
        deviceChannel.shutdownInput()
        deviceChannel.shutdownOutput()
    }

    suspend fun readExactly(length: Int): ByteBuffer {
        workBuffer.clear()
        inputChannel.readExactly(workBuffer.forChannelRead(length))
        return workBuffer.afterChannelRead()
    }

    suspend fun writeExactly(buffer: ByteBuffer) {
        outputChannel.writeExactly(buffer)
    }

    suspend fun startSyncRequest(syncRequestId: String, remoteFilePath: String) {
        logger.debug { "sending \"$syncRequestId\" command to device $device" }
        // Bytes 0-3: '<syncRequestId>'
        // Bytes 4-7: request size (little endian)
        // Bytes 8-xx: An utf-8 string with the remote file path
        workBuffer.clear()
        workBuffer.appendString(syncRequestId, AdbProtocolUtils.ADB_CHARSET)
        val lengthPos = workBuffer.position
        workBuffer.appendInt(0) // Set later
        workBuffer.appendString(remoteFilePath, AdbProtocolUtils.ADB_CHARSET)
        workBuffer.setInt(lengthPos, workBuffer.position - 8)

        outputChannel.writeExactly(workBuffer.forChannelWrite())
    }

    suspend fun sendDoneRequest(value: Int) {
        // Bytes 0-3: 'DONE'
        // Bytes 4-7: status/value
        workBuffer.clear()
        workBuffer.appendString("DONE", AdbProtocolUtils.ADB_CHARSET)
        workBuffer.appendInt(value)
        outputChannel.writeExactly(workBuffer.forChannelWrite())
    }

    fun parseStatBuffer(syncRequestId: String, expectedId: Int, buffer: ByteBuffer): FileStat {
        // See https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT
        // 1. A four-byte sync response id "DENT" (or "DONE" if no more entries)
        // 2. A four-byte integer representing file mode (or 0 if no more entries)
        // 3. A four-byte integer representing file size (or 0 if no more entries)
        // 4. A four-byte integer representing last modified time (or 0 if no more entries)
        parseBufferHeader(syncRequestId, buffer, expectedId, Constants.STAT_BUFFER_SIZE)
        val mode = buffer.getInt()
        val size = buffer.getInt()
        val lastModifiedSecs = buffer.getInt()
        return FileStat(
            RemoteFileMode.fromModeBits(mode),
            size,
            FileTime.from(lastModifiedSecs.toLong(), TimeUnit.SECONDS)
        )
    }

    suspend fun readSyncFailMessageAndThrow(service: String, buffer: ByteBuffer) {
        buffer.getInt() // Consume 'FAIL'
        val length = buffer.getInt() // Consume length
        serviceRunner.readSyncFailMessageAndThrow(
            device,
            service,
            inputChannel,
            workBuffer,
            length,
            TimeoutTracker.INFINITE
        )
    }

    suspend fun consumeSyncOkayFailResponse(service: String) {
        serviceRunner.consumeSyncOkayFailResponse(
            device,
            service,
            inputChannel,
            workBuffer,
            TimeoutTracker.INFINITE
        )
    }

    /**
     * Ensures the connection to the "sync" service is property terminated, meaning
     * all data have been received and processed by the sync service on the device.
     */
    suspend fun readForOrderlyShutdown(){
        // Shutdown socket so that adb server knows no more data is coming
        (outputChannel as? AdbBufferedOutputChannel)?.flush()
        deviceChannel.shutdownOutput()

        // Read data until we get EOF to ensure adb daemon has received and processed all data
        // that was previously sent.
        // See https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/adb_io.cpp;l=151
        workBuffer.clear()
        inputChannel.read(workBuffer.forChannelRead(16)).also { byteCount ->
            when {
                byteCount < 0 -> {
                    logger.verbose { "Orderly shutdown successful: no more data from peer" }
                }
                byteCount >= 0 -> {
                    val extraDataString = AdbProtocolUtils.bufferToByteDumpString(workBuffer.afterChannelRead())
                    val message = "The 'sync' service sent unexpected data when an EOF was expected: $extraDataString"
                    // Note: We log a "warning" here (in addition to throwing an exception) because this condition indicates
                    // an implementation error either in `adblib` or in adb server or in adb daemon. In all cases, logging can
                    // be useful to help identify the root cause of such an event.
                    logger.warn(message)
                    throw AdbProtocolErrorException(message)
                }
            }
        }
    }

    private fun parseBufferHeader(syncRequestId: String, buffer: ByteBuffer, expectedId: Int, expectedSize: Int) {
        if (buffer.remaining() < expectedSize) {
            throw IllegalArgumentException("Buffer should contain at least $expectedSize bytes")
        }
        buffer.getInt().also {
            if (it != expectedId) {
                val contents = AdbProtocolUtils.bufferToByteDumpString(buffer)
                val errorMessage =
                    "Received an invalid packet from a $syncRequestId sync query: $contents"
                throw AdbProtocolErrorException(errorMessage)
            }
        }
    }

    fun prepareDataRequest(bufferSize: Int): ByteBuffer {
        // Bytes 0-3: 'DATA'
        // Bytes 4-7: request size (little endian)
        // Bytes 8-xx: file bytes
        workBuffer.clear()
        workBuffer.appendString("DATA", AdbProtocolUtils.ADB_CHARSET)
        workBuffer.appendInt(0) // Set later
        val headerLength = workBuffer.position
        return workBuffer.forChannelRead(bufferSize - headerLength)
    }

    suspend fun sendPreparedDataRequest(byteCount: Int) {
        if (byteCount > 0) {
            workBuffer.setInt(4, byteCount)
            val writeBuffer = workBuffer.afterChannelRead(useMarkedPosition = false)
            assert(writeBuffer.remaining() == 8 + byteCount)
            outputChannel.writeExactly(writeBuffer)
        }
    }

    object Constants {
        const val STAT_ID = 0x54415453 // "STAT"
        const val STAT_BUFFER_SIZE = 16
    }
}
