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
import com.android.adblib.AdbFeatures
import com.android.adblib.AdbInputChannel
import com.android.adblib.AdbOutputChannel
import com.android.adblib.AdbProtocolErrorException
import com.android.adblib.AdbSession
import com.android.adblib.AutoShutdown
import com.android.adblib.DeviceSelector
import com.android.adblib.DirectoryEntry
import com.android.adblib.DirectoryEntryV2
import com.android.adblib.FileStat
import com.android.adblib.FileStatV2
import com.android.adblib.RemoteFileMode
import com.android.adblib.adbLogger
import com.android.adblib.availableFeatures
import com.android.adblib.impl.services.AdbServiceRunner
import com.android.adblib.read
import com.android.adblib.utils.AdbProtocolUtils
import com.android.adblib.utils.ResizableBuffer
import com.android.adblib.withPrefix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/** Helper class for all SyncXxxHandler classes */
internal class SyncConnection(
  val device: DeviceSelector,
  private val serviceRunner: AdbServiceRunner,
  private val workBuffer: ResizableBuffer,
  private val deviceChannel: AdbChannel,
  readAheadBufferSize: Int = -1,
  writeBackBufferSize: Int = -1,
) : AutoShutdown {

  private val logger = adbLogger(session).withPrefix("device:$device")

  val session: AdbSession
    get() = serviceRunner.session

  private val inputChannel: AdbInputChannel =
    if (readAheadBufferSize > 0) {
      session.channelFactory.createBufferedInputChannel(input = deviceChannel, bufferSize = readAheadBufferSize, closeInputChannel = false)
    } else {
      deviceChannel
    }

  private val outputChannel: AdbOutputChannel =
    if (writeBackBufferSize > 0) {
      session.channelFactory.createWriteBackChannel(output = deviceChannel, bufferSize = writeBackBufferSize)
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

  suspend fun canUseListV2(): Boolean {
    return session.hostServices.availableFeatures(device).contains(AdbFeatures.LS_V2)
  }

  suspend fun canUseStatV2(): Boolean {
    return session.hostServices.availableFeatures(device).contains(AdbFeatures.STAT_V2)
  }

  suspend fun readExactly(length: Int): ByteBuffer {
    workBuffer.clear()
    inputChannel.readExactly(workBuffer.forChannelRead(length))
    return workBuffer.afterChannelRead()
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

  suspend fun readFileStat(syncRequestId: String): FileStat {
    // https://cs.android.com/android/platform/superproject/+/4737fae6acca4046f5c9c0851ef6349378affafb:packages/modules/adb/file_sync_protocol.h;l=48
    //    struct __attribute__((packed)) sync_stat_v1 {
    //       uint32_t id;
    //       uint32_t mode;
    //       uint32_t size;
    //       uint32_t mtime;
    //    };
    val id = readExactly(4).getSyncId()
    return when (id) {
      Constants.FAIL_ID -> {
        // Sometimes a query FAIL explicitly (e.g. `STA2` may not be supported on the device)
        val failMessageLength = this.readExactly(4).getInt()
        this.readSyncFailMessageAndThrow(syncRequestId, failMessageLength)
      }

      Constants.STAT_ID -> {
        // Read rest of "stat" bytes
        val buffer = this.readExactly(Constants.STAT_BUFFER_SIZE - 4)
        parseStatBuffer(buffer)
      }

      else -> {
        throwUnexpectedSyncReplyId(id)
      }
    }
  }

  suspend fun readFileStatV2(syncRequestId: String): FileStatV2 {
    // https://cs.android.com/android/platform/superproject/+/4737fae6acca4046f5c9c0851ef6349378affafb:packages/modules/adb/file_sync_protocol.h;l=55
    //     struct __attribute__((packed)) sync_stat_v2 {
    //  0    uint32_t id;    /* "STA2" or "DNT2" */
    //  4    uint32_t error; /* Linux `errno` of the `stat` call */
    //  8    uint64_t dev;   /* ID of device containing file */
    // 16    uint64_t ino;   /* Inode number */
    // 24    uint32_t mode;  /* File type and mode */
    // 28    uint32_t nlink; /* Number of hard links */
    // 32    uint32_t uid;   /* User ID of owner */
    // 36    uint32_t gid;   /* Group ID of owner */
    // 40    uint64_t size;  /* Total size, in bytes */
    // 48    int64_t atime;  /* Time of last access */
    // 56    int64_t mtime;  /* Time of last modification */
    // 64    int64_t ctime;  /* Time of last status change */
    //     };
    val id = readExactly(4).getSyncId()
    return when (id) {
      Constants.FAIL_ID -> {
        // Sometimes a query FAIL explicitly (e.g. `STA2` may not be supported on the device)
        val failMessageLength = this.readExactly(4).getInt()
        this.readSyncFailMessageAndThrow(syncRequestId, failMessageLength)
      }

      Constants.STAT_V2_ID -> {
        // Read rest of "stat" bytes
        val buffer = this.readExactly(Constants.STAT_V2_BUFFER_SIZE - 4)
        parseStatV2Buffer(buffer)
      }

      else -> {
        throwUnexpectedSyncReplyId(id)
      }
    }
  }

  suspend fun readDirectoryEntry(syncRequestId: String): DirectoryEntry? {
    // https://cs.android.com/android/platform/superproject/+/4737fae6acca4046f5c9c0851ef6349378affafb:packages/modules/adb/file_sync_protocol.h;l=70
    //    struct __attribute__((packed)) sync_dent_v1 {
    //       uint32_t id;
    //       uint32_t mode;
    //       uint32_t size;
    //       uint32_t mtime;
    //       uint32_t namelen;
    //    };  // followed by `namelen` bytes of the name.
    val id = readExactly(4).getSyncId()

    return when (id) {
      Constants.FAIL_ID -> {
        // Sometimes a query FAIL explicitly (e.g. `LIS2` may not be supported on the device)
        val failMessageLength = readExactly(4).getInt()
        readSyncFailMessageAndThrow(syncRequestId, failMessageLength)
      }

      Constants.DONE_ID -> {
        // Read rest of "stat" bytes (all bytes should be "0")
        readExactly(Constants.DENT_BUFFER_SIZE - 4)
        null
      }

      Constants.DENT_ID -> {
        // Read rest of "stat" bytes
        val buffer = readExactly(Constants.DENT_BUFFER_SIZE - 4)
        val fileStat = parseStatBuffer(buffer)
        val fileName = readFileName(buffer)
        DirectoryEntry(fileName, fileStat)
      }

      else -> {
        throwUnexpectedSyncReplyId(id)
      }
    }
  }

  suspend fun readDirectoryEntryV2(syncRequestId: String): DirectoryEntryV2? {
    // See
    // https://cs.android.com/android/platform/superproject/+/4737fae6acca4046f5c9c0851ef6349378affafb:packages/modules/adb/file_sync_protocol.h;l=78
    //     struct __attribute__((packed)) sync_dent_v2 {
    //  0    uint32_t id;    /* "STA2" or "DNT2" */
    //  4    uint32_t error; /* Linux `errno` of the `stat` call */
    //  8    uint64_t dev;   /* ID of device containing file */
    // 16    uint64_t ino;   /* Inode number */
    // 24    uint32_t mode;  /* File type and mode */
    // 28    uint32_t nlink; /* Number of hard links */
    // 32    uint32_t uid;   /* User ID of owner */
    // 36    uint32_t gid;   /* Group ID of owner */
    // 40    uint64_t size;  /* Total size, in bytes */
    // 48    int64_t atime;  /* Time of last access */
    // 56    int64_t mtime;  /* Time of last modification */
    // 64    int64_t ctime;  /* Time of last status change */
    // 72    uint32_t namelen;
    // 76   };  // followed by `namelen` bytes of the name.
    val id = readExactly(4).getSyncId()

    return when (id) {
      Constants.FAIL_ID -> {
        // Sometimes a query FAIL explicitly (e.g. `LIS2` may not be supported on the device)
        val failMessageLength = readExactly(4).getInt()
        readSyncFailMessageAndThrow(syncRequestId, failMessageLength)
      }

      Constants.DONE_ID -> {
        // Read rest of "stat" bytes (all bytes should be "0")
        readExactly(Constants.DENT_V2_BUFFER_SIZE - 4)
        null
      }

      Constants.DENT_V2_ID -> {
        val buffer = readExactly(Constants.DENT_V2_BUFFER_SIZE - 4)
        // Note: A `DNT2` entry is a `STA2` entry followed by a filename
        val fileStat = parseStatV2Buffer(buffer)
        val fileName = readFileName(buffer)
        return DirectoryEntryV2(fileName, fileStat)
      }

      else -> {
        throwUnexpectedSyncReplyId(id)
      }
    }
  }

  private fun parseStatBuffer(buffer: ByteBuffer): FileStat {
    // https://cs.android.com/android/platform/superproject/+/4737fae6acca4046f5c9c0851ef6349378affafb:packages/modules/adb/file_sync_protocol.h;l=48
    //    struct __attribute__((packed)) sync_stat_v1 {
    //       uint32_t id;
    //       uint32_t mode;
    //       uint32_t size;
    //       uint32_t mtime;
    //    };
    val mode = buffer.getInt()
    val size = buffer.getInt()
    val lastModifiedSecs = buffer.getInt()
    return FileStat(RemoteFileMode.fromModeBits(mode), size, FileTime.from(lastModifiedSecs.toLong(), TimeUnit.SECONDS))
  }

  private fun parseStatV2Buffer(buffer: ByteBuffer): FileStatV2 {
    // See
    // https://cs.android.com/android/platform/superproject/+/4737fae6acca4046f5c9c0851ef6349378affafb:packages/modules/adb/file_sync_protocol.h;l=55
    //     struct __attribute__((packed)) sync_stat_v2 {
    //  0    uint32_t id;    /* "STA2" or "DNT2" */
    //  4    uint32_t error; /* Linux `errno` of the `stat` call */
    //  8    uint64_t dev;   /* ID of device containing file */
    // 16    uint64_t ino;   /* Inode number */
    // 24    uint32_t mode;  /* File type and mode */
    // 28    uint32_t nlink; /* Number of hard links */
    // 32    uint32_t uid;   /* User ID of owner */
    // 36    uint32_t gid;   /* Group ID of owner */
    // 40    uint64_t size;  /* Total size, in bytes */
    // 48    int64_t atime;  /* Time of last access */
    // 56    int64_t mtime;  /* Time of last modification */
    // 64    int64_t ctime;  /* Time of last status change */
    // 72  };
    val errno = buffer.getInt()
    val dev = buffer.getLong()
    val ino = buffer.getLong()
    val mode = buffer.getInt()
    val nlink = buffer.getInt()
    val uid = buffer.getInt()
    val gid = buffer.getInt()
    val size = buffer.getLong()
    val atime = parseTimeSpec(buffer)
    val mtime = parseTimeSpec(buffer)
    val ctime = parseTimeSpec(buffer)

    return buffer.run {
      FileStatV2(
        errno = errno,
        mode = RemoteFileMode.fromModeBits(mode),
        size = size,
        lastModifiedTime = mtime,
        lastAccessTime = atime,
        creationTime = ctime,
        dev = dev,
        inode = ino,
        nlink = nlink,
        uid = uid,
        gid = gid,
      )
    }
  }

  private suspend fun readFileName(buffer: ByteBuffer): String {
    require(buffer.remaining() >= 4) { throw IllegalArgumentException("Buffer should contain at least 4 bytes for path length") }
    val fileNameLength = buffer.getInt()
    // Read file name bytes
    workBuffer.clear()
    return inputChannel.readExactly(workBuffer.forChannelRead(fileNameLength)).let {
      workBuffer.afterChannelRead().let { buffer -> AdbProtocolUtils.byteBufferToString(buffer) }
    }
  }

  suspend fun readSyncFailMessageAndThrow(service: String, buffer: ByteBuffer): Nothing {
    buffer.getInt() // Consume 'FAIL'
    val length = buffer.getInt() // Consume length of message associated to 'FAIL'
    readSyncFailMessageAndThrow(service, length)
  }

  private suspend fun readSyncFailMessageAndThrow(service: String, length: Int): Nothing {
    serviceRunner.readSyncFailMessageAndThrow(device, service, inputChannel, workBuffer, length, TimeoutTracker.INFINITE)
  }

  suspend fun consumeSyncOkayFailResponse(service: String) {
    serviceRunner.consumeSyncOkayFailResponse(device, service, inputChannel, workBuffer, TimeoutTracker.INFINITE)
  }

  /**
   * Ensures the connection to the "sync" service is property terminated, meaning all data have been received and processed by the sync
   * service on the device.
   */
  suspend fun readForOrderlyShutdown() {
    // Shutdown socket so that adb server knows no more data is coming
    (outputChannel as? AdbBufferedOutputChannel)?.flush()
    deviceChannel.shutdownOutput()

    // Read data until we get EOF to ensure adb daemon has received and processed all data
    // that was previously sent.
    // See
    // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/adb_io.cpp;l=151
    workBuffer.clear()
    inputChannel.read(workBuffer.forChannelRead(16)).also { byteCount ->
      when {
        byteCount < 0 -> {
          logger.verbose { "Orderly shutdown successful: no more data from peer" }
        }
        byteCount >= 0 -> {
          val extraDataString = AdbProtocolUtils.bufferToByteDumpString(workBuffer.afterChannelRead())
          val message = "The 'sync' service sent unexpected data when an EOF was expected: $extraDataString"
          // Note: We log a "warning" here (in addition to throwing an exception) because this
          // condition indicates
          // an implementation error either in `adblib` or in adb server or in adb daemon. In all
          // cases, logging can
          // be useful to help identify the root cause of such an event.
          logger.warn(message)
          throw AdbProtocolErrorException(message)
        }
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

  /**
   * Reads a 64-bit [timespec](https://man7.org/linux/man-pages/man3/timespec.3type.html) structure from [buffer] and returns the equivalent
   * [FileTime] value.
   */
  private fun parseTimeSpec(buffer: ByteBuffer): FileTime {
    // struct timespec {
    //    time_t   tv_sec;   /* Seconds */
    //    uint     tv_nsec;  /* Nanoseconds [0, 999'999'999] */
    // };
    val seconds = buffer.getInt() // tv_sec
    val nanos = buffer.getInt() // tv_nsec
    val fullNanos = seconds * 1_000_000_000L + nanos
    return FileTime.from(fullNanos, TimeUnit.NANOSECONDS)
  }

  private fun ByteBuffer.getSyncId(): Int {
    return getInt()
  }

  private fun throwUnexpectedSyncReplyId(id: Int): Nothing {
    val errorMessage = "Received an unexpected sync reply message ID: $id"
    throw AdbProtocolErrorException(errorMessage)
  }

  object Constants {
    const val FAIL_ID = 0x4C494146 // "FAIL"
    const val DONE_ID = 0x454E4F44 // "DONE"

    const val STAT_ID = 0x54415453 // "STAT"
    const val STAT_BUFFER_SIZE = 16

    const val DENT_ID = 0x544E4544 // "DENT"
    const val DENT_BUFFER_SIZE = STAT_BUFFER_SIZE + 4

    const val STAT_V2_ID = 0x32415453 // "STA2"
    const val STAT_V2_BUFFER_SIZE = 72

    const val DENT_V2_ID = 0x32544E44 // "DNT2"
    const val DENT_V2_BUFFER_SIZE = STAT_V2_BUFFER_SIZE + 4
  }
}
