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

import java.io.IOException
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.flow.Flow

/**
 * Sync has a max limit of 64KB for sending/receiving file blocks.
 *
 * In [AdbDeviceSyncServices.send] and [AdbDeviceSyncServices.recv], using buffer of that size for "large" file transfer gives the most
 * optimal throughput.
 */
const val SYNC_DATA_MAX = 64 * 1024

/**
 * Allows transferring files to and from a device, using the protocol documented in
 * [SYNC.TXT](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT)
 *
 * The implementation maintains an open connection to the remote device to allow transferring more than one file, so [close] should be
 * invoked when the file operations are done.
 */
interface AdbDeviceSyncServices : AutoShutdown {

  /**
   * Flushes all internal buffers, then sends a `QUIT` request to this [AdbDeviceSyncServices], effectively terminating the sync session.
   *
   * Note: Calling this method is required to prevent potential data loss when sending data to the device: It initiates an orderly shutdown
   * of the underlying communication socket, allowing for a deterministic acknowledgment that all the data sent to the device has been
   * received.
   */
  override suspend fun shutdown()

  /**
   * Sends the contents of an [AdbInputChannel] to the [remoteFilePath] file on the remote device (`SEND` command).
   *
   * If [remoteFileTime] is not provided, it defaults to the current system time.
   *
   * Note: If the directory for [remoteFilePath] does not exist on the device, an attempt is made to create this directory (and its parent).
   * This may fail for various reasons, in which case an [AdbFailResponseException] is thrown.
   *
   * @throws AdbFailResponseException if the ADB daemon cannot process the file contents
   * @throws AdbProtocolErrorException if there is an unexpected ADB protocol error
   * @throws IOException if there is an I/O error
   */
  suspend fun send(
    sourceChannel: AdbInputChannel,
    remoteFilePath: String,
    remoteFileMode: RemoteFileMode,
    remoteFileTime: FileTime?,
    progress: SyncProgress?,
    bufferSize: Int = SYNC_DATA_MAX,
  )

  /**
   * Sends the contents of an [AdbInputChannel] to the [remoteFilePath] file on the remote device using the `SND2` command of the "sync v2"
   * protocol, which supports compressing the file contents on the fly (see [SendV2Options.compression]).
   *
   * Note: Requires the [AdbFeatures.SENDRECV_V2] feature from [AdbHostServices.availableFeatures] (API 30+), or the implementation falls
   * back to [send] if [SendV2Options.fallbackToSendV1] is `true` (the default). LZ4 compression requires the [AdbFeatures.SENDRECV_V2_LZ4]
   * feature (API 31+).
   *
   * Note: The contents of symbolic links (see [RemoteFileMode.isSymbolicLink]) are never compressed, as the ADB daemon does not support it.
   *
   * If [remoteFileTime] is not provided, it defaults to the current system time.
   *
   * [progress] reports the number of (uncompressed) bytes read from [sourceChannel]. [bufferSize] is the maximum size of the `DATA` packets
   * sent to the device, i.e. it should not exceed [SYNC_DATA_MAX].
   *
   * Note: If the directory for [remoteFilePath] does not exist on the device, an attempt is made to create this directory (and its parent).
   * This may fail for various reasons, in which case an [AdbFailResponseException] is thrown.
   *
   * @throws AdbFailResponseException if the ADB daemon cannot process the file contents
   * @throws AdbProtocolErrorException if there is an unexpected ADB protocol error
   * @throws IOException if there is an I/O error
   */
  suspend fun sendV2(
    sourceChannel: AdbInputChannel,
    remoteFilePath: String,
    remoteFileMode: RemoteFileMode,
    remoteFileTime: FileTime?,
    progress: SyncProgress?,
    bufferSize: Int = SYNC_DATA_MAX,
    options: SendV2Options = defaultSendV2Options,
  )

  /**
   * Retrieves the contents of the [remoteFilePath] file from the remote device to an [AdbOutputChannel] (`RECV` command)
   *
   * @throws AdbFailResponseException if the ADB daemon cannot send the file contents
   * @throws AdbProtocolErrorException if there is an unexpected ADB protocol error
   * @throws IOException if there is an I/O error
   */
  suspend fun recv(remoteFilePath: String, destinationChannel: AdbOutputChannel, progress: SyncProgress?, bufferSize: Int = SYNC_DATA_MAX)

  /**
   * Returns a [FileStat] for a [remoteFilePath] on the remote device (`STAT` sync command)
   *
   * @throws AdbFailResponseException if the ADB daemon cannot stat the file contents
   * @throws AdbProtocolErrorException if there is an unexpected ADB protocol error
   * @throws IOException if there is an I/O error
   */
  suspend fun stat(remoteFilePath: String): FileStat?

  /**
   * Returns a [Flow] of [DirectoryEntry] using the `LIST` command on the [remoteFilePath] directory.
   * * Note: The current implementation of `LIST` returns an empty flow if there is any kind of error opening the directory on the device
   *   (see
   *   [adbd source code](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/daemon/file_sync_service.cpp;l=199))
   * * Note: A new `LIST` command is executed on the device every time the [Flow] is collected.
   * * Note: Due to [bug 434252203](https://issuetracker.google.com/issues?q=434252203), the `LIST` command is not a direct equivalent of
   *   running `shell ls -l`, as some directories are accessible to `ls -l`, but not accessible to `LIST` due to the fact `shell` and `adbd`
   *   permissions are set up differently.
   *
   * This function does not throw any error when invoked, but collecting the returned [Flow] can throw the following exceptions:
   * * [AdbFailResponseException] if the ADB daemon cannot list entries of the directory
   * * [AdbProtocolErrorException] if there is an unexpected ADB protocol error
   * * [java.nio.channels.ClosedChannelException] if [AdbDeviceSyncServices] is closed
   * * [IOException] if there is an I/O error
   */
  fun list(remoteFilePath: String, options: ListOptions = defaultListOptions): Flow<DirectoryEntry>

  /**
   * Stat a file on the remote device ("STA2" command).
   *
   * Note: Requires the [AdbFeatures.STAT_V2] from [AdbHostServices.availableFeatures], or the implementation falls back to [stat] if
   * [StatV2Options.fallbackToStatV1] is `true` (the default)
   *
   * @throws AdbFailResponseException if the ADB daemon cannot stat the file contents
   * @throws AdbProtocolErrorException if there is an unexpected ADB protocol error
   * @throws IOException if there is an I/O error
   */
  suspend fun statV2(remoteFilePath: String, options: StatV2Options = defaultStatV2Options): FileStatV2

  /**
   * Returns a [Flow] of [DirectoryEntryV2] using the `LIS2` command on the [remoteFilePath] directory.
   *
   * Note: Requires the [AdbFeatures.LS_V2] from [AdbHostServices.availableFeatures], or the implementation falls back to [list] if
   * [ListV2Options.fallbackToListV1] is `true` (the default)
   * * Note: The current implementation of returns an empty flow if there is any kind of error opening the directory on the device (see
   *   [adbd source code](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/daemon/file_sync_service.cpp;l=199))
   * * Note: A new `LIS2` command is executed on the device every time the [Flow] is collected.
   * * Note: Due to [bug 434252203](https://issuetracker.google.com/issues?q=434252203), the `LIS2` command is not a direct equivalent of
   *   running `shell ls -l`, as some directories are accessible to `ls -l`, but not accessible to `LIS2` due to the fact `shell` and `adbd`
   *   permissions are set up differently.
   *
   * This function does not throw any error when invoked, but collecting the returned [Flow] can throw the following exceptions:
   * * [AdbFailResponseException] if the ADB daemon cannot list entries of the directory
   * * [AdbProtocolErrorException] if there is an unexpected ADB protocol error
   * * [java.nio.channels.ClosedChannelException] if [AdbDeviceSyncServices] is closed
   * * [IOException] if there is an I/O error
   */
  fun listV2(remoteFilePath: String, options: ListV2Options = defaultListV2Options): Flow<DirectoryEntryV2>

  /** Options of [AdbDeviceSyncServices.list] */
  data class ListOptions(
    /** Skip `".."` and `"."` directory entries when [listing][AdbDeviceSyncServices.list] a directory */
    val skipDotEntries: Boolean = true
  )

  /** Options of [AdbDeviceSyncServices.statV2] */
  data class StatV2Options(
    /** Fallback to executing [AdbDeviceSyncServices.stat] when [AdbDeviceSyncServices.statV2] is not supported (older devices). */
    val fallbackToStatV1: Boolean = true
  )

  /** Options of [AdbDeviceSyncServices.listV2] */
  data class ListV2Options(
    /** Skip `".."` and `"."` directory entries when [listing][AdbDeviceSyncServices.listV2] a directory */
    val skipDotEntries: Boolean = true,
    /** Skip [FileStatV2.isError] directory entries when [listing][AdbDeviceSyncServices.listV2] a directory */
    val skipErrorEntries: Boolean = true,
    /** Fallback to executing [AdbDeviceSyncServices.list] when [AdbDeviceSyncServices.listV2] is not supported (older devices). */
    val fallbackToListV1: Boolean = true,
  )

  /** Options of [AdbDeviceSyncServices.sendV2] */
  data class SendV2Options(
    /** The compression algorithm used to transfer the file contents to the device */
    val compression: SendV2Compression = SendV2Compression.AUTO,
    /**
     * Fallback to executing [AdbDeviceSyncServices.send] when [AdbDeviceSyncServices.sendV2] is not supported (older devices).
     *
     * Note: If this is `false` and the device does not support `SND2` (see [AdbFeatures.SENDRECV_V2]), the ADB daemon rejects the request
     * and closes the sync socket without reading the file contents, so [sendV2] fails with an [AdbFailResponseException] or an
     * [IOException] (for larger files) and the [AdbDeviceSyncServices] session is closed.
     */
    val fallbackToSendV1: Boolean = true,
  )

  /** Compression algorithm used by [AdbDeviceSyncServices.sendV2] to transfer file contents to the device */
  enum class SendV2Compression {
    /**
     * Use the best compression algorithm supported by both the ADB host and the device, i.e. [LZ4] if [AdbFeatures.SENDRECV_V2_LZ4] is
     * available (see [AdbHostServices.availableFeatures]), [NONE] otherwise.
     */
    AUTO,

    /** Transfer file contents without compression */
    NONE,

    /**
     * Compress file contents using the [LZ4 frame format](https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md).
     *
     * Note: This value is used even if [AdbFeatures.SENDRECV_V2_LZ4] is not available (see [AdbHostServices.availableFeatures]). If the
     * device does not support LZ4 compression, the ADB daemon rejects the request and closes the sync socket without reading the file
     * contents, so [sendV2] fails with an [AdbFailResponseException] or an [IOException] (for larger files) and the [AdbDeviceSyncServices]
     * session is closed. Use [AUTO] to select LZ4 only when it is supported.
     */
    LZ4,
  }

  companion object {
    private val defaultListOptions = ListOptions()

    private val defaultStatV2Options = StatV2Options()

    private val defaultListV2Options = ListV2Options()

    private val defaultSendV2Options = SendV2Options()
  }
}

/** Return value of [AdbDeviceSyncServices.stat] */
data class FileStat(
  /** The [RemoteFileMode] of this file system entry */
  val remoteFileMode: RemoteFileMode,
  /** The size (in bytes) of this file system entry */
  val size: Int,
  /** Tne [FileTime] of the last modification of this file system entry */
  val lastModified: FileTime,
)

/** Whether this [FileStat] instance contains valid data or is the result of an error on the device. */
val FileStat.isError: Boolean
  get() {
    // When file is not found `mode`, `size` and `lastModifiedSecs` are all 0
    return (remoteFileMode.modeBits == 0 && size == 0 && lastModified.toMillis() == 0L)
  }

/** Return value of [AdbDeviceSyncServices.statV2] */
data class FileStatV2(
  /** "errno" value when listing file on the device. If the value is non-zero, various other properties may have a "zero" value. */
  val errno: Int,

  /**
   * The [RemoteFileMode] of this file system entry.
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val mode: RemoteFileMode,

  /**
   * The size (in bytes) of this file system entry.
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val size: Long,

  /**
   * Tne [FileTime] of the last modification of this file system entry
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val lastModifiedTime: FileTime,

  /**
   * Tne [FileTime] of the last access of this file system entry
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val lastAccessTime: FileTime,

  /**
   * Tne [FileTime] of the time that the file was created.
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val creationTime: FileTime,

  /**
   * ID of device containing file
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val dev: Long,

  /**
   * Inode number
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val inode: Long,

  /**
   * Number of hard links
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val nlink: Int,

  /**
   * User ID of owner
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val uid: Int,

  /**
   * Group ID of owner
   *
   * Note: The value may be "zero" if [errno]` != 0`.
   */
  val gid: Int,
)

/** Whether this [FileStatV2] instance contains valid data or is the result of an error on the device. */
val FileStatV2.isError: Boolean
  get() = (errno != 0)

/** A directory entry as returned from [AdbDeviceSyncServices.list] */
data class DirectoryEntry(
  /** The file name of this entry in the directory */
  val fileName: String,
  /** The [FileStat] of this entry in the directory */
  val fileStat: FileStat,
)

/** A directory entry as returned from [AdbDeviceSyncServices.listV2] */
data class DirectoryEntryV2(
  /** The file name of this entry in the directory */
  val fileName: String,
  /** The [FileStatV2] of this entry in the directory */
  val fileStat: FileStatV2,
)

/**
 * Reports progress about a single remote file transfer.
 *
 * @see [AdbDeviceSyncServices.send]
 * @see [AdbDeviceSyncServices.recv]
 */
interface SyncProgress {

  /** Invoked just before the file transfer starts */
  suspend fun transferStarted(remotePath: String)

  /** Invoked (multiple times) during the file transfer */
  suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long)

  /** Invoked just after the file transfer has successfully finished */
  suspend fun transferDone(remotePath: String, totalBytes: Long)
}
