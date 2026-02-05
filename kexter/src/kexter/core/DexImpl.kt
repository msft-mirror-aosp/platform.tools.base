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

import kexter.Dex
import kexter.DexMethod
import kexter.Logger

internal class DexImpl(header: DexHeader, private val bytes: ByteArray, val logger: Logger) : Dex() {

  fun reader(position: UInt) = DexReader(bytes, position)

  val stringIds: StringIds = StringIds(header.stringIds, this)
  val classDefs: ClassDefs = ClassDefs(header.classDefs, this)
  val methodIds: MethodIds = MethodIds(header.methodsIds, this)
  val protoIds: ProtoIds = ProtoIds(header.protoIds, this)
  val typeIds: TypeIds = TypeIds(header.typeIds, this)

  private val allMethodsCache: MutableMap<UInt, DexMethod> = mutableMapOf()

  override val classes by lazy(LazyThreadSafetyMode.NONE) { retrieveClasses() }

  override fun retrieveMethod(id: UInt): DexMethod? {
    if (id >= methodIds.numElements()) {
      return null
    }
    return allMethodsCache.computeIfAbsent(id) { DexMethodImpl.fromDex(id, this) }
  }

  private fun retrieveClasses(): Map<String, DexClassImpl> {
    val map = mutableMapOf<String, DexClassImpl>()
    for (index in 0u..<classDefs.numElements()) {
      val classDef = classDefs.getClassDef(index)
      val clazz = DexClassImpl(classDef, this)
      map[clazz.name] = clazz
    }
    return map
  }

  fun retrieveParams(protoId: ProtoId): List<String> {
    if (protoId.parameterOffset == 0u) {
      return emptyList()
    }

    val params = mutableListOf<String>()
    val reader = reader(protoId.parameterOffset)
    val size = reader.uint()
    repeat(size.toInt()) {
      val typeIdx = reader.ushort()
      params.add(typeIds.get(typeIdx.toUInt()))
    }
    return params
  }
}

internal fun parseHeaders(bytes: ByteArray): List<DexHeader> {
  val reader = DexReader(bytes, 0u)
  var currentOffset = reader.position
  val result = mutableListOf<DexHeader>()
  while (reader.remaining() > 0u) {
    val header = DexHeader.parse(reader)
    result.add(header)

    // Dex header grows with new features. Keep track of what we may not have parsed.
    val extraBytes = currentOffset + header.headerSize - reader.position
    reader.skip(extraBytes)

    // Jump to an end of file or to a next header if container format is enabled
    reader.skip(header.fileSize - header.headerSize)
    currentOffset = reader.position
  }
  return result
}
