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

import com.android.adblib.AdbInputChannel
import com.android.adblib.RemoteFileMode
import com.android.adblib.SyncProgress
import com.android.adblib.adbLogger
import com.android.adblib.read
import com.android.adblib.utils.AdbProtocolUtils
import com.android.adblib.withPrefix
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.withContext

/**
 * Implementation of the `SEND` protocol of the `SYNC` command
 *
 * See [https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT]
 */
internal class SyncSendHandler(private val connection: SyncConnection) {

  private val syncRequestId: String = "SEND"

  private val logger = adbLogger(connection.session).withPrefix("device:${connection.device},sync:$syncRequestId - ")

  /**
   * From
   * [SYNC.TXT](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT;l=50)
   *
   * ```
   * SEND:
   * The remote file name is split into two parts separated by the last
   * comma (","). The first part is the actual path, while the second is a decimal
   * encoded file mode containing the permissions of the file on device.
   *
   * Note that some file types will be deleted before the copying starts, and if
   * the transfer fails. Some file types will not be deleted, which allows
   * adb push disk_image /some_block_device to work.
   *
   * After this the actual file is sent in chunks. Each chunk has the following format.
   * A sync request with id "DATA" and length equal to the chunk size. After
   * follows chunk size number of bytes. This is repeated until the file is
   * transferred. Each chunk must not be larger than 64k.
   *
   * When the file is transferred a sync request "DONE" is sent, where length is set
   * to the last modified time for the file. The server responds to this last
   * request (but not to chunk requests) with an "OKAY" sync response (length can
   * be ignored).
   * ```
   *
   * Note: This is not documented in the document above, but a failure is reported as a "FAIL" chunk, length is the error message size,
   * followed by the error message encoded as a UTF-8 string. This can happen if there is an I/O error creating the file on the remote
   * device.
   *
   * If [remoteFileTime] is not provided, it defaults to the current system time.
   */
  suspend fun send(
    sourceChannel: AdbInputChannel,
    remoteFilePath: String,
    remoteFileMode: RemoteFileMode,
    remoteFileTime: FileTime?,
    progress: SyncProgress?,
    bufferSize: Int,
  ) {
    withContext(connection.session.ioDispatcher) {
      // Note: ADB daemon implementation
      //       See
      // [https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/daemon/file_sync_service.cpp;l=498;drc=fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f;bpv=0;bpt=1]
      logger.info { "$sourceChannel -> \"$remoteFilePath\"" }

      if (remoteFilePath.length > REMOTE_PATH_MAX_LENGTH) {
        throw IllegalArgumentException("Remote paths are limited to $REMOTE_PATH_MAX_LENGTH characters")
      }
      val remoteFileEpoch =
        AdbProtocolUtils.convertFileTimeToEpochSeconds(remoteFileTime ?: FileTime.from(connection.session.host.utcNow()))

      // Send the file using the "SEND" query
      startSendRequest(remoteFilePath, remoteFileMode, progress)

      // Send the contents of the file from the input stream
      val byteCount = sendFileContents(remoteFilePath, sourceChannel, bufferSize, progress)

      // Finish the file with the last modification date
      commitRemoteFile(remoteFilePath, remoteFileEpoch, progress, byteCount)

      // The `SEND` operation is acknowledged by either "OKAY" or "FAIL" (in "SYNC" format)
      // TODO: In case of a "FAIL" happening early in the transfer process (e.g. the ADB
      //       daemon could not create the directory for the file), reading "DONE"/"FAIL"
      //       reading should be done before sending the whole file contents to the device,
      //       since that content will essentially be ignored.
      connection.consumeSyncOkayFailResponse("sync-send('$remoteFilePath')")
    }
  }

  private suspend fun startSendRequest(remoteFilePath: String, remoteFileMode: RemoteFileMode, progress: SyncProgress?) {
    progress?.transferStarted(remoteFilePath)
    connection.startSyncRequest(syncRequestId, "${remoteFilePath},${remoteFileMode.modeBits}")
  }

  private suspend fun sendFileContents(
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
        logger.debug { "Done reading bytes from source channel $sourceChannel" }
        break
      }
      connection.sendPreparedDataRequest(byteCount)

      totalBytesSoFar += byteCount
      progress?.transferProgress(remoteFilePath, totalBytesSoFar)
    }

    logger.debug { "Done sending file contents to '$remoteFilePath' ($totalBytesSoFar bytes written)" }
    return totalBytesSoFar
  }

  private suspend fun commitRemoteFile(remoteFilePath: String, remoteFileEpoch: Int, progress: SyncProgress?, byteCount: Long) {
    logger.debug { "Committing remote file $remoteFilePath ($byteCount bytes)" }
    connection.sendDoneRequest(remoteFileEpoch)
    progress?.transferDone(remoteFilePath, byteCount)
  }
}
