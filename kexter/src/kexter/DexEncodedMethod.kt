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

/** A representation of an encoded method that is present in a class section of a dex file */
interface DexEncodedMethod : DexMethod {
  /**
   * A direct method is invoked without walking the inheritance chain of an object. DEX separate
   * direct and indirect methods.
   */
  val isDirect: Boolean

  /** The bytecode of this method. The content will be an empty list if this method is native. */
  val byteCode: DexBytecode

  /**
   * A native method does not have bytecode [byteCode] returns a DexBytecode with empty list of
   * instructions.
   */
  val isNative: Boolean
}
