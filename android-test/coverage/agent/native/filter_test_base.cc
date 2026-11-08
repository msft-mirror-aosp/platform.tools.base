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

#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/common.h"
#include "slicer/dex_ir.h"
#include "slicer/dex_ir_builder.h"

namespace coverage {

TestMethod::TestMethod(const char* name, const char* signature)
    : dex_ir_(std::make_shared<ir::DexFile>()), builder_(dex_ir_) {
  ir_method_ = dex_ir_->Alloc<ir::EncodedMethod>();
  ir_method_->decl =
      builder_.GetMethodDecl(builder_.GetAsciiString(name), GetProto(signature),
                             builder_.GetType(kTestClassName));
  // Without code to disassemble, the code IR starts empty.
  ir_method_->code = nullptr;
  code_ir_ = std::make_unique<lir::CodeIr>(ir_method_, dex_ir_);
  ir_method_->code = dex_ir_->Alloc<ir::Code>();
}

lir::Bytecode* TestMethod::Add(dex::Opcode opcode,
                               std::vector<lir::Operand*> operands) {
  auto* bytecode = code_ir_->Alloc<lir::Bytecode>();
  bytecode->opcode = opcode;
  bytecode->operands = std::move(operands);
  code_ir_->instructions.push_back(bytecode);
  last_ = bytecode;
  return bytecode;
}

lir::Label* TestMethod::NewLabel() {
  return code_ir_->Alloc<lir::Label>(/*offset=*/0);
}

void TestMethod::Place(lir::Label* label) {
  code_ir_->instructions.push_back(label);
  last_ = label;
}

void TestMethod::AddPackedSwitchPayload(dex::s4 first_key,
                                        std::vector<lir::Label*> targets) {
  auto* payload = code_ir_->Alloc<lir::PackedSwitchPayload>();
  payload->first_key = first_key;
  payload->targets = std::move(targets);
  code_ir_->instructions.push_back(payload);
  last_ = payload;
}

int TestMethod::IndexOf(const lir::Instruction* instr) const {
  int index = 0;
  for (const auto* node : code_ir_->instructions) {
    if (node == instr) {
      return index;
    }
    ++index;
  }
  return -1;
}

lir::VReg* TestMethod::Reg(dex::u4 reg) {
  return code_ir_->Alloc<lir::VReg>(reg);
}

lir::VRegList* TestMethod::Regs(const std::vector<dex::u4>& regs) {
  auto* list = code_ir_->Alloc<lir::VRegList>();
  list->registers = regs;
  return list;
}

lir::VRegRange* TestMethod::RegRange(dex::u4 first, int count) {
  return code_ir_->Alloc<lir::VRegRange>(first, count);
}

lir::Const32* TestMethod::Literal(dex::u4 value) {
  return code_ir_->Alloc<lir::Const32>(value);
}

lir::CodeLocation* TestMethod::Target(lir::Label* label) {
  // Like the disassembler, count the references to the label.
  ++label->refCount;
  return code_ir_->Alloc<lir::CodeLocation>(label);
}

lir::String* TestMethod::StringRef(const char* value) {
  ir::String* string = builder_.GetAsciiString(value);
  return code_ir_->Alloc<lir::String>(string, string->orig_index);
}

lir::Type* TestMethod::TypeRef(const char* descriptor) {
  ir::Type* type = builder_.GetType(descriptor);
  return code_ir_->Alloc<lir::Type>(type, type->orig_index);
}

lir::Field* TestMethod::FieldRef(const char* owner, const char* name,
                                 const char* type) {
  ir::FieldDecl* field =
      builder_.GetFieldDecl(builder_.GetAsciiString(name),
                            builder_.GetType(type), builder_.GetType(owner));
  return code_ir_->Alloc<lir::Field>(field, field->orig_index);
}

lir::Method* TestMethod::MethodRef(const char* owner, const char* name,
                                   const char* signature) {
  ir::MethodDecl* method =
      builder_.GetMethodDecl(builder_.GetAsciiString(name), GetProto(signature),
                             builder_.GetType(owner));
  return code_ir_->Alloc<lir::Method>(method, method->orig_index);
}

ir::Proto* TestMethod::GetProto(const std::string& signature) {
  // "(" + the descriptors of the parameter types + ")" + the return type.
  std::vector<ir::Type*> param_types;
  size_t pos = 1;
  while (pos < signature.size() && signature[pos] != ')') {
    size_t end = signature.find_first_not_of('[', pos);
    if (end < signature.size() && signature[end] == 'L') {
      end = signature.find(';', end);
    }
    SLICER_CHECK_LT(end, signature.size());
    param_types.push_back(
        builder_.GetType(signature.substr(pos, end + 1 - pos).c_str()));
    pos = end + 1;
  }
  SLICER_CHECK_LT(pos + 1, signature.size());
  ir::Type* return_type = builder_.GetType(signature.substr(pos + 1).c_str());
  return builder_.GetProto(return_type, builder_.GetTypeList(param_types));
}

void FilterTestBase::Output::Ignore(lir::Instruction* from,
                                    lir::Instruction* to) {
  ignored_.push_back({from, to});
}

void FilterTestBase::Output::ReplaceBranches(
    lir::Instruction* /*source*/, std::vector<lir::Instruction*> /*targets*/) {
  ADD_FAILURE() << "Unexpected call to ReplaceBranches()";
}

FilterTestBase::FilterTestBase() {
  context_.class_name = kTestClassName;
  context_.super_class_name = "Ljava/lang/Object;";
}

void FilterTestBase::ExpectIgnored(const TestMethod& m,
                                   const std::vector<Range>& expected) const {
  auto describe = [&m](const std::vector<Range>& ranges) {
    std::ostringstream out;
    for (size_t i = 0; i < ranges.size(); ++i) {
      out << "range " << i << " from instruction " << m.IndexOf(ranges[i].from)
          << " to " << m.IndexOf(ranges[i].to) << "\n";
    }
    return out.str();
  };
  EXPECT_EQ(describe(expected), describe(output_.ignored()))
      << "ignored ranges";
}

}  // namespace coverage
