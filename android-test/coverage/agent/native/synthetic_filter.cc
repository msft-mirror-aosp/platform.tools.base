#include "synthetic_filter.h"
#include "slicer/dex_bytecode.h"
#include <algorithm>
#include <cstring>

namespace coverage {

namespace {

// Traces backward in the flat instruction stream to determine if a virtual register
// was loaded with CoroutineSingletons.COROUTINE_SUSPENDED.
bool TraceRegisterToCoroutineSuspended(lir::Instruction* branch_instr, int target_reg) {
  for (auto* instr = branch_instr->prev; instr != nullptr; instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == target_reg) {
            // Case 1: Direct static field read (SGET of COROUTINE_SUSPENDED)
            if (op >= dex::OP_SGET && op <= dex::OP_SGET_SHORT) {
              for (auto* operand : bytecode->operands) {
                if (auto* field_operand = dynamic_cast<lir::Field*>(operand)) {
                  if (field_operand->ir_field != nullptr &&
                      field_operand->ir_field->parent != nullptr &&
                      field_operand->ir_field->parent->descriptor != nullptr) {
                    const char* parent_class = field_operand->ir_field->parent->descriptor->c_str();
                    const char* field_name = (field_operand->ir_field->name != nullptr) ? field_operand->ir_field->name->c_str() : "";
                    if (strcmp(field_name, "COROUTINE_SUSPENDED") == 0 &&
                        strcmp(parent_class, "Lkotlin/coroutines/intrinsics/CoroutineSingletons;") == 0) {
                      return true;
                    }
                  }
                }
              }
            }
            // Case 2: Static getter method read (MOVE_RESULT_OBJECT of getCOROUTINE_SUSPENDED)
            else if (op == dex::OP_MOVE_RESULT_OBJECT) {
              // Find the invoking instruction directly preceding the move-result-object
              if (instr->prev != nullptr) {
                if (auto* prev_bytecode = dynamic_cast<lir::Bytecode*>(instr->prev)) {
                  dex::Opcode prev_op = prev_bytecode->opcode;
                  if (prev_op >= dex::OP_INVOKE_VIRTUAL && prev_op <= dex::OP_INVOKE_INTERFACE_RANGE) {
                    for (auto* operand : prev_bytecode->operands) {
                      if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
                        if (method_operand->ir_method != nullptr &&
                            method_operand->ir_method->parent != nullptr &&
                            method_operand->ir_method->parent->descriptor != nullptr) {
                          const char* parent_class = method_operand->ir_method->parent->descriptor->c_str();
                          const char* p_method_name = (method_operand->ir_method->name != nullptr) ? method_operand->ir_method->name->c_str() : "";
                          if ((strcmp(parent_class, "Lkotlin/coroutines/intrinsics/IntrinsicsKt;") == 0 ||
                               strcmp(parent_class, "Lkotlin/coroutines/intrinsics/IntrinsicsKt__IntrinsicsJvmKt;") == 0) &&
                              strcmp(p_method_name, "getCOROUTINE_SUSPENDED") == 0) {
                            return true;
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            // If the register was written to by any other instruction, its value has been
            // redefined, meaning it no longer holds the coroutine singleton. Terminate trace.
            return false;
          }
        }
      }
    }
  }
  return false;
}

// Scans backward in the flat instruction stream to detect synthetic coroutine suspension checks
bool IsSyntheticInstructionFlow(lir::Instruction* branch_instr) {
  auto* bytecode = dynamic_cast<lir::Bytecode*>(branch_instr);
  if (bytecode == nullptr) {
    return false;
  }

  // Check if either of the register operands evaluated in the branch traces back to COROUTINE_SUSPENDED
  for (auto* operand : bytecode->operands) {
    if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
      if (TraceRegisterToCoroutineSuspended(branch_instr, vreg->reg)) {
        return true;
      }
    }
  }
  return false;
}

// Detects if a branch in invokeSuspend is part of the coroutine label state-machine setup
bool IsCoroutineSetupBranch(lir::Instruction* branch_instr) {
  for (auto* instr = branch_instr->prev; instr != nullptr; instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (op >= dex::OP_IGET && op <= dex::OP_IGET_SHORT) {
        for (auto* operand : bytecode->operands) {
          if (auto* field_operand = dynamic_cast<lir::Field*>(operand)) {
            if (field_operand->ir_field != nullptr &&
                field_operand->ir_field->name != nullptr &&
                field_operand->ir_field->type != nullptr &&
                field_operand->ir_field->type->descriptor != nullptr) {
              const char* field_name = field_operand->ir_field->name->c_str();
              const char* field_type = field_operand->ir_field->type->descriptor->c_str();
              if (strcmp(field_name, "label") == 0 && strcmp(field_type, "I") == 0) {
                return true;
              }
            }
          }
        }
      }
    }
  }
  return false;
}

} // namespace

bool SyntheticFilter::IsSyntheticBranch(ir::EncodedMethod* ir_method,
                                       const lir::BasicBlock& block) {
  if (ir_method == nullptr || block.region.last == nullptr) {
    return false;
  }

  auto* last_bytecode = dynamic_cast<lir::Bytecode*>(block.region.last);
  if (last_bytecode == nullptr) {
    return false;
  }

  auto flags = dex::GetFlagsFromOpcode(last_bytecode->opcode);
  bool is_candidate = (flags & dex::kBranch) || (flags & dex::kSwitch);
  if (!is_candidate) {
    return false;
  }

  // Detect coroutine launch lambda state-machine dispatch branches
  if (ir_method->decl != nullptr &&
      ir_method->decl->parent != nullptr &&
      ir_method->decl->parent->descriptor != nullptr &&
      ir_method->decl->name != nullptr) {
    const char* declaring_class = ir_method->decl->parent->descriptor->c_str();
    const char* method_name = ir_method->decl->name->c_str();
    if (strcmp(method_name, "invokeSuspend") == 0 &&
        strstr(declaring_class, "$") != nullptr) {
      if (IsCoroutineSetupBranch(last_bytecode)) {
        return true;
      }
    }
  }

  // Detect coroutine suspension checks
  if (IsSyntheticInstructionFlow(last_bytecode)) {
    return true;
  }

  return false;
}

}  // namespace coverage