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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a minimal `resources.arsc` holding boolean resources in the default configuration, enough for manifest attributes such as
 * `android:enabled="@bool/foo"` to be resolved from a test APK. All IDs must share one package and type (e.g. `0x7f05xxxx`).
 */
fun boolResourceTable(values: Map<Int, Boolean>): ByteArray {
  require(values.isNotEmpty())
  val packageId = values.keys.first() ushr 24
  val typeId = (values.keys.first() ushr 16) and 0xff
  require(values.keys.all { it ushr 24 == packageId && (it ushr 16) and 0xff == typeId }) { "All IDs must share a package and type" }

  // ResTable_type with dense 32-bit offsets; each entry is a ResTable_entry followed by a Res_value.
  val entryCount = values.keys.maxOf { it and 0xffff } + 1
  val configSize = 64
  val typeHeaderSize = 8 + 12 + configSize
  val entriesStart = typeHeaderSize + entryCount * 4
  val entrySize = 16
  val type = le(entriesStart + values.size * entrySize)
  type.putShort(0x0201).putShort(typeHeaderSize.toShort()).putInt(type.capacity())
  type.put(typeId.toByte()).put(0).putShort(0).putInt(entryCount).putInt(entriesStart)
  type.putInt(configSize).put(ByteArray(configSize - 4))
  var nextOffset = 0
  val sorted = values.toSortedMap { a, b -> (a and 0xffff) - (b and 0xffff) }
  for (index in 0 until entryCount) {
    val present = sorted.keys.any { it and 0xffff == index }
    type.putInt(if (present) nextOffset else -1)
    if (present) nextOffset += entrySize
  }
  for ((_, value) in sorted) {
    type.putShort(8).putShort(0).putInt(0) // ResTable_entry: size, flags, key
    type.putShort(8).put(0).put(0x12).putInt(if (value) -1 else 0) // Res_value: TYPE_INT_BOOLEAN
  }

  val packageHeaderSize = 8 + 4 + 256 + 5 * 4
  val pkg = le(packageHeaderSize + type.capacity())
  pkg.putShort(0x0200).putShort(packageHeaderSize.toShort()).putInt(pkg.capacity()).putInt(packageId)
  pkg.position(packageHeaderSize)
  pkg.put(type.array())

  val stringPool = le(28).putShort(0x0001).putShort(28).putInt(28).array()
  val table = le(12 + stringPool.size + pkg.capacity())
  table.putShort(0x0002).putShort(12).putInt(table.capacity()).putInt(1)
  table.put(stringPool).put(pkg.array())
  return table.array()
}

private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
