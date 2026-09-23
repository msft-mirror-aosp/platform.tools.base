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

#include "kotlin_compose_filter.h"
#include <cstring>
#include <vector>
#include "filter_interface.h"
#include "slicer/dex_bytecode.h"

namespace coverage {

namespace {

// Helper to find the first preceding bytecode instruction, skipping
// non-bytecode metadata/debug elements
lir::Bytecode* GetPrevBytecode(lir::Instruction* instr) {
  if (instr == nullptr) return nullptr;
  for (auto* p = instr->prev; p != nullptr; p = p->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(p)) {
      return bytecode;
    }
  }
  return nullptr;
}

// Finds the virtual register index of the Composer parameter (using descriptor
// substring matching). This serves as our gatekeeper to verify if the method is
// Composable.
bool FindComposerRegister(ir::EncodedMethod* ir_method, dex::u4& reg_composer) {
  if (ir_method == nullptr || ir_method->code == nullptr ||
      ir_method->decl == nullptr || ir_method->decl->prototype == nullptr) {
    return false;
  }

  const dex::u4 ins_count = ir_method->code->ins_count;
  if (ins_count == 0) {
    return false;
  }

  std::vector<ir::Type*> param_types;

  // Non-static: p0 is 'this'
  if ((ir_method->access_flags & dex::kAccStatic) == 0) {
    param_types.push_back(ir_method->decl->parent);
  }
  if (ir_method->decl->prototype->param_types != nullptr) {
    for (const auto& type : ir_method->decl->prototype->param_types->types) {
      param_types.push_back(type);
    }
  }

  dex::u4 reg = ir_method->code->registers - ins_count;
  for (const auto& type : param_types) {
    dex::u4 current_reg = reg;
    if (type->GetCategory() == ir::Type::Category::WideScalar) {
      reg += 2;
    } else {
      reg += 1;
    }

    if (type->descriptor != nullptr) {
      const char* descriptor = type->descriptor->c_str();
      // Perform a substring search to be fully immune to obfuscation or custom
      // package annotations
      if (descriptor != nullptr && strstr(descriptor, "Composer;") != nullptr) {
        reg_composer = current_reg;
        return true;
      }
    }
  }

  return false;
}

// Helper to determine if a branch instruction is topologically located before
// or at the shouldExecute or getSkipping branch in a Composable method.
bool IsBeforeComposeBoundary(ir::EncodedMethod* ir_method,
                             lir::Instruction* branch_instr) {
  if (ir_method == nullptr || branch_instr == nullptr) {
    return false;
  }

  lir::Instruction* boundary_instr = nullptr;

  // Find the first instruction of the method to start scanning forward
  lir::Instruction* first_instr = branch_instr;
  while (first_instr->prev != nullptr) {
    first_instr = first_instr->prev;
  }

  for (auto* instr = first_instr; instr != nullptr; instr = instr->next) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (op >= dex::OP_INVOKE_VIRTUAL &&
          op <= dex::OP_INVOKE_INTERFACE_RANGE) {
        for (auto* operand : bytecode->operands) {
          if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
            if (method_operand->ir_method != nullptr &&
                method_operand->ir_method->name != nullptr &&
                method_operand->ir_method->parent != nullptr &&
                method_operand->ir_method->parent->descriptor != nullptr) {
              const char* parent_class =
                  method_operand->ir_method->parent->descriptor->c_str();
              const char* m_name = method_operand->ir_method->name->c_str();
              if (strstr(parent_class, "runtime/Composer") != nullptr &&
                  (strcmp(m_name, "shouldExecute") == 0 ||
                   strcmp(m_name, "getSkipping") == 0)) {
                // The boundary is the conditional branch immediately following
                // this invoke
                for (auto* next_instr = instr->next; next_instr != nullptr;
                     next_instr = next_instr->next) {
                  if (auto* next_bytecode =
                          dynamic_cast<lir::Bytecode*>(next_instr)) {
                    auto next_flags =
                        dex::GetFlagsFromOpcode(next_bytecode->opcode);
                    if (next_flags & dex::kBranch) {
                      boundary_instr = next_instr;
                      break;
                    }
                  }
                }
                break;
              }
            }
          }
        }
      }
      if (boundary_instr != nullptr) {
        break;
      }
    }
  }

  if (boundary_instr == nullptr) {
    return false;
  }

  // Check if our branch_instr appears before or is equal to boundary_instr
  for (auto* instr = first_instr; instr != nullptr; instr = instr->next) {
    if (instr == branch_instr) {
      return true;
    }
    if (instr == boundary_instr) {
      break;
    }
  }

  return false;
}

// Helper to determine if a branch instruction is topologically located in the
// Compose skipping epilogue
bool IsInComposeEpilogue(ir::EncodedMethod* ir_method,
                         lir::Instruction* branch_instr) {
  if (ir_method == nullptr || branch_instr == nullptr) {
    return false;
  }

  lir::Instruction* epilogue_start_label = nullptr;

  lir::Instruction* first_instr = branch_instr;
  while (first_instr->prev != nullptr) {
    first_instr = first_instr->prev;
  }

  for (auto* instr = first_instr; instr != nullptr; instr = instr->next) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (op >= dex::OP_INVOKE_VIRTUAL &&
          op <= dex::OP_INVOKE_INTERFACE_RANGE) {
        for (auto* operand : bytecode->operands) {
          if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
            if (method_operand->ir_method != nullptr &&
                method_operand->ir_method->name != nullptr &&
                method_operand->ir_method->parent != nullptr &&
                method_operand->ir_method->parent->descriptor != nullptr) {
              const char* parent_class =
                  method_operand->ir_method->parent->descriptor->c_str();
              const char* m_name = method_operand->ir_method->name->c_str();
              if (strstr(parent_class, "runtime/Composer") != nullptr &&
                  strcmp(m_name, "getSkipping") == 0) {
                // Find the branch instruction following getSkipping
                for (auto* next_instr = instr->next; next_instr != nullptr;
                     next_instr = next_instr->next) {
                  if (auto* next_bytecode =
                          dynamic_cast<lir::Bytecode*>(next_instr)) {
                    auto next_flags =
                        dex::GetFlagsFromOpcode(next_bytecode->opcode);
                    if (next_flags & dex::kBranch) {
                      // Extract the target label of the skipping branch
                      // (epilogue start)
                      for (auto* op_or : next_bytecode->operands) {
                        if (auto* code_loc =
                                dynamic_cast<lir::CodeLocation*>(op_or)) {
                          epilogue_start_label = code_loc->label;
                        }
                      }
                      break;
                    }
                  }
                }
                break;
              }
            }
          }
        }
      }
      if (epilogue_start_label != nullptr) {
        break;
      }
    }
  }

  if (epilogue_start_label == nullptr) {
    return false;
  }

  // Check if our branch_instr appears at or after epilogue_start_label
  bool in_epilogue = false;
  for (auto* instr = first_instr; instr != nullptr; instr = instr->next) {
    if (instr == epilogue_start_label) {
      in_epilogue = true;
    }
    if (instr == branch_instr) {
      return in_epilogue;
    }
  }
  return false;
}

// Detects the endRestartGroup() POP-gated branch suppression
bool IsEndRestartGroupBranch(ir::EncodedMethod* ir_method,
                             lir::Instruction* branch_instr) {
  auto* bytecode = dynamic_cast<lir::Bytecode*>(branch_instr);
  if (bytecode == nullptr || bytecode->opcode != dex::OP_IF_EQZ) {
    return false;
  }

  lir::Instruction* prev_instr = GetPrevBytecode(branch_instr);
  if (prev_instr == nullptr) {
    return false;
  }

  auto* prev_bytecode = dynamic_cast<lir::Bytecode*>(prev_instr);
  if (prev_bytecode != nullptr &&
      prev_bytecode->opcode == dex::OP_MOVE_RESULT_OBJECT) {
    prev_instr = GetPrevBytecode(prev_instr);
  }

  auto* invoke_bytecode = dynamic_cast<lir::Bytecode*>(prev_instr);
  if (invoke_bytecode == nullptr) {
    return false;
  }

  dex::Opcode invoke_op = invoke_bytecode->opcode;
  if (invoke_op >= dex::OP_INVOKE_VIRTUAL &&
      invoke_op <= dex::OP_INVOKE_INTERFACE_RANGE) {
    for (auto* operand : invoke_bytecode->operands) {
      if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
        if (method_operand->ir_method != nullptr &&
            method_operand->ir_method->name != nullptr &&
            method_operand->ir_method->parent != nullptr &&
            method_operand->ir_method->parent->descriptor != nullptr) {
          const char* parent_class =
              method_operand->ir_method->parent->descriptor->c_str();
          const char* m_name = method_operand->ir_method->name->c_str();
          if (strstr(parent_class, "runtime/Composer") != nullptr &&
              strstr(m_name, "endRestartGroup") != nullptr) {
            bool has_target = false;
            for (auto* op_or : bytecode->operands) {
              if (auto* code_loc = dynamic_cast<lir::CodeLocation*>(op_or)) {
                has_target = true;
                if (code_loc->label != nullptr &&
                    code_loc->label->next != nullptr) {
                  if (auto* target_bytecode =
                          dynamic_cast<lir::Bytecode*>(code_loc->label->next)) {
                    // Check if the jump target represents a clean POP or
                    // equivalent in DEX
                    return true;
                  }
                }
              }
            }
            return !has_target;
          }
        }
      }
    }
  }
  return false;
}

// Detects the isTraceInProgress() conditional branch suppression
bool IsTraceInProgressBranch(ir::EncodedMethod* ir_method,
                             lir::Instruction* branch_instr) {
  auto* bytecode = dynamic_cast<lir::Bytecode*>(branch_instr);
  if (bytecode == nullptr || bytecode->opcode != dex::OP_IF_EQZ) {
    return false;
  }

  lir::Instruction* prev_instr = GetPrevBytecode(branch_instr);
  if (prev_instr == nullptr) {
    return false;
  }

  auto* prev_bytecode = dynamic_cast<lir::Bytecode*>(prev_instr);
  if (prev_bytecode != nullptr &&
      prev_bytecode->opcode == dex::OP_MOVE_RESULT) {
    prev_instr = GetPrevBytecode(prev_instr);
  }

  auto* invoke_bytecode = dynamic_cast<lir::Bytecode*>(prev_instr);
  if (invoke_bytecode == nullptr) {
    return false;
  }

  dex::Opcode invoke_op = invoke_bytecode->opcode;
  if (invoke_op == dex::OP_INVOKE_STATIC ||
      invoke_op == dex::OP_INVOKE_STATIC_RANGE) {
    for (auto* operand : invoke_bytecode->operands) {
      if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
        if (method_operand->ir_method != nullptr &&
            method_operand->ir_method->name != nullptr &&
            method_operand->ir_method->parent != nullptr &&
            method_operand->ir_method->parent->descriptor != nullptr) {
          const char* parent_class =
              method_operand->ir_method->parent->descriptor->c_str();
          const char* m_name = method_operand->ir_method->name->c_str();
          if (strstr(parent_class, "runtime/ComposerKt") != nullptr &&
              strstr(m_name, "isTraceInProgress") != nullptr) {
            return true;
          }
        }
      }
    }
  }
  return false;
}

}  // namespace

bool KotlinComposeFilter::FilterBranch(ir::EncodedMethod* ir_method,
                                       lir::Instruction* branch_instr) {
  dex::u4 reg_composer = 0;
  if (!FindComposerRegister(ir_method, reg_composer)) {
    return false;
  }

  // 1. shouldExecute and getSkipping prologue range-wipe
  if (IsBeforeComposeBoundary(ir_method, branch_instr)) {
    return true;
  }

  // 2. getSkipping epilogue range-wipe
  if (IsInComposeEpilogue(ir_method, branch_instr)) {
    return true;
  }

  // 3. endRestartGroup POP-gated branch suppression
  if (IsEndRestartGroupBranch(ir_method, branch_instr)) {
    return true;
  }

  // 4. isTraceInProgress conditional branch suppression
  if (IsTraceInProgressBranch(ir_method, branch_instr)) {
    return true;
  }

  return false;
}

REGISTER_FILTER(KotlinComposeFilter)

}  // namespace coverage
