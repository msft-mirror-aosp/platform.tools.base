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

#include "tools/base/android-test/coverage/agent/native/private_empty_no_arg_constructor_filter.h"

#include <cstring>
#include <string>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kConstructorName[] = "<init>";
constexpr char kNoArgs[] = "()V";

// Returns true if `bytecode` calls the constructor without parameters of
// `owner`, a class descriptor.
bool CallsNoArgConstructorOf(const lir::Bytecode* bytecode,
                             const std::string& owner) {
  if ((bytecode->opcode != dex::OP_INVOKE_DIRECT &&
       bytecode->opcode != dex::OP_INVOKE_DIRECT_RANGE) ||
      bytecode->operands.size() < 2) {
    return false;
  }
  auto* method = dynamic_cast<lir::Method*>(bytecode->operands[1]);
  const ir::MethodDecl* decl = method == nullptr ? nullptr : method->ir_method;
  return decl != nullptr && decl->parent != nullptr &&
         decl->parent->descriptor != nullptr && decl->name != nullptr &&
         decl->prototype != nullptr &&
         owner == decl->parent->descriptor->c_str() &&
         strcmp(decl->name->c_str(), kConstructorName) == 0 &&
         decl->prototype->Signature() == kNoArgs;
}

}  // namespace

void PrivateEmptyNoArgConstructorFilter::Filter(const FilterContext& context,
                                                ir::EncodedMethod* ir_method,
                                                lir::CodeIr& code_ir,
                                                FilterOutput& output) {
  if ((ir_method->access_flags & dex::kAccPrivate) == 0 ||
      strcmp(ir_method->decl->name->c_str(), kConstructorName) != 0 ||
      ir_method->decl->prototype->Signature() != kNoArgs) {
    return;
  }
  // The code must be the call to the super constructor, then the return. When
  // the agent grows the frame of the constructor, it copies `this` back to its
  // register with a move-object/16 after that call.
  std::vector<const lir::Bytecode*> bytecodes;
  lir::Instruction* last = nullptr;
  for (auto* instr : code_ir.instructions) {
    last = instr;
    auto* bytecode = dynamic_cast<const lir::Bytecode*>(instr);
    if (bytecode != nullptr && bytecode->opcode != dex::OP_MOVE_OBJECT_16) {
      bytecodes.push_back(bytecode);
    }
  }
  if (bytecodes.size() != 2 ||
      !CallsNoArgConstructorOf(bytecodes[0], context.super_class_name) ||
      bytecodes[1]->opcode != dex::OP_RETURN_VOID) {
    return;
  }
  output.Ignore(*code_ir.instructions.begin(), last);
}

REGISTER_FILTER(PrivateEmptyNoArgConstructorFilter)

}  // namespace coverage
