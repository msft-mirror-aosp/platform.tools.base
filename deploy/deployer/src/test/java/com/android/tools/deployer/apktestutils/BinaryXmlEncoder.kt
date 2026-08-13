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
package com.android.tools.deployer.apktestutils

import com.android.tools.deployer.apktestutils.ResourceTypes.AndroidAttr
import com.android.tools.deployer.apktestutils.ResourceTypes.ChunkType
import com.android.tools.deployer.apktestutils.ResourceTypes.DataType
import com.android.tools.deployer.apktestutils.ResourceTypes.StructSize
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Representation of an XML attribute for binary serialization. */
data class XmlAttribute(val namespace: String? = null, val name: String, val value: Any?)

/** Representation of an XML element for binary serialization. */
data class XmlElement(
  val name: String,
  val attributes: MutableList<XmlAttribute> = mutableListOf(),
  val children: MutableList<XmlElement> = mutableListOf(),
) {
  fun addAttribute(namespace: String?, name: String, value: Any?): XmlElement {
    attributes.add(XmlAttribute(namespace, name, value))
    return this
  }

  fun addChild(child: XmlElement): XmlElement {
    children.add(child)
    return this
  }

  internal fun collectStrings(pool: IndexedStringPool) {
    pool.getOrAdd(name)
    attributes.forEach { attr ->
      attr.namespace?.let { pool.getOrAdd(it) }
      pool.getOrAdd(attr.name)
      if (attr.value is String) {
        pool.getOrAdd(attr.value)
      }
    }
    children.forEach { it.collectStrings(pool) }
  }

  internal fun encode(out: ByteArrayOutputStream, pool: IndexedStringPool) {
    val startSize = StructSize.XML_NODE_HEADER + StructSize.XML_ELEMENT_ATTR_EXT + (attributes.size * StructSize.XML_ATTRIBUTE)
    val startBuf =
      ByteBuffer.allocate(startSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(ChunkType.RES_XML_START_ELEMENT)
        .putShort(StructSize.XML_NODE_HEADER.toShort())
        .putInt(startSize)
        .putInt(1) // line number
        .putInt(-1) // comment
        .putInt(-1) // ns
        .putInt(pool.indexOf(name))
        .putShort(StructSize.XML_ELEMENT_ATTR_EXT.toShort()) // attribute start
        .putShort(StructSize.XML_ATTRIBUTE.toShort()) // attribute size
        .putShort(attributes.size.toShort())
        .putShort(0.toShort()) // idIndex
        .putShort(0.toShort()) // classIndex
        .putShort(0.toShort()) // styleIndex

    attributes.forEach { attr ->
      val nsIdx = attr.namespace?.let { pool.indexOf(it) } ?: -1
      val nameIdx = pool.indexOf(attr.name)
      startBuf.putInt(nsIdx)
      startBuf.putInt(nameIdx)

      when (val v = attr.value) {
        is Boolean -> {
          startBuf.putInt(-1) // raw value index
          startBuf.putShort(StructSize.RES_VALUE.toShort()) // typed value size
          startBuf.put(0.toByte()) // res0
          startBuf.put(DataType.TYPE_INT_BOOLEAN)
          startBuf.putInt(if (v) -1 else 0) // 0xFFFFFFFF for true
        }
        is Int -> {
          startBuf.putInt(-1)
          startBuf.putShort(StructSize.RES_VALUE.toShort())
          startBuf.put(0.toByte())
          startBuf.put(DataType.TYPE_INT_DEC)
          startBuf.putInt(v)
        }
        else -> {
          val valStr = v?.toString() ?: ""
          val valIdx = pool.indexOf(valStr)
          startBuf.putInt(valIdx)
          startBuf.putShort(StructSize.RES_VALUE.toShort())
          startBuf.put(0.toByte())
          startBuf.put(DataType.TYPE_STRING)
          startBuf.putInt(valIdx)
        }
      }
    }
    out.write(startBuf.array())

    children.forEach { it.encode(out, pool) }

    val endSize = StructSize.XML_NODE_HEADER + StructSize.XML_END_ELEMENT_EXT
    val endBuf =
      ByteBuffer.allocate(endSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(ChunkType.RES_XML_END_ELEMENT)
        .putShort(StructSize.XML_NODE_HEADER.toShort())
        .putInt(endSize)
        .putInt(1)
        .putInt(-1)
        .putInt(-1)
        .putInt(pool.indexOf(name))
        .array()
    out.write(endBuf)
  }
}

/** Sequential unique string pool with UTF-16 binary chunk encoding. */
class IndexedStringPool {
  private val stringList = mutableListOf<String>()
  private val stringMap = mutableMapOf<String, Int>()

  fun getOrAdd(str: String): Int {
    return stringMap.computeIfAbsent(str) {
      val index = stringList.size
      stringList.add(str)
      index
    }
  }

  fun indexOf(str: String): Int {
    return stringMap[str] ?: error("String '$str' not found in string pool")
  }

  fun encode(): ByteArray {
    val stringDataOut = ByteArrayOutputStream()
    val offsets = IntArray(stringList.size)

    for ((index, str) in stringList.withIndex()) {
      offsets[index] = stringDataOut.size()
      val charLen = str.length.toShort()
      val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(charLen).array()
      stringDataOut.write(lenBuf)

      val strBytes = str.toByteArray(StandardCharsets.UTF_16LE)
      stringDataOut.write(strBytes)
      stringDataOut.write(0) // 2-byte null terminator (0x0000)
      stringDataOut.write(0)
    }

    // 4-byte alignment
    while (stringDataOut.size() % 4 != 0) {
      stringDataOut.write(0)
    }

    val stringDataBytes = stringDataOut.toByteArray()
    val offsetsSize = stringList.size * 4
    val stringsStart = StructSize.STRING_POOL_HEADER + offsetsSize
    val chunkSize = stringsStart + stringDataBytes.size

    val headerBuf =
      ByteBuffer.allocate(stringsStart)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(ChunkType.RES_STRING_POOL)
        .putShort(StructSize.STRING_POOL_HEADER.toShort())
        .putInt(chunkSize)
        .putInt(stringList.size) // stringCount
        .putInt(0) // styleCount
        .putInt(0) // flags (UTF-16)
        .putInt(stringsStart) // stringsStart
        .putInt(0) // stylesStart

    offsets.forEach { headerBuf.putInt(it) }

    return headerBuf.array() + stringDataBytes
  }
}

/**
 * Encodes an XML tree into canonical Android Binary XML (AXML) bytes in pure Java/Kotlin.
 *
 * This in-memory encoder is needed because there is no Java AAPT/AAPT2 encoder available for unit tests to compile AndroidManifest.xml
 * files into binary format on the fly.
 */
object BinaryXmlEncoder {

  fun encode(root: XmlElement): ByteArray {
    val stringPool = IndexedStringPool()
    stringPool.getOrAdd(ResourceTypes.ANDROID_PREFIX)
    stringPool.getOrAdd(ResourceTypes.ANDROID_URI)

    // Pre-register attributes in order for ResourceMap mapping
    AndroidAttr.MAPPED_ATTRS.keys.forEach { stringPool.getOrAdd(it) }
    root.collectStrings(stringPool)

    val bodyOut = ByteArrayOutputStream()

    // 1. String Pool Chunk (0x0001)
    bodyOut.write(stringPool.encode())

    // 2. Resource Map Chunk (0x0180)
    val mapSize = StructSize.CHUNK_HEADER + (AndroidAttr.MAPPED_ATTRS.size * 4)
    val mapBuf =
      ByteBuffer.allocate(mapSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(ChunkType.RES_XML_RESOURCE_MAP)
        .putShort(StructSize.CHUNK_HEADER.toShort())
        .putInt(mapSize)
    AndroidAttr.MAPPED_ATTRS.values.forEach { mapBuf.putInt(it) }
    bodyOut.write(mapBuf.array())

    // 3. Start Namespace Chunk (0x0100)
    bodyOut.write(
      encodeNamespace(
        type = ChunkType.RES_XML_START_NAMESPACE,
        prefixIdx = stringPool.indexOf(ResourceTypes.ANDROID_PREFIX),
        uriIdx = stringPool.indexOf(ResourceTypes.ANDROID_URI),
      )
    )

    // 4. Elements (0x0102 Start / 0x0103 End)
    root.encode(bodyOut, stringPool)

    // 5. End Namespace Chunk (0x0101)
    bodyOut.write(
      encodeNamespace(
        type = ChunkType.RES_XML_END_NAMESPACE,
        prefixIdx = stringPool.indexOf(ResourceTypes.ANDROID_PREFIX),
        uriIdx = stringPool.indexOf(ResourceTypes.ANDROID_URI),
      )
    )

    val bodyBytes = bodyOut.toByteArray()
    val totalSize = StructSize.CHUNK_HEADER + bodyBytes.size

    // 6. Root XML Container Chunk (0x0003)
    val rootHeader =
      ByteBuffer.allocate(StructSize.CHUNK_HEADER)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(ChunkType.RES_XML)
        .putShort(StructSize.CHUNK_HEADER.toShort())
        .putInt(totalSize)
        .array()

    return rootHeader + bodyBytes
  }

  private fun encodeNamespace(type: Short, prefixIdx: Int, uriIdx: Int): ByteArray {
    val size = StructSize.XML_NODE_HEADER + StructSize.NAMESPACE_EXT
    return ByteBuffer.allocate(size)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putShort(type)
      .putShort(StructSize.XML_NODE_HEADER.toShort())
      .putInt(size)
      .putInt(1) // line number
      .putInt(-1) // comment index
      .putInt(prefixIdx)
      .putInt(uriIdx)
      .array()
  }
}
