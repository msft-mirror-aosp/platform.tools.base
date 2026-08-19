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

#include "tools/base/android-test/coverage/agent/native/constructor_analyzer.h"
#include "tools/base/android-test/coverage/agent/native/parameter_shifter.h"
#include "tools/base/android-test/coverage/agent/native/register_scanner.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/dex_ir.h"

namespace coverage {

TEST(ConstructorAnalyzerTest, NonConstructorMethodReturnsNull) {
  // Pass a dummy reference since the method-name guard checks method_name
  // first. This verifies that the non-constructor check is robust and exits
  // early.
  lir::CodeIr* dummy_code_ir = nullptr;
  auto* result = ConstructorAnalyzer::FindSuperCallInstruction(
      *reinterpret_cast<lir::CodeIr*>(dummy_code_ir), "myMethod");
  EXPECT_EQ(result, nullptr);
}

TEST(ConstructorAnalyzerTest, EmptyMethodNameReturnsNull) {
  lir::CodeIr* dummy_code_ir = nullptr;
  auto* result = ConstructorAnalyzer::FindSuperCallInstruction(
      *reinterpret_cast<lir::CodeIr*>(dummy_code_ir), "");
  EXPECT_EQ(result, nullptr);
}

TEST(ConstructorAnalyzerTest, SpecialMethodNamesReturnNull) {
  lir::CodeIr* dummy_code_ir = nullptr;
  EXPECT_EQ(ConstructorAnalyzer::FindSuperCallInstruction(
                *reinterpret_cast<lir::CodeIr*>(dummy_code_ir), "<clinit>"),
            nullptr);
  EXPECT_EQ(ConstructorAnalyzer::FindSuperCallInstruction(
                *reinterpret_cast<lir::CodeIr*>(dummy_code_ir), "init"),
            nullptr);
  EXPECT_EQ(ConstructorAnalyzer::FindSuperCallInstruction(
                *reinterpret_cast<lir::CodeIr*>(dummy_code_ir), "setup"),
            nullptr);
}

TEST(ParameterShifterTest, NullPositionReturnsTrue) {
  ir::EncodedMethod dummy_method;
  ir::Code dummy_code;
  dummy_code.ins_count = 5;
  dummy_method.code = &dummy_code;

  lir::CodeIr* dummy_code_ir = nullptr;
  lir::Instruction* dummy_position = nullptr;

  bool result = ParameterShifter::ShiftParameters(
      &dummy_method, *reinterpret_cast<lir::CodeIr*>(dummy_code_ir),
      dummy_position);
  EXPECT_TRUE(result);
}

TEST(ParameterShifterTest, ZeroInsCountReturnsTrue) {
  ir::EncodedMethod dummy_method;
  ir::Code dummy_code;
  dummy_code.ins_count = 0;
  dummy_method.code = &dummy_code;

  lir::CodeIr* dummy_code_ir = nullptr;
  lir::Instruction* dummy_position =
      reinterpret_cast<lir::Instruction*>(0x1234);

  bool result = ParameterShifter::ShiftParameters(
      &dummy_method, *reinterpret_cast<lir::CodeIr*>(dummy_code_ir),
      dummy_position);
  EXPECT_TRUE(result);
}

TEST(SyntheticFilterTest, CoroutineSuspensionCheckIsSynthetic) {
  ir::EncodedMethod method;
  ir::Code code;
  method.code = &code;

  lir::BasicBlock block;
  lir::Bytecode first_bytecode;
  first_bytecode.opcode = dex::OP_INVOKE_STATIC;

  ir::MethodDecl ir_method;
  ir::Type parent_type;
  ir::String parent_descriptor;
  parent_descriptor.data = slicer::MemView("\x2bLkotlin/coroutines/intrinsics/IntrinsicsKt;", sizeof("\x2bLkotlin/coroutines/intrinsics/IntrinsicsKt;") - 1);
  parent_type.descriptor = &parent_descriptor;
  ir_method.parent = &parent_type;

  ir::String method_name;
  method_name.data = slicer::MemView("\x16getCOROUTINE_SUSPENDED", sizeof("\x16getCOROUTINE_SUSPENDED") - 1);
  ir_method.name = &method_name;
  ir_method.prototype = nullptr;

  lir::Method method_operand(&ir_method, 0);
  first_bytecode.operands.push_back(&method_operand);

  lir::Bytecode last_bytecode;
  last_bytecode.opcode = dex::OP_IF_NEZ;

  // Link them
  first_bytecode.next = &last_bytecode;
  first_bytecode.prev = nullptr;
  last_bytecode.prev = &first_bytecode;
  last_bytecode.next = nullptr;

  block.region.first = &first_bytecode;
  block.region.last = &last_bytecode;

  auto result = SyntheticFilter::IsSyntheticBranch(&method, block);
  EXPECT_TRUE(result);
}

}  // namespace coverage
