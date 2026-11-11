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

#include "tools/base/android-test/coverage/agent/native/kotlin_safe_call_operator_filter.h"

#include <algorithm>
#include <cstddef>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

// Returns the last bytecode before `instr`, or nullptr.
lir::Bytecode* PreviousBytecode(lir::Instruction* instr) {
  for (auto* node = instr->prev; node != nullptr; node = node->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(node)) {
      return bytecode;
    }
  }
  return nullptr;
}

// Returns true if a label lies between `from` and `to`, so that other code can
// jump in between.
bool LabelBetween(const lir::Instruction* from, const lir::Instruction* to) {
  for (auto* node = from->next; node != nullptr && node != to;
       node = node->next) {
    if (dynamic_cast<const lir::Label*>(node) != nullptr) {
      return true;
    }
  }
  return false;
}

// Returns the target of a branch instruction, or nullptr for other
// instructions.
lir::Label* BranchTarget(const lir::Bytecode* bytecode) {
  if ((dex::GetFlagsFromOpcode(bytecode->opcode) & dex::kBranch) == 0) {
    return nullptr;
  }
  for (auto* operand : bytecode->operands) {
    if (auto* location = dynamic_cast<lir::CodeLocation*>(operand)) {
      return location->label;
    }
  }
  return nullptr;
}

bool IsGoto(const lir::Bytecode* bytecode) {
  return (dex::GetFlagsFromOpcode(bytecode->opcode) & dex::kUnconditional) != 0;
}

// Returns true if no instruction runs right after `bytecode`.
bool EndsFlow(const lir::Bytecode* bytecode) {
  return (dex::GetFlagsFromOpcode(bytecode->opcode) & dex::kContinue) == 0;
}

// Returns true if `opcode` writes the register of its first operand.
bool WritesFirstOperand(dex::Opcode opcode) {
  if (dex::GetFlagsFromOpcode(opcode) &
      (dex::kBranch | dex::kSwitch | dex::kReturn | dex::kInvoke)) {
    return false;
  }
  switch (opcode) {
    case dex::OP_NOP:
    case dex::OP_MONITOR_ENTER:
    case dex::OP_MONITOR_EXIT:
    case dex::OP_FILLED_NEW_ARRAY:
    case dex::OP_FILLED_NEW_ARRAY_RANGE:
    case dex::OP_FILL_ARRAY_DATA:
    case dex::OP_THROW:
      return false;
    default:
      break;
  }
  // Stores read the register of their first operand.
  return !(opcode >= dex::OP_APUT && opcode <= dex::OP_APUT_SHORT) &&
         !(opcode >= dex::OP_IPUT && opcode <= dex::OP_IPUT_SHORT) &&
         !(opcode >= dex::OP_SPUT && opcode <= dex::OP_SPUT_SHORT);
}

// Returns true if `opcode` reads the register of its first operand.
bool ReadsFirstOperand(dex::Opcode opcode) {
  // check-cast and the 2addr operations also write it.
  return !WritesFirstOperand(opcode) || opcode == dex::OP_CHECK_CAST ||
         (opcode >= dex::OP_ADD_INT_2ADDR &&
          opcode <= dex::OP_REM_DOUBLE_2ADDR);
}

// Returns true if `operand` names register `reg`.
bool Names(const lir::Operand* operand, dex::u4 reg) {
  if (auto* vreg = dynamic_cast<const lir::VReg*>(operand)) {
    return vreg->reg == reg;
  }
  if (auto* pair = dynamic_cast<const lir::VRegPair*>(operand)) {
    return pair->base_reg == reg || pair->base_reg + 1 == reg;
  }
  if (auto* list = dynamic_cast<const lir::VRegList*>(operand)) {
    return std::find(list->registers.begin(), list->registers.end(), reg) !=
           list->registers.end();
  }
  if (auto* range = dynamic_cast<const lir::VRegRange*>(operand)) {
    return reg >= range->base_reg &&
           reg < range->base_reg + static_cast<dex::u4>(range->count);
  }
  return false;
}

// Returns true if `bytecode` reads register `reg`.
bool Reads(const lir::Bytecode* bytecode, dex::u4 reg) {
  for (size_t i = 0; i < bytecode->operands.size(); ++i) {
    if ((i > 0 || ReadsFirstOperand(bytecode->opcode)) &&
        Names(bytecode->operands[i], reg)) {
      return true;
    }
  }
  return false;
}

// Returns true if `bytecode` writes register `reg`.
bool Writes(const lir::Bytecode* bytecode, dex::u4 reg) {
  return WritesFirstOperand(bytecode->opcode) && !bytecode->operands.empty() &&
         Names(bytecode->operands[0], reg);
}

// Returns true if `bytecode` ends a block: it branches, switches, returns or
// throws.
bool EndsBlock(const lir::Bytecode* bytecode) {
  return (dex::GetFlagsFromOpcode(bytecode->opcode) &
          (dex::kBranch | dex::kSwitch)) != 0 ||
         EndsFlow(bytecode);
}

// Returns true if the code right after `from` reads `reg` before that code
// ends, overwrites `reg` or reaches a label, where other code joins it.
bool ReadsNext(const lir::Instruction* from, dex::u4 reg) {
  for (auto* node = from->next; node != nullptr; node = node->next) {
    if (dynamic_cast<const lir::Label*>(node) != nullptr) {
      return false;
    }
    auto* bytecode = dynamic_cast<const lir::Bytecode*>(node);
    if (bytecode == nullptr) {
      continue;
    }
    if (Reads(bytecode, reg)) {
      return true;
    }
    if (EndsBlock(bytecode) || Writes(bytecode, reg)) {
      return false;
    }
  }
  return false;
}

// Returns true if the code that can run after `from` reads `reg` before
// writing it, following jumps but not switch cases or exception handlers.
bool IsLive(lir::Instruction* from, dex::u4 reg) {
  std::vector<lir::Instruction*> starts = {from};
  std::unordered_set<lir::Instruction*> visited = {from};
  while (!starts.empty()) {
    lir::Instruction* start = starts.back();
    starts.pop_back();
    for (auto* node = start->next; node != nullptr; node = node->next) {
      if (dynamic_cast<lir::Label*>(node) != nullptr) {
        if (!visited.insert(node).second) {
          break;
        }
        continue;
      }
      auto* bytecode = dynamic_cast<lir::Bytecode*>(node);
      if (bytecode == nullptr) {
        continue;
      }
      if (Reads(bytecode, reg)) {
        return true;
      }
      if (Writes(bytecode, reg)) {
        break;
      }
      lir::Label* target = BranchTarget(bytecode);
      if (target != nullptr && visited.insert(target).second) {
        starts.push_back(target);
      }
      if (EndsFlow(bytecode)) {
        break;
      }
    }
  }
  return false;
}

// Returns true if the agent probes the block that starts at `label`: it probes
// the blocks with an instruction other than nop, move and check-cast. Like the
// blocks of the agent, the block ends before the next label, try block boundary
// or payload.
bool IsProbed(const lir::Label* label) {
  for (auto* node = label->next; node != nullptr; node = node->next) {
    if (dynamic_cast<const lir::DbgInfoAnnotation*>(node) != nullptr) {
      continue;
    }
    auto* bytecode = dynamic_cast<const lir::Bytecode*>(node);
    if (bytecode == nullptr) {
      return false;
    }
    dex::Opcode opcode = bytecode->opcode;
    if (opcode != dex::OP_NOP && opcode != dex::OP_CHECK_CAST &&
        !(opcode >= dex::OP_MOVE && opcode <= dex::OP_MOVE_OBJECT_16)) {
      return true;
    }
  }
  return false;
}

// Where a label or a branch instruction is: its index in the code, and the
// source line of the code there.
struct Location {
  size_t index = 0;
  int line = -1;
};

// The instructions that lead to a label.
struct Entries {
  // The branch instructions that jump to it.
  std::vector<lir::Bytecode*> jumps;
  // The null checks of safe calls among them, in code order.
  std::vector<lir::Bytecode*> checks;
  // True if a switch or a catch handler also leads to it.
  bool other_entry = false;
};

}  // namespace

void KotlinSafeCallOperatorFilter::Filter(const FilterContext& context,
                                          ir::EncodedMethod* ir_method,
                                          lir::CodeIr& code_ir,
                                          FilterOutput& output) {
  std::unordered_map<const lir::Instruction*, Location> locations;
  std::unordered_map<lir::Label*, Entries> entries;
  // The labels that null checks jump to, in code order.
  std::vector<lir::Label*> null_targets;
  size_t index = 0;
  int line = -1;
  for (auto* instr : code_ir.instructions) {
    ++index;
    if (auto* annotation = dynamic_cast<lir::DbgInfoAnnotation*>(instr)) {
      if (annotation->dbg_opcode == dex::DBG_ADVANCE_LINE &&
          !annotation->operands.empty()) {
        if (auto* number =
                dynamic_cast<lir::LineNumber*>(annotation->operands[0])) {
          line = number->line;
        }
      }
      continue;
    }
    if (dynamic_cast<lir::Label*>(instr) != nullptr) {
      locations[instr] = {index, line};
      continue;
    }
    if (auto* end = dynamic_cast<lir::TryBlockEnd*>(instr)) {
      for (const auto& handler : end->handlers) {
        entries[handler.label].other_entry = true;
      }
      if (end->catch_all != nullptr) {
        entries[end->catch_all].other_entry = true;
      }
      continue;
    }
    if (auto* packed = dynamic_cast<lir::PackedSwitchPayload*>(instr)) {
      for (auto* target : packed->targets) {
        entries[target].other_entry = true;
      }
      continue;
    }
    if (auto* sparse = dynamic_cast<lir::SparseSwitchPayload*>(instr)) {
      for (const auto& switch_case : sparse->switch_cases) {
        entries[switch_case.target].other_entry = true;
      }
      continue;
    }
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    lir::Label* target = bytecode == nullptr ? nullptr : BranchTarget(bytecode);
    if (target == nullptr) {
      continue;
    }
    locations[bytecode] = {index, line};
    Entries& target_entries = entries[target];
    target_entries.jumps.push_back(bytecode);
    if (bytecode->opcode != dex::OP_IF_EQZ) {
      continue;
    }
    // A safe call uses the value that it checks.
    auto* reg = dynamic_cast<lir::VReg*>(bytecode->operands[0]);
    if (reg != nullptr && ReadsNext(bytecode, reg->reg)) {
      if (target_entries.checks.empty()) {
        null_targets.push_back(target);
      }
      target_entries.checks.push_back(bytecode);
    }
  }

  // All the checks of a chain of safe calls jump to the same code, which runs
  // when one of the values is null, so each of them already has that code as
  // its null branch. So does the check before an elvis operator (?:) when it
  // falls through to that code. When it is followed by a goto to that code
  // instead, its null branch is the goto, which runs only when the last value
  // is null. That branch is replaced with the code of the chain, unless that
  // code also runs when no value is null.
  for (lir::Label* target : null_targets) {
    const Entries& target_entries = entries[target];
    if (target_entries.other_entry) {
      continue;
    }
    // The check of the elvis operator comes after the checks of the chain.
    lir::Bytecode* elvis = nullptr;
    lir::Bytecode* goto_to_target = nullptr;
    size_t last_check = locations[target_entries.checks.back()].index;
    for (lir::Bytecode* jump : target_entries.jumps) {
      lir::Bytecode* previous = PreviousBytecode(jump);
      if (IsGoto(jump) && previous != nullptr &&
          previous->opcode == dex::OP_IF_NEZ && !LabelBetween(previous, jump) &&
          locations[jump].index > last_check) {
        elvis = previous;
        goto_to_target = jump;
        break;
      }
    }
    if (elvis == nullptr) {
      continue;
    }
    // Only the checks of the chain and the goto lead to the code: nothing else
    // jumps or falls through to it. The code also comes after the goto, so
    // that the agent probes it whenever it probes the check of the elvis
    // operator: in constructors, it doesn't probe the code before the call to
    // the super constructor. And the agent must probe it.
    bool only_null_cases =
        std::all_of(target_entries.jumps.begin(), target_entries.jumps.end(),
                    [&](lir::Bytecode* jump) {
                      return jump == goto_to_target ||
                             std::find(target_entries.checks.begin(),
                                       target_entries.checks.end(),
                                       jump) != target_entries.checks.end();
                    });
    lir::Bytecode* before = PreviousBytecode(target);
    if (!only_null_cases ||
        locations[target].index < locations[goto_to_target].index ||
        before == nullptr || !EndsFlow(before) || !IsProbed(target)) {
      continue;
    }
    // Null checks written by hand can have the same code. In
    // `a == null || a.b == null`, the value of a.b isn't used after its check,
    // while the value of an elvis operator is. And the condition of
    // `while (a != null)` is on another line than an elvis operator in the
    // loop, while a chain is on one line.
    auto* value = dynamic_cast<lir::VReg*>(elvis->operands[0]);
    int elvis_line = locations[elvis].line;
    bool one_line =
        std::all_of(target_entries.checks.begin(), target_entries.checks.end(),
                    [&](lir::Bytecode* check) {
                      return locations[check].line == elvis_line;
                    });
    if (value == nullptr || !IsLive(BranchTarget(elvis), value->reg) ||
        !one_line) {
      continue;
    }
    output.ReplaceBranches(elvis, {target, BranchTarget(elvis)});
    // The goto runs only when the last value is null, which the null branch
    // now covers.
    output.Ignore(goto_to_target, goto_to_target);
  }
}

REGISTER_KOTLIN_FILTER(KotlinSafeCallOperatorFilter)

}  // namespace coverage
