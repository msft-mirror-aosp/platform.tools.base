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

import kexter.DexBytecode
import kexter.DexMethodDebugInfo
import kexter.Instruction
import kexter.LineTableEntry

internal abstract class BaseDexBytecode(override val bytes: ByteArray, override val debugInfo: DexMethodDebugInfo) : DexBytecode {

  final override fun instructionsForLineNumber(lineNumber: Int): List<Instruction> {
    val lineTable = debugInfo.lineTable
    if (lineTable.isEmpty()) {
      throw IllegalStateException("Unable to extract instruction without DebugInfo")
    }

    val startIndex = lineTable.indexOfFirst { e -> e.lineNumber == lineNumber }

    if (startIndex == -1) {
      throw IllegalStateException("Line $lineNumber not found in TableLine")
    }
    val ranges = lineTable.withIndex().filter { it.value.lineNumber == lineNumber }.map { lineTable.rangeOfEntry(it.index) }

    return instructions.filter { ranges.any { range -> range.contains(it.index) } }
  }
}

private fun List<LineTableEntry>.rangeOfEntry(i: Int): UIntRange {
  val start = get(i).index
  val end = getOrNull(i + 1)?.index ?: UInt.MAX_VALUE
  return start until end
}
