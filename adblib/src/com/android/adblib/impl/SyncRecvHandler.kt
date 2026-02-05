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
package com.android.adblib.impl

import com.android.adblib.AdbOutputChannel
import com.android.adblib.AdbProtocolErrorException
import com.android.adblib.SyncProgress
import com.android.adblib.adbLogger
import com.android.adblib.utils.AdbProtocolUtils
import com.android.adblib.withPrefix
import kotlinx.coroutines.withContext

/**
 * Implementation of the `RECV` protocol of the `SYNC` command
 *
 * See
 * [SYNC.TXT](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT)
 */
internal class SyncRecvHandler(private val connection: SyncConnection) {

  private val syncRequestId: String = "RECV"

  private val logger = adbLogger(connection.session).withPrefix("device:${connection.device},sync:$syncRequestId - ")

  /**
   * See
   * (SYNC.TXT)[https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT]
   *
   * ```
   * RECV:
   * Retrieves a file from device to a local file. The remote path is the path to
   * the file that will be returned. Just as for the SEND sync request the file
   * received is split up into chunks. The sync response id is "DATA" and length is
   * the chunk size. After follows chunk size number of bytes. This is repeated
   * until the file is transferred. Each chunk will not be larger than 64k.
   *
   * When the file is transferred a sync response "DONE" is retrieved where the
   * length can be ignored.
   * ```
   *
   * Note: This is not documented in the document above, but a failure is reported as a "FAIL" chunk, length is the error message size,
   * followed by the error message encoded as a UTF-8 string
   */
  suspend fun recv(remoteFilePath: String, destinationChannel: AdbOutputChannel, progress: SyncProgress?) {
    withContext(connection.session.ioDispatcher) {
      logger.info { "\"$remoteFilePath\" -> $destinationChannel" }

      if (remoteFilePath.length > REMOTE_PATH_MAX_LENGTH) {
        logger.warn("\"$remoteFilePath\": Remote path length is too long ($REMOTE_PATH_MAX_LENGTH)")
        throw IllegalArgumentException("Remote paths are limited to $REMOTE_PATH_MAX_LENGTH characters")
      }

      // Receive the file using the "RECV" query
      startRecvRequest(remoteFilePath, progress)

      // Send the contents of the file from the input stream
      val byteCount = receiveFileContents(remoteFilePath, destinationChannel, progress)

      // Finish the file transfer
      commitDestinationChannel(remoteFilePath, destinationChannel, byteCount, progress)
    }
  }

  private suspend fun startRecvRequest(remoteFilePath: String, progress: SyncProgress?) {
    progress?.transferStarted(remoteFilePath)
    connection.startSyncRequest(syncRequestId, remoteFilePath)
  }

  private suspend fun receiveFileContents(remoteFilePath: String, destinationChannel: AdbOutputChannel, progress: SyncProgress?): Long {
    var totalBytesSoFar = 0L
    while (true) {
      val buffer = connection.readExactly(8)

      // We can receive either 'DATA' or 'DONE' or 'FAIL'
      // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/client/file_sync_client.cpp;l=1102;bpv=1;bpt=1
      when {
        // Bytes 0-3: 'DONE'
        // Bytes 4-7: length (always 0)
        //
        // See
        // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/daemon/file_sync_service.cpp;l=708
        AdbProtocolUtils.isDone(buffer) -> {
          // We reached EOF, we are done
          break
        }

        // Bytes 0-3: 'DATA'
        // Bytes 4-7: request size (little endian)
        // Bytes 8-xx: file bytes
        //
        // See
        // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/daemon/file_sync_service.cpp;l=690
        AdbProtocolUtils.isData(buffer) -> {
          buffer.getInt() // Consume "DATA"
          val chunkLength = buffer.getInt() // Consume length

          // Read chunk from channel
          connection.readExactly(chunkLength).also { chunkBuffer -> destinationChannel.writeExactly(chunkBuffer) }

          totalBytesSoFar += chunkLength
          progress?.transferProgress(remoteFilePath, totalBytesSoFar)
        }

        // Bytes 0-3: 'FAIL'
        // Bytes 4-7: message length (little endian)
        // Bytes 8-xx: message bytes
        //
        // Note: This is not a "regular" `FAIL` message, as the length is a 4 byte little
        //       endian integer, as opposed to 4 hex. ascii characters
        //
        // See
        // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/daemon/file_sync_service.cpp;l=257;drc=fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f
        AdbProtocolUtils.isFail(buffer) -> {
          connection.readSyncFailMessageAndThrow("sync-recv('$remoteFilePath')", buffer)
        }

        else -> {
          val contents = AdbProtocolUtils.bufferToByteDumpString(buffer)
          val errorMessage = "Received an invalid packet from a RECV sync query: $contents"
          throw AdbProtocolErrorException(errorMessage)
        }
      }
    }

    return totalBytesSoFar
  }

  private suspend fun commitDestinationChannel(
    remoteFilePath: String,
    destinationChannel: AdbOutputChannel,
    byteCount: Long,
    progress: SyncProgress?,
  ) {
    destinationChannel.close()
    progress?.transferDone(remoteFilePath, byteCount)
  }
}
