/*
* Copyright (C) 2025 The Android Open Source Project
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
*
*/

#include "tools/base/deploy/agent/native/transform/print.h"

#include <sstream>

#include "slicer/dex_ir.h"
#include "slicer/instrumentation.h"
#include "slicer/reader.h"
#include "slicer/writer.h"
#include "tools/base/deploy/common/log.h"

std::string BytecodeToString::GetBytecodeString() {
  if (bytecode_ != nullptr) {
    const std::string opcode(GetOpcodeName(bytecode_->opcode));
    const std::string operands = operands_.str();
    bytecode_ = nullptr;
    label_ = nullptr;
    return opcode + " " + operands;
  }

  if (label_ != nullptr) {
    operands_ << label_->id;
    bytecode_ = nullptr;
    label_ = nullptr;
    return operands_.str();
  }

  return "";
}
bool BytecodeToString::Visit(lir::Bytecode* bytecode) override {
  operands_.clear();
  operands_.str("");
  bytecode_ = bytecode;
  label_ = nullptr;
  for (const auto& operand : bytecode->operands) {
    operand->Accept(this);
    operands_ << " ";
  }
  return true;
}

bool BytecodeToString::Visit(lir::Label* label) override {
  operands_.clear();
  operands_.str("");
  bytecode_ = nullptr;
  label_ = label;
  return true;
}

bool BytecodeToString::Visit(lir::CodeLocation* location) override {
  operands_ << "L:" << location->label->id;
  return true;
}

bool BytecodeToString::Visit(lir::Const32* const32) override {
  operands_ << const32->u.u4_value;
  return true;
}

bool BytecodeToString::Visit(lir::Const64* const64) override {
  operands_ << const64->u.u8_value;
  return true;
}

bool BytecodeToString::Visit(lir::VReg* vreg) override {
  operands_ << "R:" << vreg->reg;
  return true;
}

bool BytecodeToString::Visit(lir::VRegPair* vreg_pair) override {
  operands_ << "R:" << vreg_pair->base_reg
            << ", R:" << vreg_pair->base_reg + 1;
  return true;
}

bool BytecodeToString::Visit(lir::VRegList* vreg_list) override {
  operands_ << "[ ";
  for (const dex::u4 reg : vreg_list->registers) {
    operands_ << "R:" << reg << " ";
  }
  operands_ << "]";
  return true;
}

bool BytecodeToString::Visit(lir::VRegRange* vreg_range) override {
  operands_ << "R:" << vreg_range->base_reg << "-"
            << vreg_range->base_reg + vreg_range->count;
  return true;
}
bool BytecodeToString::Visit(lir::String* string) override {
  operands_ << string->ir_string->c_str();
  return true;
}

bool BytecodeToString::Visit(lir::Type* type) override {
  operands_ << type->ir_type->descriptor->c_str();
  return true;
}

bool BytecodeToString::Visit(lir::Field* field) override {
  operands_ << field->ir_field->name->c_str() << ": "
            << field->ir_field->type->descriptor->c_str();
  return true;
}

bool BytecodeToString::Visit(lir::Method* method) override {
  operands_ << method->ir_method->parent->Decl().c_str() << "."
            << method->ir_method->name->c_str()
            << method->ir_method->prototype->Signature().c_str();
  return true;
}

bool Print::Apply(lir::CodeIr* code_ir) override {
  BytecodeToString visitor;
  for (const auto instr : code_ir->instructions) {
    instr->Accept(&visitor);
    deploy::Log::V("%s", visitor.GetBytecodeString().c_str());
  }
  return true;
}
