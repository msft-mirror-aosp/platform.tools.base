#include "synthetic_filter.h"
#include "slicer/dex_bytecode.h"
#include <algorithm>
#include <cstring>
#include <vector>

namespace coverage {

namespace {

// Helper to find the first preceding bytecode instruction, skipping non-bytecode metadata/debug elements
lir::Bytecode* GetPrevBytecode(lir::Instruction* instr) {
  if (instr == nullptr) return nullptr;
  for (auto* p = instr->prev; p != nullptr; p = p->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(p)) {
      return bytecode;
    }
  }
  return nullptr;
}

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

// Finds the virtual register index of the Composer parameter (using descriptor substring matching).
bool FindComposerRegister(ir::EncodedMethod* ir_method, dex::u4& reg_composer) {
  if (ir_method == nullptr || ir_method->code == nullptr || ir_method->decl == nullptr || ir_method->decl->prototype == nullptr) {
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
      // Perform a substring search to be fully immune to obfuscation or custom package annotations
      if (descriptor != nullptr && strstr(descriptor, "Composer;") != nullptr) {
        reg_composer = current_reg;
        return true;
      }
    }
  }

  return false;
}

// Checks if a register index corresponds to a $changed parameter (any parameter following the Composer)
bool IsChangedParamRegister(int reg, dex::u4 reg_composer) {
  return (reg > static_cast<int>(reg_composer));
}

// Traces a virtual register back to the $changed parameter registers (scanning all operand slots)
bool TraceRegisterToChangedParam(lir::Instruction* branch_instr, int target_reg, dex::u4 reg_composer) {
  if (IsChangedParamRegister(target_reg, reg_composer)) {
    return true;
  }

  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr; instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            bool target_updated = false;
            for (size_t i = 1; i < bytecode->operands.size(); ++i) {
              if (auto* src_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[i])) {
                int src_reg = src_vreg->reg;
                if (IsChangedParamRegister(src_reg, reg_composer)) {
                  return true;
                }
                if (!target_updated) {
                  current_target = src_reg;
                  target_updated = true;
                }
              }
            }
            if (target_updated) {
              continue;
            }
            return false;
          }
        }
      }
    }
  }
  return false;
}

// Checks if a register index corresponds to the $default parameter (the very last parameter of the Composable method)
bool IsDefaultParamRegister(int reg, ir::EncodedMethod* ir_method) {
  if (ir_method == nullptr || ir_method->code == nullptr) {
    return false;
  }
  return (reg == static_cast<int>(ir_method->code->registers - 1));
}

// Traces a virtual register back to the $default parameter register (scanning all operand slots)
bool TraceRegisterToDefaultParam(lir::Instruction* branch_instr, int target_reg, ir::EncodedMethod* ir_method) {
  if (IsDefaultParamRegister(target_reg, ir_method)) {
    return true;
  }

  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr; instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            bool target_updated = false;
            for (size_t i = 1; i < bytecode->operands.size(); ++i) {
              if (auto* src_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[i])) {
                int src_reg = src_vreg->reg;
                if (IsDefaultParamRegister(src_reg, ir_method)) {
                  return true;
                }
                if (!target_updated) {
                  current_target = src_reg;
                  target_updated = true;
                }
              }
            }
            if (target_updated) {
              continue;
            }
            return false;
          }
        }
      }
    }
  }
  return false;
}

// Traces a register back to any method call on the Composer parameter (boxed or primitive)
bool TraceRegisterToComposerGetSkipping(lir::Instruction* branch_instr, int target_reg) {
  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr; instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            if (op == dex::OP_MOVE_RESULT || op == dex::OP_MOVE_RESULT_OBJECT) {
              if (auto* prev_bytecode = GetPrevBytecode(instr)) {
                dex::Opcode prev_op = prev_bytecode->opcode;
                if (prev_op >= dex::OP_INVOKE_VIRTUAL && prev_op <= dex::OP_INVOKE_INTERFACE_RANGE) {
                  bool method_is_composer = false;
                  for (auto* operand : prev_bytecode->operands) {
                    if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
                      if (method_operand->ir_method != nullptr &&
                          method_operand->ir_method->parent != nullptr &&
                          method_operand->ir_method->parent->descriptor != nullptr) {
                        const char* parent_class = method_operand->ir_method->parent->descriptor->c_str();
                        if (strstr(parent_class, "runtime/Composer") != nullptr) {
                          method_is_composer = true;
                        }
                      }
                    }
                  }
                  if (method_is_composer) {
                    return true;
                  }
                }
              }
            }
            // If the register was moved or copied, continue tracing the source register backwards!
            else if (op >= dex::OP_MOVE && op <= dex::OP_MOVE_16) {
              if (bytecode->operands.size() >= 2) {
                if (auto* src_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[1])) {
                  current_target = src_vreg->reg;
                  continue;
                }
              }
            }
            return false;
          }
        }
      }
    }
  }
  return false;
}

// Traces a register back to an endRestartGroup() method call on the Composer parameter
bool TraceRegisterToComposerEndRestartGroup(lir::Instruction* branch_instr, int target_reg) {
  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr; instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            if (op == dex::OP_MOVE_RESULT_OBJECT) {
              if (auto* prev_bytecode = GetPrevBytecode(instr)) {
                dex::Opcode prev_op = prev_bytecode->opcode;
                if (prev_op >= dex::OP_INVOKE_VIRTUAL && prev_op <= dex::OP_INVOKE_INTERFACE_RANGE) {
                  bool method_is_end_restart_group = false;
                  for (auto* operand : prev_bytecode->operands) {
                    if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
                      if (method_operand->ir_method != nullptr &&
                          method_operand->ir_method->name != nullptr &&
                          method_operand->ir_method->parent != nullptr &&
                          method_operand->ir_method->parent->descriptor != nullptr) {
                        const char* parent_class = method_operand->ir_method->parent->descriptor->c_str();
                        const char* m_name = method_operand->ir_method->name->c_str();
                        if (strstr(parent_class, "runtime/Composer") != nullptr &&
                            strstr(m_name, "endRestartGroup") != nullptr) {
                          method_is_end_restart_group = true;
                        }
                      }
                    }
                  }
                  if (method_is_end_restart_group) {
                    return true;
                  }
                }
              }
            }
            // Continue tracing backwards if register is copied
            else if (op >= dex::OP_MOVE && op <= dex::OP_MOVE_16) {
              if (bytecode->operands.size() >= 2) {
                if (auto* src_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[1])) {
                  current_target = src_vreg->reg;
                  continue;
                }
              }
            }
            return false;
          }
        }
      }
    }
  }
  return false;
}

// Traces a register back to a ComposerKt.isTraceInProgress() method call (universally suppresses debug-tracing branches)
bool TraceRegisterToComposerIsTraceInProgress(lir::Instruction* branch_instr, int target_reg) {
  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr; instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            if (op == dex::OP_MOVE_RESULT) {
              if (auto* prev_bytecode = GetPrevBytecode(instr)) {
                dex::Opcode prev_op = prev_bytecode->opcode;
                if (prev_op == dex::OP_INVOKE_STATIC || prev_op == dex::OP_INVOKE_STATIC_RANGE) {
                  bool method_is_trace_in_progress = false;
                  for (auto* operand : prev_bytecode->operands) {
                    if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
                      if (method_operand->ir_method != nullptr &&
                          method_operand->ir_method->name != nullptr &&
                          method_operand->ir_method->parent != nullptr &&
                          method_operand->ir_method->parent->descriptor != nullptr) {
                        const char* parent_class = method_operand->ir_method->parent->descriptor->c_str();
                        const char* m_name = method_operand->ir_method->name->c_str();
                        if (strstr(parent_class, "runtime/ComposerKt") != nullptr &&
                            strstr(m_name, "isTraceInProgress") != nullptr) {
                          method_is_trace_in_progress = true;
                        }
                      }
                    }
                  }
                  if (method_is_trace_in_progress) {
                    return true;
                  }
                }
              }
            }
            // Continue tracing backwards if register is copied
            else if (op >= dex::OP_MOVE && op <= dex::OP_MOVE_16) {
              if (bytecode->operands.size() >= 2) {
                if (auto* src_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[1])) {
                  current_target = src_vreg->reg;
                  continue;
                }
              }
            }
            return false;
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

  // Detect Jetpack Compose default parameter branches ($default checks) - Only run if Composable
  dex::u4 reg_composer = 0;
  bool has_composer = FindComposerRegister(ir_method, reg_composer);

  if (has_composer) {
    for (auto* operand : last_bytecode->operands) {
      if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
        if (TraceRegisterToDefaultParam(last_bytecode, vreg->reg, ir_method)) {
          return true;
        }
      }
    }
  }

  // Detect Jetpack Compose recomposition branch ($changed checks)
  if (has_composer) {
    for (auto* operand : last_bytecode->operands) {
      if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
        if (TraceRegisterToChangedParam(last_bytecode, vreg->reg, reg_composer)) {
          return true;
        }
      }
    }
  }

  // Detect getSkipping() or shouldExecute() branch check (run universally)
  for (auto* operand : last_bytecode->operands) {
    if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
      if (TraceRegisterToComposerGetSkipping(last_bytecode, vreg->reg)) {
        return true;
      }
    }
  }

  // Detect endRestartGroup() branch check (run universally)
  for (auto* operand : last_bytecode->operands) {
    if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
      if (TraceRegisterToComposerEndRestartGroup(last_bytecode, vreg->reg)) {
        return true;
      }
    }
  }

  // Detect isTraceInProgress() branch check (run universally)
  for (auto* operand : last_bytecode->operands) {
    if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
      if (TraceRegisterToComposerIsTraceInProgress(last_bytecode, vreg->reg)) {
        return true;
      }
    }
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
