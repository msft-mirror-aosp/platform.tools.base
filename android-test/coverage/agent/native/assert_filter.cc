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

#include "tools/base/android-test/coverage/agent/native/assert_filter.h"

#include <algorithm>
#include <cstddef>
#include <iterator>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr std::string_view kStaticInitializer = "<clinit>";
constexpr std::string_view kAssertionsDisabled = "$assertionsDisabled";
constexpr std::string_view kClass = "Ljava/lang/Class;";
constexpr std::string_view kDesiredAssertionStatus = "desiredAssertionStatus";

// The registers that hold the value of $assertionsDisabled, or the status that
// the static initializer negates for it.
using Registers = std::set<int>;

// Returns the register of operand `index` of `bytecode`, or -1.
int Register(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return -1;
  }
  auto* reg = dynamic_cast<const lir::VReg*>(bytecode->operands[index]);
  return reg == nullptr ? -1 : static_cast<int>(reg->reg);
}

// Returns true if the operand `index` of `bytecode` is the literal `value`.
bool IsLiteral(const lir::Bytecode* bytecode, size_t index, dex::s4 value) {
  if (index >= bytecode->operands.size()) {
    return false;
  }
  auto* literal = dynamic_cast<const lir::Const32*>(bytecode->operands[index]);
  return literal != nullptr && literal->u.s4_value == value;
}

// Returns true if `bytecode` is `opcode` on the field $assertionsDisabled of
// the class `class_name`, a descriptor.
bool AccessesAssertionsDisabled(const lir::Bytecode* bytecode,
                                dex::Opcode opcode,
                                std::string_view class_name) {
  if (bytecode->opcode != opcode || bytecode->operands.size() < 2) {
    return false;
  }
  auto* field = dynamic_cast<const lir::Field*>(bytecode->operands[1]);
  const ir::FieldDecl* decl = field == nullptr ? nullptr : field->ir_field;
  return decl != nullptr && decl->name != nullptr && decl->parent != nullptr &&
         decl->parent->descriptor != nullptr &&
         std::string_view(decl->name->c_str()) == kAssertionsDisabled &&
         std::string_view(decl->parent->descriptor->c_str()) == class_name;
}

// Returns the register that the invoke `bytecode` passes as its only argument,
// in a list or a range, or -1.
int OnlyArgument(const lir::Bytecode* bytecode) {
  if (bytecode->operands.empty()) {
    return -1;
  }
  if (auto* list = dynamic_cast<const lir::VRegList*>(bytecode->operands[0])) {
    return list->registers.size() == 1 ? static_cast<int>(list->registers[0])
                                       : -1;
  }
  if (auto* range =
          dynamic_cast<const lir::VRegRange*>(bytecode->operands[0])) {
    return range->count == 1 ? static_cast<int>(range->base_reg) : -1;
  }
  return -1;
}

// Returns true if `bytecode` calls Class.desiredAssertionStatus() on a class
// that the bytecode `previous` loads.
bool AsksAssertionStatus(const lir::Bytecode* previous,
                         const lir::Bytecode* bytecode) {
  if (previous->opcode != dex::OP_CONST_CLASS ||
      (bytecode->opcode != dex::OP_INVOKE_VIRTUAL &&
       bytecode->opcode != dex::OP_INVOKE_VIRTUAL_RANGE) ||
      bytecode->operands.size() < 2) {
    return false;
  }
  auto* method = dynamic_cast<const lir::Method*>(bytecode->operands[1]);
  const ir::MethodDecl* decl = method == nullptr ? nullptr : method->ir_method;
  return OnlyArgument(bytecode) == Register(previous, 0) && decl != nullptr &&
         decl->name != nullptr && decl->parent != nullptr &&
         decl->parent->descriptor != nullptr && decl->prototype != nullptr &&
         std::string_view(decl->name->c_str()) == kDesiredAssertionStatus &&
         std::string_view(decl->parent->descriptor->c_str()) == kClass &&
         decl->prototype->Signature() == "()Z";
}

// Returns true if register `reg` holds the int 1 at `bytecode`: the last
// bytecode before it with `reg` as its first operand, in the code that runs
// straight to it, loads 1.
bool HoldsOne(const lir::Bytecode* bytecode, int reg) {
  for (const lir::Instruction* node = bytecode->prev; node != nullptr;
       node = node->prev) {
    if (dynamic_cast<const lir::Label*>(node) != nullptr) {
      return false;
    }
    auto* previous = dynamic_cast<const lir::Bytecode*>(node);
    if (previous != nullptr && Register(previous, 0) == reg) {
      // Only the bytecodes that load a constant take a literal second.
      return IsLiteral(previous, 1, 1);
    }
  }
  return false;
}

// Returns the index in `bytecodes` of the sput-boolean that sets the field
// $assertionsDisabled of `class_name` to the negation of the status that the
// bytecode at `index` asks for, if any. D8 turns the branch of javac into a
// xor with 1: a literal, or a register that it loads right before or shares
// with earlier code.
std::optional<size_t> SetsAssertionsDisabled(
    const std::vector<lir::Bytecode*>& bytecodes, size_t index,
    std::string_view class_name) {
  if (index + 3 >= bytecodes.size() ||
      bytecodes[index + 1]->opcode != dex::OP_MOVE_RESULT) {
    return std::nullopt;
  }
  const int status = Register(bytecodes[index + 1], 0);
  size_t next = index + 2;
  int negation = -1;
  const lir::Bytecode* bytecode = bytecodes[next];
  if (bytecode->opcode == dex::OP_XOR_INT_LIT8 &&
      Register(bytecode, 1) == status && IsLiteral(bytecode, 2, 1)) {
    negation = Register(bytecode, 0);
  } else {
    const lir::Bytecode* constant = nullptr;
    if (bytecode->opcode == dex::OP_CONST_4) {
      constant = bytecode;
      bytecode = bytecodes[++next];
    }
    if (bytecode->opcode == dex::OP_XOR_INT_2ADDR &&
        Register(bytecode, 0) == status &&
        (constant == nullptr ||
         Register(constant, 0) == Register(bytecode, 1)) &&
        HoldsOne(bytecode, Register(bytecode, 1))) {
      negation = status;
    }
  }
  ++next;
  if (negation == -1 || next >= bytecodes.size() ||
      !AccessesAssertionsDisabled(bytecodes[next], dex::OP_SPUT_BOOLEAN,
                                  class_name) ||
      Register(bytecodes[next], 0) != negation) {
    return std::nullopt;
  }
  return next;
}

// Returns true if `opcode` copies an int from one register to another.
bool IsMove(dex::Opcode opcode) {
  return opcode >= dex::OP_MOVE && opcode <= dex::OP_MOVE_16;
}

// Returns the registers in both `a` and `b`.
Registers Intersect(const Registers& a, const Registers& b) {
  Registers both;
  std::set_intersection(a.begin(), a.end(), b.begin(), b.end(),
                        std::inserter(both, both.end()));
  return both;
}

// Returns the labels that `bytecode` jumps to: the target of a branch, or the
// cases of a switch, or nullopt if they aren't found.
std::optional<std::vector<const lir::Label*>> Jumps(
    const lir::Bytecode* bytecode) {
  const dex::OpcodeFlags flags = dex::GetFlagsFromOpcode(bytecode->opcode);
  if ((flags & (dex::kBranch | dex::kSwitch)) == 0) {
    return std::vector<const lir::Label*>();
  }
  auto* location =
      bytecode->operands.empty()
          ? nullptr
          : dynamic_cast<const lir::CodeLocation*>(bytecode->operands.back());
  if (location == nullptr || location->label == nullptr) {
    return std::nullopt;
  }
  if ((flags & dex::kBranch) != 0) {
    return std::vector<const lir::Label*>{location->label};
  }
  // A switch refers to the label of its payload.
  const lir::Instruction* payload = location->label->next;
  while (dynamic_cast<const lir::DbgInfoAnnotation*>(payload) != nullptr) {
    payload = payload->next;
  }
  std::vector<const lir::Label*> cases;
  if (auto* packed = dynamic_cast<const lir::PackedSwitchPayload*>(payload)) {
    cases.assign(packed->targets.begin(), packed->targets.end());
  } else if (auto* sparse =
                 dynamic_cast<const lir::SparseSwitchPayload*>(payload)) {
    for (const auto& switch_case : sparse->switch_cases) {
      cases.push_back(switch_case.target);
    }
  } else {
    return std::nullopt;
  }
  return cases;
}

// Returns the labels where the code before them doesn't tell the registers:
// the exception handlers, and the labels that later code jumps back to, as at
// the start of a loop, in which D8 reads the field again. Returns nullopt if
// the jumps of a bytecode aren't found.
std::optional<std::set<const lir::Label*>> UnknownLabels(
    const lir::CodeIr& code_ir) {
  std::set<const lir::Label*> unknown;
  std::set<const lir::Label*> placed;
  for (const lir::Instruction* instr : code_ir.instructions) {
    if (auto* label = dynamic_cast<const lir::Label*>(instr)) {
      placed.insert(label);
    } else if (auto* bytecode = dynamic_cast<const lir::Bytecode*>(instr)) {
      std::optional<std::vector<const lir::Label*>> targets = Jumps(bytecode);
      if (!targets) {
        return std::nullopt;
      }
      for (const lir::Label* target : *targets) {
        if (placed.count(target) != 0) {
          unknown.insert(target);
        }
      }
    } else if (auto* end = dynamic_cast<const lir::TryBlockEnd*>(instr)) {
      for (const lir::CatchHandler& handler : end->handlers) {
        unknown.insert(handler.label);
      }
      if (end->catch_all != nullptr) {
        unknown.insert(end->catch_all);
      }
    }
  }
  return unknown;
}

// Ignores the branches on the value of $assertionsDisabled of `class_name`,
// which each statement reads, and on the status that the static initializer
// negates for it, which the bytecodes `statuses` move to a register. D8 can
// keep either in a register for later statements, which only branch then.
void IgnoreBranches(const lir::CodeIr& code_ir, std::string_view class_name,
                    const std::set<const lir::Bytecode*>& statuses,
                    FilterOutput& output) {
  const std::optional<std::set<const lir::Label*>> unknown =
      UnknownLabels(code_ir);
  if (!unknown) {
    return;
  }
  // The registers that hold a value at the jumps so far to each label.
  std::map<const lir::Label*, Registers> jumped;
  Registers holding;
  bool falls_through = true;
  for (lir::Instruction* instr : code_ir.instructions) {
    if (auto* label = dynamic_cast<const lir::Label*>(instr)) {
      auto jump = jumped.find(label);
      if (unknown->count(label) != 0) {
        holding.clear();
      } else if (!falls_through) {
        holding = jump == jumped.end() ? Registers() : jump->second;
      } else if (jump != jumped.end()) {
        holding = Intersect(holding, jump->second);
      }
      falls_through = true;
      continue;
    }
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    if (bytecode == nullptr) {
      continue;
    }
    const int reg = Register(bytecode, 0);
    if ((bytecode->opcode == dex::OP_IF_NEZ ||
         bytecode->opcode == dex::OP_IF_EQZ) &&
        holding.count(reg) != 0) {
      output.Ignore(bytecode, bytecode);
    }
    // UnknownLabels() found the jumps of every bytecode.
    const std::vector<const lir::Label*> targets = *Jumps(bytecode);
    for (const lir::Label* target : targets) {
      auto [it, inserted] = jumped.emplace(target, holding);
      if (!inserted) {
        it->second = Intersect(it->second, holding);
      }
    }
    const dex::OpcodeFlags flags = dex::GetFlagsFromOpcode(bytecode->opcode);
    if (AccessesAssertionsDisabled(bytecode, dex::OP_SGET_BOOLEAN,
                                   class_name) ||
        AccessesAssertionsDisabled(bytecode, dex::OP_SPUT_BOOLEAN,
                                   class_name) ||
        statuses.count(bytecode) != 0 ||
        (IsMove(bytecode->opcode) &&
         holding.count(Register(bytecode, 1)) != 0)) {
      holding.insert(reg);
    } else if ((flags & (dex::kBranch | dex::kSwitch)) == 0) {
      // The other bytecodes may write the register or the pair of registers of
      // their first operand.
      auto* pair =
          bytecode->operands.empty()
              ? nullptr
              : dynamic_cast<const lir::VRegPair*>(bytecode->operands[0]);
      if (pair != nullptr) {
        holding.erase(static_cast<int>(pair->base_reg));
        holding.erase(static_cast<int>(pair->base_reg) + 1);
      } else {
        holding.erase(reg);
      }
    }
    falls_through = (flags & dex::kContinue) != 0;
  }
}

}  // namespace

void AssertFilter::Filter(const FilterContext& context,
                          ir::EncodedMethod* ir_method, lir::CodeIr& code_ir,
                          FilterOutput& output) {
  std::vector<lir::Bytecode*> bytecodes;
  for (auto* instr : code_ir.instructions) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      bytecodes.push_back(bytecode);
    }
  }
  // Skips the many methods that neither read nor set the field.
  if (std::none_of(
          bytecodes.begin(), bytecodes.end(),
          [&context](const lir::Bytecode* bytecode) {
            return AccessesAssertionsDisabled(bytecode, dex::OP_SGET_BOOLEAN,
                                              context.class_name) ||
                   AccessesAssertionsDisabled(bytecode, dex::OP_SPUT_BOOLEAN,
                                              context.class_name);
          })) {
    return;
  }
  // The static initializer sets the field.
  std::set<const lir::Bytecode*> statuses;
  if (std::string_view(ir_method->decl->name->c_str()) == kStaticInitializer) {
    for (size_t i = 1; i < bytecodes.size(); ++i) {
      if (!AsksAssertionStatus(bytecodes[i - 1], bytecodes[i])) {
        continue;
      }
      if (std::optional<size_t> set =
              SetsAssertionsDisabled(bytecodes, i, context.class_name)) {
        output.Ignore(bytecodes[i - 1], bytecodes[*set]);
        statuses.insert(bytecodes[i + 1]);
        i = *set;
      }
    }
  }
  IgnoreBranches(code_ir, context.class_name, statuses, output);
}

REGISTER_FILTER(AssertFilter)

}  // namespace coverage
