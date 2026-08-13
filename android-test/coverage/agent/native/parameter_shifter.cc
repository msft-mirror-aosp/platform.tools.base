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

#include "tools/base/android-test/coverage/agent/native/parameter_shifter.h"
#include <vector>

namespace coverage {

bool ParameterShifter::ShiftParameters(ir::EncodedMethod* ir_method,
                                       lir::CodeIr& code_ir,
                                       lir::Instruction* position) {
  const dex::u4 ins_count = ir_method->code->ins_count;
  if (ins_count == 0 || position == nullptr) {
    return true;
  }

  std::vector<ir::Type*> param_types;

  // If the method is non-static, Parameter 0 is the implicit 'this' receiver
  // reference.
  if ((ir_method->access_flags & dex::kAccStatic) == 0) {
    param_types.push_back(ir_method->decl->parent);
  }
  if (ir_method->decl->prototype->param_types != nullptr) {
    for (const auto& type : ir_method->decl->prototype->param_types->types) {
      param_types.push_back(type);
    }
  }

  // 'reg' tracks the new shifted register index of each parameter we are
  // copying back.
  dex::u4 reg = ir_method->code->registers - ins_count;

  for (const auto& type : param_types) {
    auto move = code_ir.Alloc<lir::Bytecode>();
    switch (type->GetCategory()) {
      case ir::Type::Category::Reference:
        move->opcode = dex::OP_MOVE_OBJECT_16;
        move->operands.push_back(code_ir.Alloc<lir::VReg>(reg - 1));
        move->operands.push_back(code_ir.Alloc<lir::VReg>(reg));
        reg += 1;
        break;
      case ir::Type::Category::Scalar:
        move->opcode = dex::OP_MOVE_16;
        move->operands.push_back(code_ir.Alloc<lir::VReg>(reg - 1));
        move->operands.push_back(code_ir.Alloc<lir::VReg>(reg));
        reg += 1;
        break;
      case ir::Type::Category::WideScalar:
        // For 64-bit wide parameters, Dalvik requires format-wide
        // OP_MOVE_WIDE_16 instructions, and Slicer's assembler validates that
        // these must have lir::VRegPair operands.
        move->opcode = dex::OP_MOVE_WIDE_16;
        move->operands.push_back(code_ir.Alloc<lir::VRegPair>(reg - 1));
        move->operands.push_back(code_ir.Alloc<lir::VRegPair>(reg));
        reg += 2;
        break;
      case ir::Type::Category::Void:
        Log::E("void parameter type in %s", ir_method->decl->name->c_str());
        return false;
    }
    code_ir.instructions.InsertBefore(position, move);
  }

  return true;
}

}  // namespace coverage
