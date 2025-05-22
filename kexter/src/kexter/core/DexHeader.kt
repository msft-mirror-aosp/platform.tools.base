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

import java.nio.charset.StandardCharsets

// count can either be bytes or number of elements
data class Span(val count: UInt, val offset: UInt)

internal enum class EndianTag(val value: UInt) {
  ENDIAN_CONSTANT(0x12345678u),
  REVERSE_ENDIAN_CONSTANT(0x78563412u);

  internal companion object {
    fun fromUInt(value: UInt) = entries.first { it.value == value }
  }
}

internal class DexHeader(
  val magic: ByteArray,
  val checksum: UInt,
  val sha1Hash: ByteArray,
  val fileSize: UInt,
  val headerSize: UInt,
  val endianTag: EndianTag,
  val link: Span,
  val mapOffset: UInt,
  val stringIds: Span,
  val typeIds: Span,
  val protoIds: Span,
  val fieldIds: Span,
  val methodsIds: Span,
  val classDefs: Span,
  val data: Span,
) {
  companion object {
    private const val MAGIC_PREFIX = "dex\n" // 0x64 0x65 0x78 0x0a
    private const val MAGIC_SUFFIX = "\u0000"

    fun parse(reader: DexReader): DexHeader {
      val magic = reader.bytes(8u)
      val magicString = String(magic, StandardCharsets.UTF_8)
      if (!magicString.startsWith(MAGIC_PREFIX) || !magicString.endsWith(MAGIC_SUFFIX)) {
        throw IllegalStateException("Bad dex magic number ('${magic.toHexString()}')!")
      }

      // TODO Allow checksum
      val checksum = reader.uint()
      // TODO Allow sha1 checksum
      val sha1Hash = reader.bytes(20u)
      val fileSize = reader.uint()
      val headerSize = reader.uint()
      val endianTag = EndianTag.fromUInt(reader.uint())
      val link = reader.span()
      val mapOffset = reader.uint()
      val stringIds = reader.span()
      val typeIds = reader.span()
      val protoIds = reader.span()
      val fieldIds = reader.span()
      val methodsIds = reader.span()
      val classDefs = reader.span()
      val data = reader.span()

      return DexHeader(
        magic,
        checksum,
        sha1Hash,
        fileSize,
        headerSize,
        endianTag,
        link,
        mapOffset,
        stringIds,
        typeIds,
        protoIds,
        fieldIds,
        methodsIds,
        classDefs,
        data,
      )
    }
  }

  override fun toString(): String {
    return """
			Magic      : '${magic.toHexString()}'
			Checksum   : $checksum
			Sha1 hash  : ${sha1Hash.toSha1String()}
			File size  : ${fileSize.nice()}
			Header size: ${headerSize.nice()}
			Endian Tag : $endianTag
			Link       : $link
			Map offset : $mapOffset
			String ids : $stringIds
			Type ids   : $typeIds
			Proto ids  : $protoIds
			Field ids  : $fieldIds
			Method ids : $methodsIds
			Class defs : $classDefs
			Data       : $data
		"""
      .trimIndent()
  }
}

private fun ByteArray.toHexString() = joinToString(",") { "0x%02x".format(it) }

private fun ByteArray.toSha1String() = joinToString("") { "%02x".format(it) }

private fun UInt.nice() = "%,d".format(this.toInt())
