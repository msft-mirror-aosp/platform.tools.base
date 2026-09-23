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

package com.android.tools.ui.inspector.proto

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser

/**
 * Parses a tree response produced by an inspector agent.
 *
 * Tree responses nest one message level per UI hierarchy level, so their depth is unbounded. Protobuf's default recursion limit guards
 * parsers against untrusted input; these payloads come from our own agent, so no depth limit is imposed.
 */
internal fun <T : MessageLite> Parser<T>.parseTreeResponse(payload: ByteArray): T {
  val input = CodedInputStream.newInstance(payload).apply { setRecursionLimit(Int.MAX_VALUE) }
  val response = parseFrom(input)
  try {
    input.checkLastTagWas(0)
  } catch (exception: InvalidProtocolBufferException) {
    throw exception.setUnfinishedMessage(response)
  }
  return response
}
