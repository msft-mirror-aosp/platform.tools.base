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

import kexter.InvokeInstruction
import kexter.Opcode
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class InvokeInstructionsTest(archive: DexArchiveBase) : MultipleDexVersionTestBase(archive) {
  @Test
  fun testInvokeStatic() {
    testTopLevelFunction(
      "testInvokeStatic(V)",
      Opcode.INVOKE_STATIC,
      MethodInfo(
        name = "foo",
        owner = "LinvokeInstructions/InvokeInstructionsKt;",
        signature = "()V",
      ),
    )
  }

  @Test
  fun testInvokeStaticRange() {
    testTopLevelFunction(
      "testInvokeStaticRange(V)",
      Opcode.INVOKE_STATIC_RANGE,
      MethodInfo(
        name = "fooRange",
        owner = "LinvokeInstructions/InvokeInstructionsKt;",
        signature = "(IIIIII)V",
      ),
    )
  }

  @Test
  fun testInvokeVirtual() {
    testTopLevelFunction(
      "testInvokeVirtual(VL)",
      Opcode.INVOKE_VIRTUAL,
      MethodInfo(name = "i", owner = "LinvokeInstructions/X;", signature = "()V"),
    )
  }

  @Test
  fun testInvokeVirtualRange() {
    testTopLevelFunction(
      "testInvokeVirtualRange(VL)",
      Opcode.INVOKE_VIRTUAL_RANGE,
      MethodInfo(name = "iRange", owner = "LinvokeInstructions/X;", signature = "(IIIII)V"),
    )
  }

  @Test
  fun testInvokeDirect() {
    testTopLevelFunction(
      "testInvokeDirect(V)",
      Opcode.INVOKE_DIRECT,
      MethodInfo(name = "<init>", owner = "LinvokeInstructions/X;", signature = "()V"),
    )
  }

  @Test
  fun testInvokeDirectRange() {
    testTopLevelFunction(
      "testInvokeDirectRange(V)",
      Opcode.INVOKE_DIRECT_RANGE,
      MethodInfo(name = "<init>", owner = "LinvokeInstructions/Y;", signature = "(IIIII)V"),
    )
  }

  @Test
  fun testInvokeInterface() {
    testTopLevelFunction(
      "testInvokeInterface(VL)",
      Opcode.INVOKE_INTERFACE,
      MethodInfo(name = "i", owner = "LinvokeInstructions/I;", signature = "()V"),
    )
  }

  @Test
  fun testInvokeInterfaceRange() {
    testTopLevelFunction(
      "testInvokeInterfaceRange(VL)",
      Opcode.INVOKE_INTERFACE_RANGE,
      MethodInfo(name = "iRange", owner = "LinvokeInstructions/I;", signature = "(IIIII)V"),
    )
  }

  @Test
  fun testInvokePolymorphic() {
    testTopLevelFunction(
      "testInvokePolymorphic(VL)",
      Opcode.INVOKE_POLYMORPHIC,
      MethodInfo(
        name = "invoke",
        owner = "Ljava/lang/invoke/MethodHandle;",
        signature = "([Ljava/lang/Object;)Ljava/lang/Object;",
      ),
    )
  }

  @Test
  fun testInvokeSuper() {
    test(
      "LinvokeInstructions/X;",
      "base(V)",
      Opcode.INVOKE_SUPER,
      MethodInfo(name = "base", owner = "LinvokeInstructions/Base;", signature = "()V"),
    )
  }

  @Test
  fun testInvokeSuperRange() {
    test(
      "LinvokeInstructions/X;",
      "baseRange(VIIIII)",
      Opcode.INVOKE_SUPER_RANGE,
      MethodInfo(name = "baseRange", owner = "LinvokeInstructions/Base;", signature = "(IIIII)V"),
    )
  }

  private fun test(
    className: String,
    methodName: String,
    expectedOpcode: Opcode,
    expectedInfo: MethodInfo,
  ) {
    val dex = archive.getDex(className)
    val fetchedInfos = mutableSetOf<Pair<Opcode, MethodInfo>>()
    val instructions = archive.getByteCode(className, methodName).instructions
    for (insn in instructions) {
      if (insn is InvokeInstruction) {
        val info = dex.retrieveMethod(insn.methodIndex())?.toMethodInfo() ?: continue
        fetchedInfos.add(insn.opcode to info)
      }
    }
    Assert.assertTrue((expectedOpcode to expectedInfo) in fetchedInfos)
  }

  private fun testTopLevelFunction(
    methodName: String,
    expectedOpcode: Opcode,
    expectedInfo: MethodInfo,
  ) {
    test("LinvokeInstructions/InvokeInstructionsKt;", methodName, expectedOpcode, expectedInfo)
  }
}
