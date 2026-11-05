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
#include "tools/base/android-test/coverage/agent/native/instrumenter.h"
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

  // Prepend a mock getSkipping boundary after the branch under test,
  // making the branch under test topologically locate inside the prologue
  // range.
  lir::Bytecode invoke_get_skipping;
  invoke_get_skipping.opcode = dex::OP_INVOKE_INTERFACE;
  ir::MethodDecl get_skipping_decl;
  ir::Type parent_type;
  parent_type.descriptor = &composer_descriptor;
  get_skipping_decl.parent = &parent_type;
  ir::String get_skipping_name;
  get_skipping_name.data =
      slicer::MemView("\x0bgetSkipping", sizeof("\x0bgetSkipping"));
  get_skipping_decl.name = &get_skipping_name;
  lir::Method method_operand(&get_skipping_decl, 0);
  lir::VReg caller_vreg(2);  // Composer (p0) is register index 2
  invoke_get_skipping.operands.push_back(&caller_vreg);
  invoke_get_skipping.operands.push_back(&method_operand);

  lir::Bytecode move_bytecode;
  move_bytecode.opcode = dex::OP_MOVE_RESULT;
  lir::VReg move_vreg(0);
  move_bytecode.operands.push_back(&move_vreg);

  lir::Bytecode get_skipping_branch;
  get_skipping_branch.opcode = dex::OP_IF_EQZ;
  lir::VReg branch_vreg(0);
  get_skipping_branch.operands.push_back(&branch_vreg);

  // Link them
  first_bytecode.next = &last_bytecode;
  first_bytecode.prev = nullptr;
  last_bytecode.prev = &first_bytecode;
  last_bytecode.next = &invoke_get_skipping;

  invoke_get_skipping.prev = &last_bytecode;
  invoke_get_skipping.next = &move_bytecode;

  move_bytecode.prev = &invoke_get_skipping;
  move_bytecode.next = &get_skipping_branch;

  get_skipping_branch.prev = &move_bytecode;
  get_skipping_branch.next = nullptr;

  block.region.first = &first_bytecode;
  block.region.last = &get_skipping_branch;

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

namespace {

// A special debug info opcode, which emits a position without advancing the
// address or the line.
constexpr dex::u1 kPosition = dex::DBG_FIRST_SPECIAL - dex::DBG_LINE_BASE;
constexpr dex::u1 kOnePosition[] = {kPosition, dex::DBG_END_SEQUENCE};

// The IR of
// `Lcom/example/Item;.<init>(IILkotlin/jvm/internal/DefaultConstructorMarker;)V`,
// a constructor that Kotlin generates for default arguments.
struct DefaultArgumentsConstructor {
  DefaultArgumentsConstructor() {
    name.data = slicer::MemView("\x06<init>", sizeof("\x06<init>"));
    class_descriptor.data = slicer::MemView("\x12Lcom/example/Item;",
                                            sizeof("\x12Lcom/example/Item;"));
    other_class_descriptor.data = slicer::MemView(
        "\x13Lcom/example/Other;", sizeof("\x13Lcom/example/Other;"));
    int_descriptor.data = slicer::MemView("\x01I", sizeof("\x01I"));
    marker_descriptor.data = slicer::MemView(
        "\x2eLkotlin/jvm/internal/DefaultConstructorMarker;",
        sizeof("\x2eLkotlin/jvm/internal/DefaultConstructorMarker;"));
    class_def.source_file = nullptr;
    class_type.descriptor = &class_descriptor;
    class_type.class_def = &class_def;
    other_class_type.descriptor = &other_class_descriptor;
    int_type.descriptor = &int_descriptor;
    marker_type.descriptor = &marker_descriptor;
    params.types = {&int_type, &int_type, &marker_type};
    proto.param_types = &params;
    decl.name = &name;
    decl.prototype = &proto;
    decl.parent = &class_type;
    debug_info.line_start = 1;
    debug_info.data = slicer::MemView(kOnePosition, sizeof(kOnePosition));
    code.registers = 0;
    code.ins_count = 0;
    code.outs_count = 0;
    code.debug_info = &debug_info;
    method.decl = &decl;
    method.access_flags =
        dex::kAccPublic | dex::kAccSynthetic | dex::kAccConstructor;
    method.code = &code;
  }

  ir::String name;
  ir::String class_descriptor;
  ir::String other_class_descriptor;
  ir::String int_descriptor;
  ir::String marker_descriptor;
  ir::Class class_def;
  ir::Type class_type;
  ir::Type other_class_type;
  ir::Type int_type;
  ir::Type marker_type;
  ir::TypeList params;
  ir::Proto proto;
  ir::MethodDecl decl;
  ir::DebugInfo debug_info;
  ir::Code code;
  ir::EncodedMethod method;
};

}  // namespace

TEST(ConstructorAnalyzerTest, RecognizesConstructorForDefaultArguments) {
  DefaultArgumentsConstructor constructor;
  EXPECT_TRUE(
      ConstructorAnalyzer::IsDefaultArgumentsConstructor(&constructor.method));
}

TEST(ConstructorAnalyzerTest, ConstructorForDefaultArgumentsIsSynthetic) {
  DefaultArgumentsConstructor constructor;
  constructor.method.access_flags &= ~dex::kAccSynthetic;
  EXPECT_FALSE(
      ConstructorAnalyzer::IsDefaultArgumentsConstructor(&constructor.method));
}

TEST(ConstructorAnalyzerTest,
     ConstructorForDefaultArgumentsEndsWithDefaultConstructorMarker) {
  DefaultArgumentsConstructor constructor;
  constructor.params.types = {&constructor.marker_type, &constructor.int_type};
  EXPECT_FALSE(
      ConstructorAnalyzer::IsDefaultArgumentsConstructor(&constructor.method));
}

TEST(ConstructorAnalyzerTest,
     ConstructorForDefaultArgumentsHasAnIntMaskBeforeTheMarker) {
  DefaultArgumentsConstructor constructor;
  constructor.params.types = {&constructor.int_type,
                              &constructor.other_class_type,
                              &constructor.marker_type};
  EXPECT_FALSE(
      ConstructorAnalyzer::IsDefaultArgumentsConstructor(&constructor.method));
}

TEST(ConstructorAnalyzerTest, AccessorThatOnlyTakesTheMarkerIsNotRecognized) {
  // Like Foo$Companion.<init>(Lkotlin/jvm/internal/DefaultConstructorMarker;)V,
  // which every companion object has.
  DefaultArgumentsConstructor constructor;
  constructor.params.types = {&constructor.marker_type};
  EXPECT_FALSE(
      ConstructorAnalyzer::IsDefaultArgumentsConstructor(&constructor.method));
}

TEST(ConstructorAnalyzerTest, ConstructorForDefaultArgumentsHasLineNumbers) {
  // Like the accessor of a private constructor whose last parameter is an int,
  // such as kotlin.ranges.UIntProgression's
  // <init>(IIILkotlin/jvm/internal/DefaultConstructorMarker;)V.
  DefaultArgumentsConstructor constructor;
  constructor.code.debug_info = nullptr;
  EXPECT_FALSE(
      ConstructorAnalyzer::IsDefaultArgumentsConstructor(&constructor.method));

  // A local, then changes of the address and the line, but no position. Each
  // operand would be a position if it was read as an opcode.
  constexpr dex::u1 kNoPosition[] = {
      dex::DBG_START_LOCAL,  0x10, 0x11, 0x12,  // The register, name and type.
      dex::DBG_ADVANCE_PC,   0x20,              // The address difference.
      dex::DBG_ADVANCE_LINE, 0x0b,              // The line difference.
      dex::DBG_END_SEQUENCE};
  constructor.debug_info.data =
      slicer::MemView(kNoPosition, sizeof(kNoPosition));
  constructor.code.debug_info = &constructor.debug_info;
  EXPECT_FALSE(
      ConstructorAnalyzer::IsDefaultArgumentsConstructor(&constructor.method));
}

TEST(ConstructorAnalyzerTest, FindsLineNumbersAfterOtherDebugInfo) {
  DefaultArgumentsConstructor constructor;
  constexpr dex::u1 kLaterPosition[] = {dex::DBG_ADVANCE_LINE, 0x0b, kPosition,
                                        dex::DBG_END_SEQUENCE};
  constructor.debug_info.data =
      slicer::MemView(kLaterPosition, sizeof(kLaterPosition));
  EXPECT_TRUE(
      ConstructorAnalyzer::IsDefaultArgumentsConstructor(&constructor.method));
}

TEST(ConstructorAnalyzerTest, MethodForDefaultArgumentsIsNotAConstructor) {
  DefaultArgumentsConstructor constructor;
  constructor.name.data = slicer::MemView("\x03get", sizeof("\x03get"));
  EXPECT_FALSE(
      ConstructorAnalyzer::IsDefaultArgumentsConstructor(&constructor.method));
}

TEST(ConstructorAnalyzerTest, FindsThisCallFollowedByReturnVoid) {
  DefaultArgumentsConstructor constructor;
  lir::CodeIr code_ir(&constructor.method, nullptr);
  ir::MethodDecl primary_constructor;
  primary_constructor.name = &constructor.name;
  primary_constructor.parent = &constructor.class_type;
  lir::VRegRange args(0, 2);
  lir::Method callee(&primary_constructor, 0);

  // const/4 v1, 0; invoke-direct/range {v0 .. v1}, Item.<init>(I)V; return-void
  lir::Bytecode default_value;
  default_value.opcode = dex::OP_CONST_4;
  lir::Bytecode this_call;
  this_call.opcode = dex::OP_INVOKE_DIRECT_RANGE;
  this_call.operands = {&args, &callee};
  lir::Bytecode return_void;
  return_void.opcode = dex::OP_RETURN_VOID;
  code_ir.instructions.push_back(&default_value);
  code_ir.instructions.push_back(&this_call);
  code_ir.instructions.push_back(&return_void);

  EXPECT_EQ(ConstructorAnalyzer::FindDefaultArgumentsDelegation(code_ir),
            &this_call);
}

TEST(ConstructorAnalyzerTest, RejectsInstructionsBetweenThisCallAndReturnVoid) {
  DefaultArgumentsConstructor constructor;
  lir::CodeIr code_ir(&constructor.method, nullptr);
  ir::MethodDecl primary_constructor;
  primary_constructor.name = &constructor.name;
  primary_constructor.parent = &constructor.class_type;
  lir::VRegRange args(0, 2);
  lir::Method callee(&primary_constructor, 0);

  lir::Bytecode this_call;
  this_call.opcode = dex::OP_INVOKE_DIRECT_RANGE;
  this_call.operands = {&args, &callee};
  lir::Bytecode after_call;
  after_call.opcode = dex::OP_CONST_4;
  lir::Bytecode return_void;
  return_void.opcode = dex::OP_RETURN_VOID;
  code_ir.instructions.push_back(&this_call);
  code_ir.instructions.push_back(&after_call);
  code_ir.instructions.push_back(&return_void);

  EXPECT_EQ(ConstructorAnalyzer::FindDefaultArgumentsDelegation(code_ir),
            nullptr);
}

TEST(ConstructorAnalyzerTest, RejectsCallToConstructorOfAnotherClass) {
  DefaultArgumentsConstructor constructor;
  lir::CodeIr code_ir(&constructor.method, nullptr);
  ir::MethodDecl super_constructor;
  super_constructor.name = &constructor.name;
  super_constructor.parent = &constructor.other_class_type;
  lir::VRegRange args(0, 1);
  lir::Method callee(&super_constructor, 0);

  lir::Bytecode super_call;
  super_call.opcode = dex::OP_INVOKE_DIRECT_RANGE;
  super_call.operands = {&args, &callee};
  lir::Bytecode return_void;
  return_void.opcode = dex::OP_RETURN_VOID;
  code_ir.instructions.push_back(&super_call);
  code_ir.instructions.push_back(&return_void);

  EXPECT_EQ(ConstructorAnalyzer::FindDefaultArgumentsDelegation(code_ir),
            nullptr);
}

TEST(ConstructorAnalyzerTest, RejectsConstructorThatDoesNotEndWithReturnVoid) {
  DefaultArgumentsConstructor constructor;
  lir::CodeIr code_ir(&constructor.method, nullptr);
  ir::MethodDecl primary_constructor;
  primary_constructor.name = &constructor.name;
  primary_constructor.parent = &constructor.class_type;
  lir::VRegRange args(0, 2);
  lir::Method callee(&primary_constructor, 0);

  lir::Bytecode this_call;
  this_call.opcode = dex::OP_INVOKE_DIRECT_RANGE;
  this_call.operands = {&args, &callee};
  lir::Bytecode throw_instr;
  throw_instr.opcode = dex::OP_THROW;
  code_ir.instructions.push_back(&this_call);
  code_ir.instructions.push_back(&throw_instr);

  EXPECT_EQ(ConstructorAnalyzer::FindDefaultArgumentsDelegation(code_ir),
            nullptr);
}

TEST(InstrumenterTest, MatchesClassesWithTheInclusionPrefix) {
  Instrumenter instrumenter(nullptr, "com/example");
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example"));
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example/Foo"));
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example/Foo$Bar"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter("com/examples/Foo"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter("org/example/Foo"));
}

TEST(InstrumenterTest, DoesNotMatchGeneratedClassesOrTheCoverageRuntime) {
  Instrumenter instrumenter(nullptr, "com");
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter(
      "com/example/Foo$$ExternalSyntheticLambda0"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter(
      "com/android/tools/coverage/CoverageTracker"));
}

TEST(RegisterScannerTest, FindsFirstUnusedLocalRegister) {
  ir::EncodedMethod method{};
  ir::Code code{};
  code.registers = 4;
  code.ins_count = 1;
  method.code = &code;
  lir::CodeIr code_ir(&method, nullptr);

  lir::Bytecode instr{};
  lir::VRegPair used_pair(0);
  instr.operands.push_back(&used_pair);
  code_ir.instructions.push_back(&instr);

  bool found_unused = false;
  EXPECT_EQ(RegisterScanner::FindUnusedScratchRegister(&method, code_ir,
                                                       found_unused),
            2u);
  EXPECT_TRUE(found_unused);
}

TEST(RegisterScannerTest, DoesNotSelectParameterOrRegisterAboveFourBitLimit) {
  ir::EncodedMethod method{};
  ir::Code code{};
  code.registers = 18;
  code.ins_count = 1;
  method.code = &code;
  lir::CodeIr code_ir(&method, nullptr);

  lir::Bytecode instr{};
  lir::VRegRange used_low_regs(0, 16);
  instr.operands.push_back(&used_low_regs);
  code_ir.instructions.push_back(&instr);

  bool found_unused = true;
  RegisterScanner::FindUnusedScratchRegister(&method, code_ir, found_unused);
  EXPECT_FALSE(found_unused);
}

}  // namespace coverage
