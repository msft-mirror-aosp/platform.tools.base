/*
 * Copyright (C) 2024 The Android Open Source Project
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

package kexter.core

import kexter.CatchHandler
import kexter.DexEncodedMethod
import kexter.DexMethodDebugInfo
import kexter.TryBlock
import kotlin.math.absoluteValue

internal class CodeItem(
  val registerSize: UShort,
  val insSize: UShort,
  val outsSize: UShort,
  val triesSize: UShort,
  val debugInfoOffset: UInt,
  val sizeInstructions: UInt,
  val instructions: ByteArray,
) {

  companion object {
    fun from(reader: DexReader): CodeItem {
      val registerSize = reader.ushort()
      val insSize = reader.ushort()
      val outsSize = reader.ushort()
      val triesSize = reader.ushort()
      val debugInfoOffset = reader.uint()
      val sizeInstructions = reader.uint() * UShort.SIZE_BYTES.toUInt()
      val instructions = reader.bytes(sizeInstructions)
      // Tries and handlers are parsed lazily by [readTryBlocks].
      return CodeItem(registerSize, insSize, outsSize, triesSize, debugInfoOffset, sizeInstructions, instructions)
    }

    /**
     * Reads the `tries` and `handlers` sections of a `code_item`.
     *
     * [reader] must be positioned right after the instructions of [codeItem], i.e. where [from] left it.
     */
    fun readTryBlocks(reader: DexReader, codeItem: CodeItem): List<TryBlock> {
      val triesSize = codeItem.triesSize.toInt()
      if (triesSize == 0) {
        return emptyList()
      }
      // Two bytes of padding are present when tries_size is non-zero and insns_size (in 16-bit code units) is odd.
      if ((codeItem.sizeInstructions / UShort.SIZE_BYTES.toUInt()) % 2u == 1u) {
        reader.skip(2u)
      }
      val tries =
        List(triesSize) {
          RawTryItem(startIndex = reader.uint(), instructionCount = reader.ushort().toUInt(), handlerOffset = reader.ushort().toUInt())
        }
      // `handler_off` is relative to the start of the `encoded_catch_handler_list`.
      val handlersStart = reader.position
      return tries.map {
        val handlerReader = reader.copy()
        handlerReader.position = handlersStart + it.handlerOffset
        TryBlock(it.startIndex, it.instructionCount, readCatchHandlers(handlerReader))
      }
    }

    private fun readCatchHandlers(reader: DexReader): List<CatchHandler> {
      // A non-positive size means that a catch-all handler follows the typed handlers.
      val size = reader.sLeb128()
      val typed = List(size.absoluteValue) { CatchHandler(typeIndex = reader.uLeb128(), handlerIndex = reader.uLeb128()) }
      return if (size <= 0) typed + CatchHandler(typeIndex = null, handlerIndex = reader.uLeb128()) else typed
    }
  }
}

private class RawTryItem(val startIndex: UInt, val instructionCount: UInt, val handlerOffset: UInt)

internal class DexEncodedMethodImpl(private val method: EncodedMethod, override val isDirect: Boolean, private val dex: DexImpl) :
  DexEncodedMethod {

  override val name: String by lazy(LazyThreadSafetyMode.NONE) { retrieveName() }

  override val byteCode by lazy(LazyThreadSafetyMode.NONE) { retrieveByteCode() }

  override val type: String by lazy(LazyThreadSafetyMode.NONE) { dex.typeIds.get(methodId.classIndex.toUInt()) }

  override val isNative: Boolean
    get() = byteCode.instructions.size == 0

  override val shorty: String
    get() = dex.stringIds.get(protoId.shortyIndex)

  override val params: List<String> by lazy(LazyThreadSafetyMode.NONE) { dex.retrieveParams(protoId) }

  override val returnType: String
    get() = dex.typeIds.get(protoId.returnTypeIndex)

  private val methodId by lazy(LazyThreadSafetyMode.NONE) { dex.methodIds.get(method.methodIndex) }
  private val protoId by lazy(LazyThreadSafetyMode.NONE) { dex.protoIds.get(methodId.protoIndex) }

  private fun retrieveName(): String {
    return dex.stringIds.get(methodId.nameIndex)
  }

  private fun retrieveByteCode(): DexBytecodeImpl {
    if (method.codeOffset == 0u) {
      // Native method don't have bytecode
      return DexBytecodeImpl(ByteArray(0), emptyList(), DexMethodDebugInfo(), dex.logger)
    }
    val reader = dex.reader(method.codeOffset)
    val codeItem = CodeItem.from(reader)
    val tryBlocks = CodeItem.readTryBlocks(reader, codeItem)
    val bytecode = codeItem.instructions
    val debugInfo =
      if (codeItem.debugInfoOffset == 0u) {
        DexMethodDebugInfo()
      } else {
        reader.position = codeItem.debugInfoOffset
        kexter.core.DexMethodDebugInfo.fromReader(reader, dex.logger)
      }
    return DexBytecodeImpl(bytecode, tryBlocks, debugInfo, dex.logger)
  }
}
