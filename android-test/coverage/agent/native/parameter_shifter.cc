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

bool ParameterShifter::ShiftParameters(
    ir::EncodedMethod* ir_method, lir::CodeIr& code_ir,
    const std::vector<lir::Instruction*>& super_calls) {
  const dex::u4 ins_count = ir_method->code->ins_count;
  if (ins_count == 0 || code_ir.instructions.empty()) {
    return true;
  }

  // Find the insertion point for normal parameter copy-backs:
  // It is the very first instruction in the method list, ensuring they are
  // placed topologically before any TryBlockBegin instructions, preventing
  // static type-merging VerifyErrors in catch handlers.
  lir::Instruction* first_instr = *code_ir.instructions.begin();

  // Track insertion positions for each separate super_call branch in
  // constructors. This must be maintained across parameter iterations to
  // prevent register overwrites.
  std::vector<lir::Instruction*> insert_positions;
  for (auto* super_call : super_calls) {
    insert_positions.push_back(super_call->next);
  }

  std::vector<ir::Type*> param_types;

  // If the method is non-static, Parameter 0 is the implicit 'this' receiver
  // reference.
  const bool is_constructor = !super_calls.empty();
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

  for (size_t i = 0; i < param_types.size(); ++i) {
    const auto& type = param_types[i];
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

    if (is_constructor) {
      // For constructors, all parameter copy-back moves must be executed
      // immediately after EVERY super() or this() direct delegation call, after
      // 'this' is officially initialized, completely preventing any register
      // overwrites or uninitialized 'this' VerifyErrors. We must allocate
      // brand-new, unique operand instances for each super_move to avoid
      // pointer-sharing corruption!
      for (size_t s = 0; s < super_calls.size(); ++s) {
        auto super_move = code_ir.Alloc<lir::Bytecode>();
        super_move->opcode = move->opcode;
        if (type->GetCategory() == ir::Type::Category::WideScalar) {
          super_move->operands.push_back(
              code_ir.Alloc<lir::VRegPair>(reg - 2 - 1));
          super_move->operands.push_back(code_ir.Alloc<lir::VRegPair>(reg - 2));
        } else {
          super_move->operands.push_back(code_ir.Alloc<lir::VReg>(reg - 1 - 1));
          super_move->operands.push_back(code_ir.Alloc<lir::VReg>(reg - 1));
        }
        code_ir.instructions.InsertBefore(insert_positions[s], super_move);
      }
    } else {
      // Normal parameter copy-back: insert at the very beginning of the method.
      code_ir.instructions.InsertBefore(first_instr, move);
    }
  }

  return true;
}

}  // namespace coverage
