/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.fakeadbserver.devicecommandhandlers

import com.android.fakeadbserver.DeviceFileState
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.FakeAdbServer
import com.android.fakeadbserver.shutdownGracefully
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.Integer.min
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.coroutines.CoroutineScope

/** Implementation of the '`sync` protocol, using [DeviceFileState] as the backing "file system" */
class SyncCommandHandler : DeviceCommandHandler("sync") {

  override fun invoke(server: FakeAdbServer, socketScope: CoroutineScope, socket: Socket, device: DeviceState, args: String) {
    val output = socket.getOutputStream()
    val input = socket.getInputStream()

    if (device.acceptsSyncServiceRequests) {
      // Tell client we accepted the `sync` service request
      writeOkay(output)
    } else {
      writeFailResponse(output, "fake device is not accepting sync requests")
      return
    }

    //
    // Handle the various "SEND", "RECV", etc. requests
    while (true) {
      // Sync protocol:
      // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT
      // Bytes 0-3: 'SEND', 'RECV', 'LIST', etc.
      when (val syncRequest = readSyncRequest(input)) {
        "SEND" -> handleSendProtocol(device, input, output)
        "RECV" -> handleRecvProtocol(device, input, output)
        "STAT" -> handleStatProtocol(device, input, output)
        "LIST" -> handleListProtocol(device, input, output)

        "STA2" -> {
          if (!device.features.contains("stat_v2")) {
            closeSocketWithUnknownCommand(socket, syncRequest)
            break
          }
          handleStatV2Protocol(device, input, output)
        }
        "LIS2" -> {
          if (!device.features.contains("ls_v2")) {
            closeSocketWithUnknownCommand(socket, syncRequest)
            break
          }
          handleListV2Protocol(device, input, output)
        }

        "QUIT" -> {
          handleQuitProtocol(input, socket)
          break
        }
        else -> {
          closeSocketWithUnknownCommand(socket, syncRequest)
          break
        }
      }
    }
  }

  private fun closeSocketWithUnknownCommand(socket: Socket, syncRequest: String) {
    writeUnsupportedSyncRequest(socket.outputStream, syncRequest)
    socket.shutdownGracefully()
  }

  /** Data is sent by the peer using 'DATA' and 'DONE' packets */
  private fun handleSendProtocol(device: DeviceState, input: InputStream, output: OutputStream) {
    val (path, permission) = readSendHeader(input, output)
    val bytePackets = ArrayList<ByteArray>()
    while (true) {
      // Bytes 0-3: 'DATA' or 'DONE'
      when (val sendRequest = readSyncRequest(input)) {
        "DATA" -> {
          val bytes = readDataPacket(input)
          bytePackets.add(bytes)
        }
        "DONE" -> {
          val modifiedDate = readDonePacket(input)
          val bytes = bytePackets.flatMap { it.asIterable() }.toByteArray()
          if (path.isEmpty()) {
            sendSyncFail(output, "'$path' not a directory")
            return
          }
          val file = DeviceFileState(path, permission, modifiedDate, bytes)
          device.createFile(file)
          sendSyncOkay(output)
          break
        }
        else -> {
          writeUnsupportedSyncRequest(output, sendRequest)
          break
        }
      }
    }
  }

  /** Response is sent to the peer using 'DATA', 'DONE' or 'FAIL' packets */
  private fun handleRecvProtocol(device: DeviceState, input: InputStream, output: OutputStream) {
    val path = readRecvHeader(input)
    val fileState = device.getFile(path)
    if (fileState == null) {
      val reason = "File does not exist: '$path'"
      sendSyncFail(output, reason)
      return // We do not throw, as we can accept another sync request
    }
    if (!fileState.isOwnerReadable()) {
      sendSyncFail(output, "File is not readable: '$path'")
      return
    }
    val bytesToSend = fileState.bytes
    var offset = 0
    var remainingCount = bytesToSend.size
    while (remainingCount > 0) {
      val count = min(remainingCount, 4_096)
      sendSyncData(output, bytesToSend, offset, count)
      offset += count
      remainingCount -= count
    }
    sendSyncDone(output)
  }

  /** Response is a "STAT" entry */
  private fun handleStatProtocol(device: DeviceState, input: InputStream, output: OutputStream) {
    val path = readRecvHeader(input)
    val fileState = device.getFile(path)
    writeStatEntry(output, id = "STAT", fileState = fileState)
  }

  /** Response is a sequence of "DENT" entries ("STAT" + filename) followed by a "DONE" entry */
  private fun handleListProtocol(device: DeviceState, input: InputStream, output: OutputStream) {
    val path = readRecvHeader(input)
    val fileStates = device.getDirectoryFiles(path)
    fileStates.forEach { fileState -> writeDentEntry(output, id = "DENT", fileState = fileState) }
    writeDentEntry(output, id = "DONE", fileState = null)
  }

  /** Response is a "STA2" entry */
  private fun handleStatV2Protocol(device: DeviceState, input: InputStream, output: OutputStream) {
    val path = readRecvHeader(input)
    val fileState = device.getFile(path)
    writeStatV2Entry(output, id = "STA2", fileState = fileState)
  }

  /** Response is a series of "DNT2" entries following by a "DONE" entry */
  private fun handleListV2Protocol(device: DeviceState, input: InputStream, output: OutputStream) {
    val path = readRecvHeader(input)
    val fileStates = device.getDirectoryFiles(path)
    fileStates.forEach { fileState -> writeDentV2Entry(output, id = "DNT2", fileState = fileState) }
    writeDentV2Entry(output, id = "DONE", fileState = null)
  }

  private fun handleQuitProtocol(input: InputStream, socket: Socket) {
    // QUIT command is followed by an int32 value of zero
    val value = readInt32(input)
    assert(value == 0)
    socket.shutdownOutput()
  }

  private val DeviceFileState.fileName: String
    get() = path.substringAfterLast("/")

  private fun writeStatEntry(output: OutputStream, id: String, fileState: DeviceFileState?) {
    // 1. A four-byte sync response e.g. "STAT"
    // 2. A four-byte integer representing file mode.
    // 3. A four-byte integer representing file size.
    // 4. A four-byte integer representing last modified time.
    writeEntryId(output, id) // 1
    if (fileState == null) {
      writeInt32(output, 0) // file mode = 0
      writeInt32(output, 0) // file size = 0
      writeInt32(output, 0) // last modified time = 0
    } else {
      writeInt32(output, fileState.permission) // 2
      writeInt32(output, fileState.bytes.size) // 3
      writeInt32(output, fileState.modifiedDate) // 4
    }
  }

  private fun writeDentEntry(output: OutputStream, id: String, fileState: DeviceFileState?) {
    // 1. A four-byte sync response e.g. "DENT"
    // 2. A four-byte integer representing file mode.
    // 3. A four-byte integer representing file size.
    // 4. A four-byte integer representing last modified time.
    // 5. A four-byte integer representing file name length.
    // 6. length number of bytes containing an utf-8 string representing the file name.
    writeStatEntry(output, id, fileState)
    writeLengthPrefixedFileName(output, fileState)
  }

  private fun writeStatV2Entry(output: OutputStream, id: String, fileState: DeviceFileState?) {
    //     struct __attribute__((packed)) sync_dent_v2 {
    //  0    uint32_t id;     /* "STA2" */
    //  4    uint32_t error;  /* Linux `errno` of the `stat` call */
    //  8    uint64_t dev;    /* ID of device containing file */
    // 16    uint64_t ino;    /* Inode number */
    // 24    uint32_t mode;   /* File type and mode */
    // 28    uint32_t nlink;  /* Number of hard links */
    // 32    uint32_t uid;    /* User ID of owner */
    // 36    uint32_t gid;    /* Group ID of owner */
    // 40    uint64_t size;   /* Total size, in bytes */
    // 48    int64_t atime;   /* Time of last access */
    // 56    int64_t mtime;   /* Time of last modification */
    // 64    int64_t ctime;   /* Time of last status change */
    // 72   };
    writeEntryId(output, id)
    if (fileState == null) {
      writeInt32(output, 2) // error = "ENOENT"
      writeInt64(output, 0) // dev
      writeInt64From32(output, 0) // inode
      writeInt32(output, 0) // mode
      writeInt32(output, 0) // nlink
      writeInt32(output, 0) // uid
      writeInt32(output, 0) // gid
      writeInt64From32(output, 0) // size
      writeInt64From32(output, 0) // atime
      writeInt64From32(output, 0) // mtime
      writeInt64From32(output, 0) // ctime
    } else {
      writeInt32(output, 0) // error
      writeInt64(output, fileState.dev) // dev
      writeInt64(output, fileState.inode) // inode
      writeInt32(output, fileState.permission) // mode
      writeInt32(output, fileState.nlink) // nlink
      writeInt32(output, fileState.uid) // uid
      writeInt32(output, fileState.gid) // gid
      writeInt64From32(output, fileState.bytes.size) // size
      writeTimeSpec64(output, fileState.modifiedDate) // atime
      writeTimeSpec64(output, fileState.modifiedDate) // mtime
      writeTimeSpec64(output, fileState.modifiedDate) // ctime
    }
  }

  private fun writeDentV2Entry(output: OutputStream, id: String, fileState: DeviceFileState?) {
    writeStatV2Entry(output, id, fileState)
    writeLengthPrefixedFileName(output, fileState)
  }

  private fun writeLengthPrefixedFileName(output: OutputStream, fileState: DeviceFileState?) {
    if (fileState == null) {
      writeInt32(output, 0)
    } else {
      val filePathBytes = fileState.fileName.toByteArray(UTF_8)
      writeInt32(output, filePathBytes.size)
      output.write(filePathBytes)
    }
  }

  private fun readSyncRequest(input: InputStream): String {
    val bytes = readExactly(input, 4)
    return String(bytes, UTF_8)
  }

  private data class SendHeader(val path: String, val permissions: Int)

  private fun readSendHeader(input: InputStream, output: OutputStream): SendHeader {
    // Bytes 0-3: 'SEND'
    // Bytes 4-7: request size (little endian)
    // Bytes 8-xx: An utf-8 string with the remote file path followed by ',' followed by the
    // permissions as a string
    val length = readLength(input)
    val bytes = readExactly(input, length)
    val header = String(bytes, UTF_8)
    val index = header.indexOf(',')
    if (index < 0 || index == header.length - 1) {
      val errorMessage = "Send protocol error: path and/or permissions missing"
      sendSyncFail(output, errorMessage)
      throw IOException(errorMessage)
    }
    return SendHeader(header.substring(0, index), header.substring(index + 1, header.length).toInt())
  }

  private fun readRecvHeader(input: InputStream): String {
    // Bytes 0-3: 'RECV'
    // Bytes 4-7: request size (little endian)
    // Bytes 8-xx: An utf-8 string with the remote file path
    val length = readLength(input)
    val bytes = readExactly(input, length)
    return String(bytes, UTF_8)
  }

  private fun readDataPacket(input: InputStream): ByteArray {
    // Bytes 0-3: 'DATA'
    // Bytes 4-7: request size (little endian)
    // Bytes 8-xx: file bytes
    val length = readLength(input)
    return readExactly(input, length)
  }

  private fun readDonePacket(input: InputStream): Int {
    // Bytes 0-3: 'DONE'
    // Bytes 4-7: modified date (in seconds)
    return readInt32(input)
  }

  private fun readLength(input: InputStream): Int {
    return readInt32(input)
  }

  private fun sendSyncOkay(output: OutputStream) {
    writeOkay(output)
    writeInt32(output, 0)
  }

  private fun sendSyncFail(output: OutputStream, reason: String) {
    writeFail(output)
    writeInt32(output, reason.length)
    writeString(output, reason)
  }

  private fun sendSyncDone(output: OutputStream) {
    writeDone(output)
    writeInt32(output, 0)
  }

  private fun sendSyncData(output: OutputStream, bytes: ByteArray, offset: Int, count: Int) {
    writeData(output)
    writeInt32(output, count)
    output.write(bytes, offset, count)
  }

  private fun writeDone(output: OutputStream) {
    writeEntryId(output, "DONE")
  }

  private fun writeData(output: OutputStream) {
    writeEntryId(output, "DATA")
  }

  private fun writeEntryId(output: OutputStream, id: String) {
    assert(id.length == 4)
    output.write(id.toByteArray(UTF_8))
  }

  private fun readInt32(input: InputStream): Int {
    // Note: This could be way more efficient, but fake adb is for testing only
    val bytes = readExactly(input, 4)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
  }

  private fun writeInt32(output: OutputStream, value: Int) {
    // Note: This could be way more efficient, but fake adb is for testing only
    val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    output.write(bytes)
  }

  private fun writeInt64From32(output: OutputStream, value: Int) {
    writeInt64(output, value.toLong())
  }

  private fun writeInt64(output: OutputStream, value: Long) {
    // Note: This could be way more efficient, but fake adb is for testing only
    val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    output.write(bytes)
  }

  private fun writeTimeSpec64(output: OutputStream, value: Int) {
    writeInt32(output, value) // seconds
    writeInt32(output, 0) // nanos
  }

  private fun writeUnsupportedSyncRequest(output: OutputStream, syncRequest: String) {
    // See
    // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/daemon/file_sync_service.cpp;l=845
    val message = "unknown command $syncRequest"
    sendSyncFail(output, message)
  }

  private fun readExactly(input: InputStream, len: Int): ByteArray {
    val buffer = ByteArray(len)
    if (len == 0) {
      return buffer
    }
    var pos = 0
    while (pos < len) {
      val byteCount = input.read(buffer, pos, len - pos)
      if (byteCount < 0) {
        throw EOFException("Unexpected EOF")
      }
      if (byteCount == 0) {
        throw IOException("Unexpected stream implementation")
      }
      pos += byteCount
    }
    return buffer
  }
}
