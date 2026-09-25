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
package com.android.fakeadbserver

/**
 * A `SEND` or `SND2` request of the `sync` service received by a [DeviceState], as sent on the wire, i.e. before decompressing the file
 * contents (see [DeviceState.syncSendRequests]).
 */
class SyncSendRequest(
  /** The id of the request, i.e. `"SEND"` or `"SND2"` */
  val requestId: String,
  /** The path of the file on the device */
  val path: String,
  /** The file mode (i.e. file type and permissions) */
  val mode: Int,
  /** The `flags` field of a `SND2` request (e.g. `2` for LZ4 compression), always `0` for `SEND` requests */
  val flags: Int,
  /** The size (in bytes) of the payload of each `DATA` packet */
  val dataPacketSizes: List<Int>,
  /** The concatenated payloads of all the `DATA` packets, i.e. the (compressed) file contents */
  val payload: ByteArray,
) {

  override fun toString(): String {
    return "${this::class.simpleName}(requestId=$requestId, path=$path, mode=$mode, flags=$flags, " +
      "dataPacketCount=${dataPacketSizes.size}, payloadSize=${payload.size})"
  }
}
