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

import kexter.CatchHandler
import kexter.DexEncodedMethod
import kexter.InvokeInstruction
import kexter.Opcode
import kexter.TryBlock
import kexter.core.DexImpl
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val CLASS = "LtryBlocks/TryBlocksKt;"
private const val ILLEGAL_STATE = "Ljava/lang/IllegalStateException;"
private const val ILLEGAL_ARGUMENT = "Ljava/lang/IllegalArgumentException;"

/** Tests for [kexter.DexBytecode.tryBlocks]. Test data is in `test/resources/main/src/TryBlocks.kt`. */
@RunWith(Parameterized::class)
class TryBlocksTest(archive: DexArchiveBase) : MultipleDexVersionTestBase(archive) {

  @Test
  fun testNoTryBlocks() {
    Assert.assertEquals(emptyList<TryBlock>(), method("noTry(I)").byteCode.tryBlocks)
  }

  @Test
  fun testSingleCatch() {
    val method = method("singleCatch(L)")
    Assert.assertEquals(1, method.byteCode.tryBlocks.size)
    Assert.assertEquals(listOf(ILLEGAL_STATE), method.tryBlockCoveringMayThrow().handlerTypes())
    assertHandlersStartWithMoveException(method)
  }

  @Test
  fun testMultipleCatchesKeepOrder() {
    val method = method("multipleCatches(L)")
    Assert.assertEquals(listOf(ILLEGAL_STATE, ILLEGAL_ARGUMENT), method.tryBlockCoveringMayThrow().handlerTypes())
    assertHandlersStartWithMoveException(method)
  }

  @Test
  fun testCatchAllOnly() {
    val method = method("catchAll(I)")
    // A `finally` block is a catch-all handler, encoded with a non-positive handler count.
    Assert.assertEquals(listOf<String?>(null), method.tryBlockCoveringMayThrow().handlerTypes())
    assertHandlersStartWithMoveException(method)
  }

  @Test
  fun testCatchAllIsLast() {
    val method = method("catchWithFinally(L)")
    Assert.assertEquals(listOf(ILLEGAL_STATE, null), method.tryBlockCoveringMayThrow().handlerTypes())
    assertHandlersStartWithMoveException(method)
  }

  @Test
  fun testNestedTry() {
    val method = method("nestedTry(L)")
    // The innermost call can be caught by both the inner and the outer handler, innermost first.
    Assert.assertEquals(listOf(ILLEGAL_STATE, ILLEGAL_ARGUMENT), method.tryBlockCoveringMayThrow().handlerTypes())
    // The inner handler itself is only covered by the outer handler.
    Assert.assertTrue(method.byteCode.tryBlocks.any { it.handlerTypes() == listOf(ILLEGAL_ARGUMENT) })
    assertHandlersStartWithMoveException(method)
  }

  /**
   * Checks every method of the archive, so that methods with an odd number of code units (where the tries are preceded by two bytes of
   * padding) and methods with an even number of code units are both covered.
   */
  @Test
  fun testTryBlocksAndHandlersStartAtInstructions() {
    var methodsWithTries = 0
    var oddSize = 0
    var evenSize = 0
    for (dex in archive.container.dexFiles) {
      for (clazz in dex.classes.values) {
        for (method in clazz.methods.values) {
          val tryBlocks = method.byteCode.tryBlocks
          if (tryBlocks.isEmpty()) continue
          methodsWithTries++
          val instructions = method.byteCode.instructions
          val starts = instructions.map { it.index }.toSet()
          val codeUnits = method.byteCode.bytes.size / 2
          if (codeUnits % 2 == 1) oddSize++ else evenSize++
          val name = "${clazz.name}.${method.name}"
          for (tryBlock in tryBlocks) {
            Assert.assertTrue("$name: try block $tryBlock does not start at an instruction", tryBlock.startIndex in starts)
            Assert.assertTrue("$name: try block $tryBlock is empty", tryBlock.instructionCount > 0u)
            Assert.assertTrue("$name: try block $tryBlock ends after the code", tryBlock.range.last < codeUnits.toUInt())
            Assert.assertTrue("$name: try block $tryBlock has no handlers", tryBlock.handlers.isNotEmpty())
            for (handler in tryBlock.handlers) {
              Assert.assertTrue("$name: handler $handler does not start at an instruction", handler.handlerIndex in starts)
            }
            Assert.assertTrue("$name: catch-all handler is not last", tryBlock.handlers.dropLast(1).none { it.typeIndex == null })
          }
        }
      }
    }
    Assert.assertTrue("Expected methods with try blocks", methodsWithTries > 0)
    Assert.assertTrue("Expected a method with an odd number of code units", oddSize > 0)
    Assert.assertTrue("Expected a method with an even number of code units", evenSize > 0)
  }

  private fun method(key: String): DexEncodedMethod {
    val clazz = archive.retrieveClass(CLASS)
    return clazz.methods[key] ?: throw IllegalStateException("Unable to find $key in $CLASS. Found:\n ${clazz.methods.keys}")
  }

  /** Returns the only try block covering the call to `mayThrow()` in this method. */
  private fun DexEncodedMethod.tryBlockCoveringMayThrow(): TryBlock {
    val dex = archive.getDex(CLASS)
    val calls =
      byteCode.instructions.filterIsInstance<InvokeInstruction>().filter { dex.retrieveMethod(it.methodIndex())?.name == "mayThrow" }
    val tryBlocks = byteCode.tryBlocks
    val covering = tryBlocks.filter { block -> calls.any { it.index in block.range } }
    Assert.assertEquals("Expected exactly one try block covering a call to mayThrow() in $name, got $tryBlocks", 1, covering.size)
    return covering.single()
  }

  private fun TryBlock.handlerTypes(): List<String?> = handlers.map { it.typeName() }

  private fun CatchHandler.typeName(): String? = typeIndex?.let { (archive.getDex(CLASS) as DexImpl).typeIds.get(it) }

  private fun assertHandlersStartWithMoveException(method: DexEncodedMethod) {
    val opcodes = method.byteCode.instructions.associate { it.index to it.opcode }
    for (handler in method.byteCode.tryBlocks.flatMap { it.handlers }) {
      Assert.assertEquals("Handler $handler of ${method.name}", Opcode.MOVE_EXCEPTION, opcodes[handler.handlerIndex])
    }
  }
}
