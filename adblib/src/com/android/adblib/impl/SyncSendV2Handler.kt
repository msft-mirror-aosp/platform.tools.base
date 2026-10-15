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
package com.android.adblib.impl

import com.android.adblib.AdbDeviceSyncServices.SendV2Compression
import com.android.adblib.AdbInputChannel
import com.android.adblib.RemoteFileMode
import com.android.adblib.SYNC_DATA_MAX
import com.android.adblib.SyncProgress
import com.android.adblib.adbLogger
import com.android.adblib.impl.SyncConnection.Constants.DATA_HEADER_SIZE
import com.android.adblib.impl.SyncConnection.Constants.SEND_V2
import com.android.adblib.impl.SyncConnection.Constants.SEND_V2_FLAG_LZ4
import com.android.adblib.impl.SyncConnection.Constants.SEND_V2_FLAG_NONE
import com.android.adblib.read
import com.android.adblib.utils.AdbProtocolUtils
import com.android.adblib.withPrefix
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.attribute.FileTime
import java.util.Objects
import kotlinx.coroutines.withContext
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FrameOutputStream
import net.jpountz.xxhash.XXHash32
import net.jpountz.xxhash.XXHashFactory

/**
 * Implementation of the `SND2` request of the `SYNC` command, i.e. the "sync v2" version of the `SEND` request, which supports compressing
 * the contents of the file on the fly.
 *
 * See [file_sync_protocol.h](https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/adb/file_sync_protocol.h)
 * and
 * [file_sync_service.cpp](https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/adb/daemon/file_sync_service.cpp)
 */
internal class SyncSendV2Handler(private val connection: SyncConnection) {

  private val logger = adbLogger(connection.session).withPrefix("device:${connection.device},sync:$SEND_V2 - ")

  /**
   * Sends the contents of [sourceChannel] to the [remoteFilePath] file using the following sequence of "sync" packets:
   * * `SND2`: The remote file path, followed by a `sync_send_v2` structure containing the file mode and the compression flag.
   * * `DATA`: The (compressed) file contents, split in packets of at most [bufferSize] bytes (including the packet header). When
   *   compression is enabled, the ADB daemon decompresses the payloads of all the `DATA` packets of the file as a *single* continuous
   *   stream, i.e. a compressed block can span multiple `DATA` packets.
   * * `DONE`: The last modification time of the file, acknowledged by either an `OKAY` or a `FAIL` response from the ADB daemon.
   *
   * [compression] must be either [SendV2Compression.NONE] or [SendV2Compression.LZ4], i.e. the caller is responsible for resolving
   * [SendV2Compression.AUTO] (see [AdbDeviceSyncServicesImpl.sendV2]).
   *
   * If [remoteFileTime] is not provided, it defaults to the current system time.
   *
   * [progress] reports the number of (uncompressed) bytes read from [sourceChannel].
   */
  suspend fun sendV2(
    sourceChannel: AdbInputChannel,
    remoteFilePath: String,
    remoteFileMode: RemoteFileMode,
    remoteFileTime: FileTime?,
    progress: SyncProgress?,
    bufferSize: Int,
    compression: SendV2Compression,
  ) {
    withContext(connection.session.ioDispatcher) {
      logger.info { "$sourceChannel -> \"$remoteFilePath\" (compression=$compression)" }

      require(remoteFilePath.length <= REMOTE_PATH_MAX_LENGTH) {
        "Remote paths are limited to $REMOTE_PATH_MAX_LENGTH characters"
      }
      require(bufferSize > DATA_HEADER_SIZE) { "Buffer size ($bufferSize) must be greater than $DATA_HEADER_SIZE bytes" }
      val flags =
        when (compression) {
          SendV2Compression.NONE -> SEND_V2_FLAG_NONE
          SendV2Compression.LZ4 -> SEND_V2_FLAG_LZ4
          SendV2Compression.AUTO -> throw IllegalArgumentException("Compression '$compression' should be resolved by the caller")
        }
      val remoteFileEpoch =
        AdbProtocolUtils.convertFileTimeToEpochSeconds(remoteFileTime ?: FileTime.from(connection.session.host.utcNow()))

      // Send the file using the "SND2" request
      progress?.transferStarted(remoteFilePath)
      connection.startSyncSendV2Request(remoteFilePath, remoteFileMode.modeBits, flags)

      // Send the (compressed) contents of the file from the input channel
      val byteCount =
        when (compression) {
          SendV2Compression.LZ4 -> sendLz4FileContents(remoteFilePath, sourceChannel, bufferSize, progress)
          else -> sendUncompressedFileContents(remoteFilePath, sourceChannel, bufferSize, progress)
        }

      // Finish the file with the last modification date
      logger.debug { "Committing remote file $remoteFilePath ($byteCount bytes)" }
      connection.sendDoneRequest(remoteFileEpoch)
      progress?.transferDone(remoteFilePath, byteCount)

      // The `SND2` operation is acknowledged by either "OKAY" or "FAIL" (in "SYNC" format)
      connection.consumeSyncOkayFailResponse("sync-send-v2('$remoteFilePath')")
    }
  }

  private suspend fun sendUncompressedFileContents(
    remoteFilePath: String,
    sourceChannel: AdbInputChannel,
    bufferSize: Int,
    progress: SyncProgress?,
  ): Long {
    var totalBytesSoFar = 0L
    while (true) {
      val buffer = connection.prepareDataRequest(bufferSize)
      val byteCount = sourceChannel.read(buffer)
      if (byteCount <= 0) {
        // We reached EOF, we are done
        break
      }
      connection.sendPreparedDataRequest(byteCount)

      totalBytesSoFar += byteCount
      progress?.transferProgress(remoteFilePath, totalBytesSoFar)
    }

    logger.debug { "Done sending file contents to '$remoteFilePath' ($totalBytesSoFar bytes written)" }
    return totalBytesSoFar
  }

  /**
   * Sends the contents of [sourceChannel] as a single [LZ4 frame](https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md), which is
   * what the ADB daemon expects.
   *
   * Note: The frame is always terminated (i.e. it contains an "end mark"), even if [sourceChannel] is empty, because the ADB daemon waits
   * for the end of the frame before acknowledging the `DONE` request.
   */
  private suspend fun sendLz4FileContents(
    remoteFilePath: String,
    sourceChannel: AdbInputChannel,
    bufferSize: Int,
    progress: SyncProgress?,
  ): Long {
    val maxPayloadSize = bufferSize - DATA_HEADER_SIZE
    val compressedBytes = CompressedBytesAccumulator()

    // Note: The frame header is written to `compressedBytes` by the constructor. Compressed blocks are written to `compressedBytes`
    // every time the input of the stream reaches the maximum block size.
    val lz4Stream =
      LZ4FrameOutputStream(
        compressedBytes,
        LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB,
        -1L,
        lz4Compressor,
        xxHash32,
        LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
      )
    val readBuffer = ByteBuffer.allocate(maxPayloadSize)
    var totalBytesSoFar = 0L
    var totalCompressedBytesSoFar = 0L
    while (true) {
      readBuffer.clear()
      val byteCount = sourceChannel.read(readBuffer)
      if (byteCount <= 0) {
        // We reached EOF, we are done
        break
      }
      lz4Stream.write(readBuffer.array(), readBuffer.arrayOffset(), byteCount)
      totalCompressedBytesSoFar += sendCompressedBytes(compressedBytes, maxPayloadSize, sendAll = false)

      totalBytesSoFar += byteCount
      progress?.transferProgress(remoteFilePath, totalBytesSoFar)
    }

    // Write the last block and the "end mark" of the frame, then send all remaining bytes
    lz4Stream.close()
    totalCompressedBytesSoFar += sendCompressedBytes(compressedBytes, maxPayloadSize, sendAll = true)

    logger.debug {
      "Done sending file contents to '$remoteFilePath' ($totalBytesSoFar bytes compressed to $totalCompressedBytesSoFar bytes)"
    }
    return totalBytesSoFar
  }

  /**
   * Sends the bytes of [compressedBytes] as `DATA` packets of [maxPayloadSize] bytes. If [sendAll] is `false`, the remaining bytes (if any)
   * are kept in [compressedBytes] so that they can be sent along with the next compressed bytes, to minimize the number of packets.
   *
   * Returns the number of bytes sent.
   */
  private suspend fun sendCompressedBytes(compressedBytes: CompressedBytesAccumulator, maxPayloadSize: Int, sendAll: Boolean): Long {
    var bytesSent = 0L
    while (compressedBytes.size >= maxPayloadSize || (sendAll && compressedBytes.size > 0)) {
      val payloadSize = minOf(compressedBytes.size, maxPayloadSize)
      val buffer = connection.prepareDataRequest(DATA_HEADER_SIZE + payloadSize)
      buffer.put(compressedBytes.array, compressedBytes.offset, payloadSize)
      connection.sendPreparedDataRequest(payloadSize)
      compressedBytes.consume(payloadSize)
      bytesSent += payloadSize
    }
    return bytesSent
  }

  /**
   * An [OutputStream] that accumulates the bytes written by a [LZ4FrameOutputStream] until they are sent to the device, see
   * [sendCompressedBytes].
   */
  private class CompressedBytesAccumulator : OutputStream() {

    /** The underlying storage, containing [size] bytes starting at [offset] */
    var array = ByteArray(INITIAL_CAPACITY)
      private set

    var offset = 0
      private set

    var size = 0
      private set

    override fun write(b: Int) {
      ensureCapacity(1)
      array[offset + size] = b.toByte()
      size++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      Objects.checkFromIndexSize(off, len, b.size)
      ensureCapacity(len)
      System.arraycopy(b, off, array, offset + size, len)
      size += len
    }

    /** Removes the first [count] bytes */
    fun consume(count: Int) {
      require(count in 0..size) { "Invalid byte count: $count (size=$size)" }
      offset += count
      size -= count
      if (size == 0) {
        offset = 0
      }
    }

    private fun ensureCapacity(count: Int) {
      if (offset + size + count <= array.size) {
        return
      }
      // Move bytes to the start of the array
      if (offset > 0) {
        System.arraycopy(array, offset, array, 0, size)
        offset = 0
      }
      if (size + count > array.size) {
        array = array.copyOf(maxOf(array.size * 2, size + count))
      }
    }

    companion object {

      /**
       * A compressed block contains at most 64KB (plus a small header), and we send `DATA` packets as soon as we have 64KB of data, so we
       * never need more than 128KB (plus a few bytes).
       */
      private const val INITIAL_CAPACITY = 2 * SYNC_DATA_MAX + 1024
    }
  }

  companion object {

    /**
     * The "safe" (i.e. pure Java) LZ4 compressor, so that we don't depend on JNI libraries or on `sun.misc.Unsafe`. It is fast enough to
     * compress at a higher rate than the maximum ADB transfer rate.
     */
    private val lz4Compressor: LZ4Compressor by lazy { LZ4Factory.safeInstance().fastCompressor() }

    /** The hash function used for the header checksum of LZ4 frames, see [lz4Compressor] */
    private val xxHash32: XXHash32 by lazy { XXHashFactory.safeInstance().hash32() }
  }
}
