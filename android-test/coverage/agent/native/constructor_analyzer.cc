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
#include "slicer/dex_format.h"
#include "slicer/dex_leb128.h"

namespace coverage {

namespace {

// Returns whether the debug info maps an address to a line, like an entry of
// the LineNumberTable of a class file.
bool HasLineNumbers(const ir::DebugInfo* debug_info) {
  if (debug_info == nullptr || debug_info->data.size() == 0) {
    return false;
  }
  const dex::u1* ptr = debug_info->data.ptr<dex::u1>();
  for (dex::u1 opcode = *ptr++; opcode != dex::DBG_END_SEQUENCE;
       opcode = *ptr++) {
    switch (opcode) {
      case dex::DBG_ADVANCE_PC:
      case dex::DBG_END_LOCAL:
      case dex::DBG_RESTART_LOCAL:
      case dex::DBG_SET_FILE:
        dex::ReadULeb128(&ptr);
        break;
      case dex::DBG_ADVANCE_LINE:
        dex::ReadSLeb128(&ptr);
        break;
      case dex::DBG_START_LOCAL:
        // The register, name and type.
        for (int i = 0; i < 3; ++i) {
          dex::ReadULeb128(&ptr);
        }
        break;
      case dex::DBG_START_LOCAL_EXTENDED:
        // The register, name, type and signature.
        for (int i = 0; i < 4; ++i) {
          dex::ReadULeb128(&ptr);
        }
        break;
      case dex::DBG_SET_PROLOGUE_END:
      case dex::DBG_SET_EPILOGUE_BEGIN:
        break;
      default:
        // A special opcode, which emits a position: an address and its line.
        return true;
    }
  }
  return false;
}

}  // namespace

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

bool ConstructorAnalyzer::IsDefaultArgumentsConstructor(
    const ir::EncodedMethod* ir_method) {
  if (ir_method == nullptr || ir_method->decl == nullptr ||
      (ir_method->access_flags & dex::kAccSynthetic) == 0 ||
      std::strcmp(ir_method->decl->name->c_str(), "<init>") != 0 ||
      ir_method->decl->prototype == nullptr) {
    return false;
  }
  // Its parameters end with the int mask of the default values to use, then
  // the marker.
  const auto* params = ir_method->decl->prototype->param_types;
  if (params == nullptr || params->types.size() < 2) {
    return false;
  }
  const auto& types = params->types;
  if (std::strcmp(types[types.size() - 2]->descriptor->c_str(), "I") != 0 ||
      std::strcmp(types.back()->descriptor->c_str(),
                  "Lkotlin/jvm/internal/DefaultConstructorMarker;") != 0) {
    return false;
  }
  // Kotlin's synthetic accessors of private constructors end with the marker
  // too, and sometimes with an int before it. Unlike the constructor for
  // default arguments, they have no line numbers, which is also why JaCoCo
  // leaves them out.
  return ir_method->code != nullptr &&
         HasLineNumbers(ir_method->code->debug_info);
}

lir::Instruction* ConstructorAnalyzer::FindDefaultArgumentsDelegation(
    lir::CodeIr& code_ir) {
  lir::Bytecode* last = nullptr;
  lir::Bytecode* second_to_last = nullptr;
  for (auto* instr : code_ir.instructions) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      second_to_last = last;
      last = bytecode;
    }
  }
  if (last == nullptr || last->opcode != dex::OP_RETURN_VOID ||
      second_to_last == nullptr ||
      (second_to_last->opcode != dex::OP_INVOKE_DIRECT &&
       second_to_last->opcode != dex::OP_INVOKE_DIRECT_RANGE) ||
      second_to_last->operands.size() < 2) {
    return nullptr;
  }
  auto* callee = dynamic_cast<lir::Method*>(second_to_last->operands[1]);
  const auto* constructor = code_ir.ir_method->decl;
  if (callee == nullptr ||
      std::strcmp(callee->ir_method->name->c_str(), "<init>") != 0 ||
      std::strcmp(callee->ir_method->parent->descriptor->c_str(),
                  constructor->parent->descriptor->c_str()) != 0) {
    return nullptr;
  }
  return second_to_last;
}

}  // namespace coverage
