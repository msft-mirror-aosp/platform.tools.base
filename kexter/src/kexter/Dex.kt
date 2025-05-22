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

package kexter

import java.nio.file.Files
import java.nio.file.Path
import kexter.core.DexImpl
import kexter.core.parseHeaders

abstract class Dex {
  abstract val classes: Map<String, DexClass>

  /** Allows to fetch a method by its index from the method table of a dex file. */
  abstract fun retrieveMethod(id: UInt): DexMethod?

  companion object {
    fun fromPath(path: Path, logger: Logger = Logger()): DexContainer {
      val dexBytes = Files.readAllBytes(path)
      return fromBytes(dexBytes, logger)
    }

    fun fromBytes(bytes: ByteArray, logger: Logger = Logger()): DexContainer {
      val headers = parseHeaders(bytes)
      val dexFiles = headers.map { DexImpl(it, bytes, logger) }
      return DexContainer(dexFiles)
    }
  }
}
