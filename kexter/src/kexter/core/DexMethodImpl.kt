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

import kexter.DexMethod

internal class DexMethodImpl
private constructor(
  override val name: String,
  override val shorty: String,
  override val returnType: String,
  override val params: List<String>,
  override val type: String,
) : DexMethod {
  companion object {
    internal fun fromDex(index: UInt, dex: DexImpl): DexMethodImpl =
      with(dex) {
        val methodId = methodIds.get(index)
        val name = stringIds.get(methodId.nameIndex)
        val type = typeIds.get(methodId.classIndex.toUInt())
        val protoId = protoIds.get(methodId.protoIndex)
        val params = retrieveParams(protoId)
        val shorty = stringIds.get(protoId.shortyIndex)
        val returnType = typeIds.get(protoId.returnTypeIndex)
        return DexMethodImpl(name, shorty, returnType, params, type)
      }
  }
}
