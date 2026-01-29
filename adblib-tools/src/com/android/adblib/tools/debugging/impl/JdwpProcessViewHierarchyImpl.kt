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
package com.android.adblib.tools.debugging.impl

import com.android.adblib.AdbInputChannel
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.JdwpProcessViewHierarchy
import com.android.adblib.tools.debugging.handleDdmsCaptureView
import com.android.adblib.tools.debugging.handleDdmsDumpViewHierarchy
import com.android.adblib.tools.debugging.handleDdmsListViewRoots
import com.android.adblib.tools.debugging.packets.ddms.withPayload

internal class JdwpProcessViewHierarchyImpl(override val process: JdwpProcess) : JdwpProcessViewHierarchy {

  override suspend fun <R> listViewRoots(payloadProcessor: suspend (AdbInputChannel, Int) -> R): R {
    return process.withJdwpSession {
      handleDdmsListViewRoots { chunkReply -> chunkReply.withPayload { payloadProcessor(it, chunkReply.length) } }
    }
  }

  override suspend fun <R> dumpViewHierarchy(
    viewRoot: String,
    skipChildren: Boolean,
    includeProperties: Boolean,
    useV2: Boolean,
    payloadProcessor: suspend (payload: AdbInputChannel, payloadLength: Int) -> R,
  ): R {
    return process.withJdwpSession {
      handleDdmsDumpViewHierarchy(viewRoot = viewRoot, skipChildren = skipChildren, includeProperties = includeProperties, useV2 = useV2) {
        chunkReply ->
        chunkReply.withPayload { payloadProcessor(it, chunkReply.length) }
      }
    }
  }

  override suspend fun <R> captureView(viewRoot: String, view: String, payloadProcessor: suspend (AdbInputChannel, Int) -> R): R {
    return process.withJdwpSession {
      handleDdmsCaptureView(viewRoot, view) { chunkReply -> chunkReply.withPayload { payloadProcessor(it, chunkReply.length) } }
    }
  }
}
