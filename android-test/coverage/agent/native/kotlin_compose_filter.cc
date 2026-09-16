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
// substring matching).
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

// Checks if a register index corresponds to a $changed parameter (any parameter
// following the Composer)
bool IsChangedParamRegister(int reg, dex::u4 reg_composer) {
  return (reg > static_cast<int>(reg_composer));
}

// Traces a virtual register back to the $changed parameter registers (scanning
// all operand slots)
bool TraceRegisterToChangedParam(lir::Instruction* branch_instr, int target_reg,
                                 dex::u4 reg_composer) {
  if (IsChangedParamRegister(target_reg, reg_composer)) {
    return true;
  }

  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr;
       instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            bool target_updated = false;
            for (size_t i = 1; i < bytecode->operands.size(); ++i) {
              if (auto* src_vreg =
                      dynamic_cast<lir::VReg*>(bytecode->operands[i])) {
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

// Checks if a register index corresponds to the $default parameter (the very
// last parameter of the Composable method)
bool IsDefaultParamRegister(int reg, ir::EncodedMethod* ir_method) {
  if (ir_method == nullptr || ir_method->code == nullptr) {
    return false;
  }
  return (reg == static_cast<int>(ir_method->code->registers - 1));
}

// Traces a virtual register back to the $default parameter register (scanning
// all operand slots)
bool TraceRegisterToDefaultParam(lir::Instruction* branch_instr, int target_reg,
                                 ir::EncodedMethod* ir_method) {
  if (IsDefaultParamRegister(target_reg, ir_method)) {
    return true;
  }

  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr;
       instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            bool target_updated = false;
            for (size_t i = 1; i < bytecode->operands.size(); ++i) {
              if (auto* src_vreg =
                      dynamic_cast<lir::VReg*>(bytecode->operands[i])) {
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

// Traces a register back to any method call on the Composer parameter (boxed or
// primitive)
bool TraceRegisterToComposerGetSkipping(lir::Instruction* branch_instr,
                                        int target_reg) {
  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr;
       instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            if (op == dex::OP_MOVE_RESULT || op == dex::OP_MOVE_RESULT_OBJECT) {
              if (auto* prev_bytecode = GetPrevBytecode(instr)) {
                dex::Opcode prev_op = prev_bytecode->opcode;
                if (prev_op >= dex::OP_INVOKE_VIRTUAL &&
                    prev_op <= dex::OP_INVOKE_INTERFACE_RANGE) {
                  bool method_is_composer = false;
                  for (auto* operand : prev_bytecode->operands) {
                    if (auto* method_operand =
                            dynamic_cast<lir::Method*>(operand)) {
                      if (method_operand->ir_method != nullptr &&
                          method_operand->ir_method->parent != nullptr &&
                          method_operand->ir_method->parent->descriptor !=
                              nullptr) {
                        const char* parent_class =
                            method_operand->ir_method->parent->descriptor
                                ->c_str();
                        if (strstr(parent_class, "runtime/Composer") !=
                            nullptr) {
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
            // If the register was moved or copied, continue tracing the source
            // register backwards!
            else if (op >= dex::OP_MOVE && op <= dex::OP_MOVE_16) {
              if (bytecode->operands.size() >= 2) {
                if (auto* src_vreg =
                        dynamic_cast<lir::VReg*>(bytecode->operands[1])) {
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

// Traces a register back to an endRestartGroup() method call on the Composer
// parameter
bool TraceRegisterToComposerEndRestartGroup(lir::Instruction* branch_instr,
                                            int target_reg) {
  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr;
       instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            if (op == dex::OP_MOVE_RESULT_OBJECT) {
              if (auto* prev_bytecode = GetPrevBytecode(instr)) {
                dex::Opcode prev_op = prev_bytecode->opcode;
                if (prev_op >= dex::OP_INVOKE_VIRTUAL &&
                    prev_op <= dex::OP_INVOKE_INTERFACE_RANGE) {
                  bool method_is_end_restart_group = false;
                  for (auto* operand : prev_bytecode->operands) {
                    if (auto* method_operand =
                            dynamic_cast<lir::Method*>(operand)) {
                      if (method_operand->ir_method != nullptr &&
                          method_operand->ir_method->name != nullptr &&
                          method_operand->ir_method->parent != nullptr &&
                          method_operand->ir_method->parent->descriptor !=
                              nullptr) {
                        const char* parent_class =
                            method_operand->ir_method->parent->descriptor
                                ->c_str();
                        const char* m_name =
                            method_operand->ir_method->name->c_str();
                        if (strstr(parent_class, "runtime/Composer") !=
                                nullptr &&
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
                if (auto* src_vreg =
                        dynamic_cast<lir::VReg*>(bytecode->operands[1])) {
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

// Traces a register back to a ComposerKt.isTraceInProgress() method call
// (universally suppresses debug-tracing branches)
bool TraceRegisterToComposerIsTraceInProgress(lir::Instruction* branch_instr,
                                              int target_reg) {
  int current_target = target_reg;

  for (auto* instr = branch_instr->prev; instr != nullptr;
       instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (bytecode->operands.size() > 0) {
        if (auto* dest_vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
          if (dest_vreg->reg == current_target) {
            if (op == dex::OP_MOVE_RESULT) {
              if (auto* prev_bytecode = GetPrevBytecode(instr)) {
                dex::Opcode prev_op = prev_bytecode->opcode;
                if (prev_op == dex::OP_INVOKE_STATIC ||
                    prev_op == dex::OP_INVOKE_STATIC_RANGE) {
                  bool method_is_trace_in_progress = false;
                  for (auto* operand : prev_bytecode->operands) {
                    if (auto* method_operand =
                            dynamic_cast<lir::Method*>(operand)) {
                      if (method_operand->ir_method != nullptr &&
                          method_operand->ir_method->name != nullptr &&
                          method_operand->ir_method->parent != nullptr &&
                          method_operand->ir_method->parent->descriptor !=
                              nullptr) {
                        const char* parent_class =
                            method_operand->ir_method->parent->descriptor
                                ->c_str();
                        const char* m_name =
                            method_operand->ir_method->name->c_str();
                        if (strstr(parent_class, "runtime/ComposerKt") !=
                                nullptr &&
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
                if (auto* src_vreg =
                        dynamic_cast<lir::VReg*>(bytecode->operands[1])) {
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

}  // namespace

bool KotlinComposeFilter::FilterBranch(ir::EncodedMethod* ir_method,
                                       lir::Instruction* branch_instr) {
  auto* bytecode = dynamic_cast<lir::Bytecode*>(branch_instr);
  if (bytecode == nullptr) {
    return false;
  }

  // Detect Jetpack Compose default parameter branches ($default checks) - Only
  // run if Composable
  dex::u4 reg_composer = 0;
  bool has_composer = FindComposerRegister(ir_method, reg_composer);

  if (has_composer) {
    for (auto* operand : bytecode->operands) {
      if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
        if (TraceRegisterToDefaultParam(bytecode, vreg->reg, ir_method)) {
          return true;
        }
      }
    }
  }

  // Detect Jetpack Compose recomposition branch ($changed checks)
  if (has_composer) {
    for (auto* operand : bytecode->operands) {
      if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
        if (TraceRegisterToChangedParam(bytecode, vreg->reg, reg_composer)) {
          return true;
        }
      }
    }
  }

  // Detect getSkipping() or shouldExecute() branch check (run universally)
  for (auto* operand : bytecode->operands) {
    if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
      if (TraceRegisterToComposerGetSkipping(bytecode, vreg->reg)) {
        return true;
      }
    }
  }

  // Detect endRestartGroup() branch check (run universally)
  for (auto* operand : bytecode->operands) {
    if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
      if (TraceRegisterToComposerEndRestartGroup(bytecode, vreg->reg)) {
        return true;
      }
    }
  }

  // Detect isTraceInProgress() branch check (run universally)
  for (auto* operand : bytecode->operands) {
    if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
      if (TraceRegisterToComposerIsTraceInProgress(bytecode, vreg->reg)) {
        return true;
      }
    }
  }

  return false;
}

REGISTER_FILTER(KotlinComposeFilter)

}  // namespace coverage
