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

package com.android.build.gradle.integration.common.fixture.project.builder.bytecode

/** Helper methods to read types coming from bytecode instructions (via ASM) and convert them into a usable format. */
internal fun String.fromDescriptorToType(): String? {
  if (startsWith('L')) {
    return substring(1, length - 1)
  }

  return null
}

internal fun String.fromSignatureToTypes(): Collection<String> {
  if (!this.startsWith('(')) return emptyList()

  val result = mutableSetOf<String>()

  var index = 1

  while (index < length) {
    if (this[index] != 'L') {
      // skips over other types like IFBV but also )
      index++
    } else {
      // find ;
      val end = nextSemiColon(index)
      result += substring(index, end + 1).fromDescriptorToType()!!
      index = end + 1
    }
  }

  return result.toSet()
}

private fun String.nextSemiColon(startIndex: Int): Int {
  for (i in startIndex..<length) {
    if (this[i] == ';') return i
  }
  return -1
}
