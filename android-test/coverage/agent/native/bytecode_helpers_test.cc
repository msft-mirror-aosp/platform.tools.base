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

TEST(ConstructorAnalyzerTest, NonConstructorMethodReturnsEmpty) {
  ir::EncodedMethod dummy_method;
  dummy_method.code = nullptr;
  lir::CodeIr code_ir(&dummy_method, nullptr);
  auto result =
      ConstructorAnalyzer::FindSuperCallInstructions(code_ir, "myMethod");
  EXPECT_TRUE(result.empty());
}

TEST(ConstructorAnalyzerTest, EmptyMethodNameReturnsEmpty) {
  ir::EncodedMethod dummy_method;
  dummy_method.code = nullptr;
  lir::CodeIr code_ir(&dummy_method, nullptr);
  auto result = ConstructorAnalyzer::FindSuperCallInstructions(code_ir, "");
  EXPECT_TRUE(result.empty());
}

TEST(ConstructorAnalyzerTest, SpecialMethodNamesReturnEmpty) {
  ir::EncodedMethod dummy_method;
  dummy_method.code = nullptr;
  lir::CodeIr code_ir(&dummy_method, nullptr);
  EXPECT_TRUE(
      ConstructorAnalyzer::FindSuperCallInstructions(code_ir, "<clinit>")
          .empty());
  EXPECT_TRUE(
      ConstructorAnalyzer::FindSuperCallInstructions(code_ir, "init").empty());
  EXPECT_TRUE(
      ConstructorAnalyzer::FindSuperCallInstructions(code_ir, "setup").empty());
}

TEST(ParameterShifterTest, ZeroInsCountReturnsTrue) {
  ir::EncodedMethod dummy_method;
  ir::Code dummy_code;
  dummy_code.ins_count = 0;
  dummy_method.code = &dummy_code;

  lir::CodeIr* dummy_code_ir = nullptr;
  std::vector<lir::Instruction*> dummy_super_calls;

  bool result = ParameterShifter::ShiftParameters(
      &dummy_method, *reinterpret_cast<lir::CodeIr*>(dummy_code_ir),
      dummy_super_calls);
  EXPECT_TRUE(result);
}

TEST(SyntheticFilterTest, CoroutineSuspensionCheckIsSynthetic) {
  ir::EncodedMethod method;
  ir::Code code;
  method.code = &code;
  method.decl = nullptr;

  lir::BasicBlock block;
  lir::Bytecode first_bytecode;
  first_bytecode.opcode = dex::OP_INVOKE_STATIC;

  ir::MethodDecl ir_method;
  ir::Type parent_type;
  ir::String parent_descriptor;
  parent_descriptor.data = slicer::MemView(
      "\x2bLkotlin/coroutines/intrinsics/IntrinsicsKt;",
      sizeof("\x2bLkotlin/coroutines/intrinsics/IntrinsicsKt;"));
  parent_type.descriptor = &parent_descriptor;
  ir_method.parent = &parent_type;

  ir::String method_name;
  method_name.data = slicer::MemView("\x16getCOROUTINE_SUSPENDED",
                                     sizeof("\x16getCOROUTINE_SUSPENDED"));
  ir_method.name = &method_name;
  ir_method.prototype = nullptr;

  lir::Method method_operand(&ir_method, 0);
  first_bytecode.operands.push_back(&method_operand);

  lir::Bytecode move_bytecode;
  move_bytecode.opcode = dex::OP_MOVE_RESULT_OBJECT;
  lir::VReg dest_vreg(0);
  move_bytecode.operands.push_back(&dest_vreg);

  lir::Bytecode last_bytecode;
  last_bytecode.opcode = dex::OP_IF_NEZ;
  lir::VReg cmp_vreg(0);
  last_bytecode.operands.push_back(&cmp_vreg);

  // Link them
  first_bytecode.next = &move_bytecode;
  first_bytecode.prev = nullptr;

  move_bytecode.prev = &first_bytecode;
  move_bytecode.next = &last_bytecode;

  last_bytecode.prev = &move_bytecode;
  last_bytecode.next = nullptr;

  block.region.first = &first_bytecode;
  block.region.last = &last_bytecode;

  auto result = SyntheticFilter::IsSyntheticBranch(&method, block);
  EXPECT_TRUE(result);
}

TEST(SyntheticFilterTest, CoroutineLaunchLambdaSetupIsSynthetic) {
  ir::EncodedMethod method;
  ir::Code code;
  method.code = &code;

  ir::MethodDecl method_decl;
  ir::Type parent_type;
  ir::String parent_descriptor;
  parent_descriptor.data = slicer::MemView(
      "\x23Lcom/example/MyClass$invokeSuspend$1;",
      sizeof("\x23Lcom/example/MyClass$invokeSuspend$1;"));  // 35 chars
  parent_type.descriptor = &parent_descriptor;
  method_decl.parent = &parent_type;

  ir::String method_name;
  method_name.data = slicer::MemView(
      "\x0d\x69nvokeSuspend", sizeof("\x0d\x69nvokeSuspend"));  // 13 chars
  method_decl.name = &method_name;
  method_decl.prototype = nullptr;
  method.decl = &method_decl;

  lir::BasicBlock block;
  lir::Bytecode first_bytecode;
  first_bytecode.opcode = dex::OP_IGET;

  ir::FieldDecl field_decl;
  ir::Type field_type;
  ir::String field_type_descriptor;
  field_type_descriptor.data = slicer::MemView("\x01I", sizeof("\x01I"));
  field_type.descriptor = &field_type_descriptor;
  field_decl.type = &field_type;

  ir::String field_name;
  field_name.data =
      slicer::MemView("\x05label", sizeof("\x05label"));  // 5 chars
  field_decl.name = &field_name;
  lir::Field field_operand(&field_decl, 0);
  first_bytecode.operands.push_back(&field_operand);

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

TEST(SyntheticFilterTest, ComposeRecompositionSkippingBranchIsSynthetic) {
  ir::EncodedMethod method;
  ir::Code code;
  // 4 registers, 2 parameters (ins_count = 2)
  // Parameter p0 is index 2, p1 is index 3
  code.registers = 4;
  code.ins_count = 2;
  method.code = &code;
  method.access_flags = dex::kAccStatic; // Static method

  ir::MethodDecl method_decl;
  method_decl.name = nullptr;
  method_decl.parent = nullptr;
  ir::Proto prototype;
  ir::TypeList param_types;

  ir::Type composer_type;
  ir::String composer_descriptor;
  composer_descriptor.data =
      slicer::MemView("\x23Landroidx/compose/runtime/Composer;",
                      sizeof("\x23Landroidx/compose/runtime/Composer;"));
  composer_type.descriptor = &composer_descriptor;

  ir::Type changed_type;
  ir::String changed_descriptor;
  changed_descriptor.data = slicer::MemView("\x01I", sizeof("\x01I"));
  changed_type.descriptor = &changed_descriptor;

  param_types.types.push_back(&composer_type);
  param_types.types.push_back(&changed_type);
  prototype.param_types = &param_types;
  method_decl.prototype = &prototype;
  method.decl = &method_decl;

  lir::BasicBlock block;
  lir::Bytecode first_bytecode;
  first_bytecode.opcode = dex::OP_AND_INT_LIT8;
  lir::VReg dest_vreg(0);
  lir::VReg src_vreg(3); // p1 ($changed) is index 3
  first_bytecode.operands.push_back(&dest_vreg);
  first_bytecode.operands.push_back(&src_vreg);

  lir::Bytecode last_bytecode;
  last_bytecode.opcode = dex::OP_IF_NE;
  lir::VReg cmp_vreg(0);
  last_bytecode.operands.push_back(&cmp_vreg);

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

TEST(SyntheticFilterTest, ComposeComposerGetSkippingBranchIsSynthetic) {
  ir::EncodedMethod method;
  ir::Code code;
  code.registers = 4;
  code.ins_count = 2;
  method.code = &code;
  method.access_flags = dex::kAccStatic;

  ir::MethodDecl method_decl;
  method_decl.name = nullptr;
  method_decl.parent = nullptr;
  ir::Proto prototype;
  ir::TypeList param_types;

  ir::Type composer_type;
  ir::String composer_descriptor;
  composer_descriptor.data =
      slicer::MemView("\x23Landroidx/compose/runtime/Composer;",
                      sizeof("\x23Landroidx/compose/runtime/Composer;"));
  composer_type.descriptor = &composer_descriptor;

  ir::Type changed_type;
  ir::String changed_descriptor;
  changed_descriptor.data = slicer::MemView("\x01I", sizeof("\x01I"));
  changed_type.descriptor = &changed_descriptor;

  param_types.types.push_back(&composer_type);
  param_types.types.push_back(&changed_type);
  prototype.param_types = &param_types;
  method_decl.prototype = &prototype;
  method.decl = &method_decl;

  lir::BasicBlock block;
  lir::Bytecode first_bytecode;
  first_bytecode.opcode = dex::OP_INVOKE_INTERFACE;

  // Let's mock the getSkipping method operand
  ir::MethodDecl get_skipping_decl;
  ir::Type parent_type;
  parent_type.descriptor = &composer_descriptor;
  get_skipping_decl.parent = &parent_type;
  ir::String get_skipping_name;
  get_skipping_name.data =
      slicer::MemView("\x0bgetSkipping", sizeof("\x0bgetSkipping"));
  get_skipping_decl.name = &get_skipping_name;

  lir::Method method_operand(&get_skipping_decl, 0);
  lir::VReg caller_vreg(2); // Composer (p0) is register index 2
  first_bytecode.operands.push_back(&caller_vreg);
  first_bytecode.operands.push_back(&method_operand);

  lir::Bytecode move_bytecode;
  move_bytecode.opcode = dex::OP_MOVE_RESULT;
  lir::VReg dest_vreg(0);
  move_bytecode.operands.push_back(&dest_vreg);

  lir::Bytecode last_bytecode;
  last_bytecode.opcode = dex::OP_IF_EQZ;
  lir::VReg cmp_vreg(0);
  last_bytecode.operands.push_back(&cmp_vreg);

  // Link them
  first_bytecode.next = &move_bytecode;
  first_bytecode.prev = nullptr;

  move_bytecode.prev = &first_bytecode;
  move_bytecode.next = &last_bytecode;

  last_bytecode.prev = &move_bytecode;
  last_bytecode.next = nullptr;

  block.region.first = &first_bytecode;
  block.region.last = &last_bytecode;

  auto result = SyntheticFilter::IsSyntheticBranch(&method, block);
  EXPECT_TRUE(result);
}

TEST(SyntheticFilterTest, ComposeComposerEndRestartGroupBranchIsSynthetic) {
  ir::EncodedMethod method;
  ir::Code code;
  code.registers = 4;
  code.ins_count = 2;
  method.code = &code;
  method.access_flags = dex::kAccStatic;

  ir::MethodDecl method_decl;
  method_decl.name = nullptr;
  method_decl.parent = nullptr;
  ir::Proto prototype;
  ir::TypeList param_types;

  ir::Type composer_type;
  ir::String composer_descriptor;
  composer_descriptor.data =
      slicer::MemView("\x23Landroidx/compose/runtime/Composer;",
                      sizeof("\x23Landroidx/compose/runtime/Composer;"));
  composer_type.descriptor = &composer_descriptor;

  ir::Type changed_type;
  ir::String changed_descriptor;
  changed_descriptor.data = slicer::MemView("\x01I", sizeof("\x01I"));
  changed_type.descriptor = &changed_descriptor;

  param_types.types.push_back(&composer_type);
  param_types.types.push_back(&changed_type);
  prototype.param_types = &param_types;
  method_decl.prototype = &prototype;
  method.decl = &method_decl;

  lir::BasicBlock block;
  lir::Bytecode first_bytecode;
  first_bytecode.opcode = dex::OP_INVOKE_INTERFACE;

  // Mock the endRestartGroup method
  ir::MethodDecl end_restart_group_decl;
  ir::Type parent_type;
  parent_type.descriptor = &composer_descriptor;
  end_restart_group_decl.parent = &parent_type;
  ir::String end_restart_group_name;
  end_restart_group_name.data = slicer::MemView(
      "\x0f\x65ndRestartGroup",
      sizeof("\x0f\x65ndRestartGroup"));  // 15 chars: endRestartGroup
  end_restart_group_decl.name = &end_restart_group_name;

  lir::Method method_operand(&end_restart_group_decl, 0);
  lir::VReg caller_vreg(2); // Composer (p0) is register index 2
  first_bytecode.operands.push_back(&caller_vreg);
  first_bytecode.operands.push_back(&method_operand);

  lir::Bytecode move_bytecode;
  move_bytecode.opcode = dex::OP_MOVE_RESULT_OBJECT;
  lir::VReg dest_vreg(0);
  move_bytecode.operands.push_back(&dest_vreg);

  lir::Bytecode last_bytecode;
  last_bytecode.opcode = dex::OP_IF_EQZ;
  lir::VReg cmp_vreg(0);
  last_bytecode.operands.push_back(&cmp_vreg);

  // Link them
  first_bytecode.next = &move_bytecode;
  first_bytecode.prev = nullptr;

  move_bytecode.prev = &first_bytecode;
  move_bytecode.next = &last_bytecode;

  last_bytecode.prev = &move_bytecode;
  last_bytecode.next = nullptr;

  block.region.first = &first_bytecode;
  block.region.last = &last_bytecode;

  auto result = SyntheticFilter::IsSyntheticBranch(&method, block);
  EXPECT_TRUE(result);
}

}  // namespace coverage
