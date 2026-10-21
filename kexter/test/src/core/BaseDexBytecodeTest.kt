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
package core

import com.google.common.truth.Truth.assertThat
import kexter.DexBytecode
import kexter.DexMethodDebugInfo
import kexter.InstructionImpl
import kexter.LineTableEntry
import kexter.Opcode
import kexter.core.testing.FakeDexBytecode
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BaseDexBytecodeTest {

  @Test
  fun lineWithNonContiguousBytecodeRegions() {
    // This bytecode is not intended to be valid. It simulates a line (line 3) that is
    // split into multiple code regions including a region at the end of the code.
    val code =
      """
      3:  0000: move-object v0, v5
          0001: check-cast v0, Ljava/lang/Iterable; // type@0006
      4:  0006: move-result-object v0
          0007: check-cast v0, Ljava/lang/Integer; // type@0005
      3:  000b: invoke-virtual {v0}, Ljava/lang/Integer;.intValue:()I // method@0001
          000e: move-result v0
      5:  000f: move-object v1, v5
          0010: check-cast v1, Ljava/lang/Iterable; // type@0006
      3:  0015: move-result-object v1
          0016: check-cast v1, Ljava/lang/Number; // type@0007
      6:  001c: new-instance v2, Lkotlin/Pair; // type@0010
          001e: invoke-static {v0}, Ljava/lang/Integer;.valueOf:(I)Ljava/lang/Integer; // method@0002
      3:  002a: const/4 v0, #int 0 // #0
          002b: return-object v0
      """
        .trimIndent()
    val bytecode = createDexBytecode(code)

    val instructions = bytecode.instructionsForLineNumber(3).joinToString("\n")

    assertThat(instructions)
      .isEqualTo(
        """
        0000: move-object
        0001: check-cast
        000b: invoke-virtual
        000e: move-result
        0015: move-result-object
        0016: check-cast
        002a: const/4
        002b: return-object
        """
          .trimIndent()
      )
  }
}

private val CODE_LINE_REGEX = Regex("""^\s*(?:(?<line>\d+):)?\s*(?<index>[0-9a-fA-F]+):\s*(?<opcode>\S+).*$""")

private data class CodeLine(val line: Int?, val index: UInt, val opcode: Opcode) {
  companion object {
    fun parse(line: String): CodeLine {
      val match = CODE_LINE_REGEX.matchEntire(line) ?: throw IllegalArgumentException("Invalid line: '$line'")
      val values = match.groups
      val line = values["line"]?.value?.toInt()
      val index = values["index"]!!.value.toUInt(16)
      val opcodeName = values["opcode"]!!.value
      val opcode = Opcode.entries.find { it.displayName == opcodeName } ?: throw IllegalArgumentException("Unknown opcode: '$opcodeName'")
      return CodeLine(line, index, opcode)
    }
  }
}

private fun createDexBytecode(code: String): DexBytecode {
  val lines = code.lines().map { CodeLine.parse(it) }
  val instructions = lines.map { InstructionImpl(it.opcode, it.index, ByteArray(0)) }
  val lineTable = lines.mapNotNull {
    LineTableEntry(it.index, it.line ?: return@mapNotNull null)
  }
  return FakeDexBytecode(instructions, DexMethodDebugInfo(lineTable))
}
