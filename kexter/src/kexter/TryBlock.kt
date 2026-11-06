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

package kexter

/**
 * A `try_item` of a dex `code_item` (see https://source.android.com/docs/core/runtime/dex-format#code-item).
 *
 * @param startIndex The index (in 16-bit code units) of the first instruction covered by this block.
 * @param instructionCount The number of 16-bit code units covered by this block.
 * @param handlers The handlers of this block, in the order they are tried by the runtime. A catch-all handler, if present, is last.
 */
data class TryBlock(val startIndex: UInt, val instructionCount: UInt, val handlers: List<CatchHandler>) {
  /** The range of instruction indexes covered by this block. */
  val range: UIntRange
    get() = startIndex until startIndex + instructionCount
}

/**
 * An `encoded_type_addr_pair` (or catch-all address) of an `encoded_catch_handler`.
 *
 * @param typeIndex The index into `type_ids` of the caught exception type, or `null` for a catch-all handler.
 * @param handlerIndex The index (in 16-bit code units) of the first instruction of the handler.
 */
data class CatchHandler(val typeIndex: UInt?, val handlerIndex: UInt)
