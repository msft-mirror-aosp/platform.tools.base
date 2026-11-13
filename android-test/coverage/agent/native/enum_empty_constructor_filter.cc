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

#include "tools/base/android-test/coverage/agent/native/enum_empty_constructor_filter.h"

#include <cstring>
#include <string>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kEnum[] = "Ljava/lang/Enum;";
constexpr char kConstructorName[] = "<init>";
constexpr char kConstructorSignature[] = "(Ljava/lang/String;I)V";

// Returns true if `opcode` is a move with which the agent copies a parameter
// back to its register when it grows the frame of a method.
bool IsAgentCopy(dex::Opcode opcode) {
  return opcode == dex::OP_MOVE_16 || opcode == dex::OP_MOVE_WIDE_16 ||
         opcode == dex::OP_MOVE_OBJECT_16;
}

// Returns true if `bytecode` calls the constructor of java.lang.Enum that
// takes the name and the ordinal.
bool CallsEnumConstructor(const lir::Bytecode* bytecode) {
  if (bytecode->opcode != dex::OP_INVOKE_DIRECT ||
      bytecode->operands.size() < 2) {
    return false;
  }
  auto* method = dynamic_cast<lir::Method*>(bytecode->operands[1]);
  const ir::MethodDecl* decl = method == nullptr ? nullptr : method->ir_method;
  return decl != nullptr && decl->parent != nullptr &&
         decl->parent->descriptor != nullptr && decl->name != nullptr &&
         decl->prototype != nullptr &&
         strcmp(decl->parent->descriptor->c_str(), kEnum) == 0 &&
         strcmp(decl->name->c_str(), kConstructorName) == 0 &&
         decl->prototype->Signature() == kConstructorSignature;
}

}  // namespace

void EnumEmptyConstructorFilter::Filter(const FilterContext& context,
                                        ir::EncodedMethod* ir_method,
                                        lir::CodeIr& code_ir,
                                        FilterOutput& output) {
  if (context.super_class_name != kEnum ||
      strcmp(ir_method->decl->name->c_str(), kConstructorName) != 0 ||
      ir_method->decl->prototype->Signature() != kConstructorSignature) {
    return;
  }
  // The code must be the call to the constructor of Enum, then the return,
  // apart from the copies that the agent adds when it grows the frame. The
  // call only passes `this`, the name and the ordinal, the only values that
  // the constructor has at that point, and their registers fit in
  // invoke-direct, so it never uses invoke-direct/range.
  std::vector<const lir::Bytecode*> bytecodes;
  lir::Instruction* last = nullptr;
  for (auto* instr : code_ir.instructions) {
    last = instr;
    auto* bytecode = dynamic_cast<const lir::Bytecode*>(instr);
    if (bytecode != nullptr && !IsAgentCopy(bytecode->opcode)) {
      bytecodes.push_back(bytecode);
    }
  }
  if (bytecodes.size() != 2 || !CallsEnumConstructor(bytecodes[0]) ||
      bytecodes[1]->opcode != dex::OP_RETURN_VOID) {
    return;
  }
  output.Ignore(*code_ir.instructions.begin(), last);
}

REGISTER_FILTER(EnumEmptyConstructorFilter)

}  // namespace coverage
