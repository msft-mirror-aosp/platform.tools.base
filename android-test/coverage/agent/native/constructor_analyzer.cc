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
#include <cstring>

namespace coverage {

std::vector<lir::Instruction*> ConstructorAnalyzer::FindSuperCallInstructions(
    lir::CodeIr& code_ir, std::string_view method_name) {
  std::vector<lir::Instruction*> super_calls;
  // 1. Constrain evaluation strictly to constructors.
  // In Dalvik bytecode, all constructors are compiled under the special method
  // name "<init>".
  if (method_name != "<init>") {
    return super_calls;
  }

  const auto ir_method = code_ir.ir_method;
  if (ir_method->code == nullptr || ir_method->code->ins_count == 0) {
    return super_calls;
  }

  // 2. Identify the uninitialized 'this' reference.
  // Dalvik places all incoming parameters (including 'this' for instance
  // methods) at the very end of the method's register frame. Parameter 0
  // ('this') of any non-static method is mapped to the register index:
  // registers - ins_count.
  dex::u4 this_reg = ir_method->code->registers - ir_method->code->ins_count;

  // 3. Scan the method body for the parent constructor delegation call.
  // Before an object can be safely initialized, it must call super() or this()
  // to delegate to the parent/sibling constructor. This call is always compiled
  // as a direct invocation (invoke-direct or invoke-direct/range) targeting
  // "<init>" on the 'this_reg'.
  for (auto* instr : code_ir.instructions) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      if (bytecode->opcode == dex::OP_INVOKE_DIRECT ||
          bytecode->opcode == dex::OP_INVOKE_DIRECT_RANGE) {
        if (bytecode->operands.size() > 1) {
          // Verify if the target method being invoked is a constructor
          // ("<init>").
          auto* method_operand =
              dynamic_cast<lir::Method*>(bytecode->operands[1]);
          if (method_operand != nullptr &&
              std::strcmp(method_operand->ir_method->name->c_str(), "<init>") ==
                  0) {
            // Verify if the first register in the invocation list matches our
            // uninitialized 'this' reference.
            bool is_super_or_this_call = false;
            if (bytecode->opcode == dex::OP_INVOKE_DIRECT) {
              auto* vregs = dynamic_cast<lir::VRegList*>(bytecode->operands[0]);
              if (vregs != nullptr && !vregs->registers.empty() &&
                  vregs->registers[0] == this_reg) {
                is_super_or_this_call = true;
              }
            } else if (bytecode->opcode == dex::OP_INVOKE_DIRECT_RANGE) {
              auto* vrange =
                  dynamic_cast<lir::VRegRange*>(bytecode->operands[0]);
              if (vrange != nullptr && vrange->count > 0 &&
                  vrange->base_reg == this_reg) {
                is_super_or_this_call = true;
              }
            }

            if (is_super_or_this_call) {
              super_calls.push_back(instr);
            }
          }
        }
      }
    }
  }

  return super_calls;
}

}  // namespace coverage
