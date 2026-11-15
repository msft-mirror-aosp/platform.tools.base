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

#include "tools/base/android-test/coverage/agent/native/exhaustive_switch_filter.h"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <optional>
#include <set>
#include <utility>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kMatchException[] = "Ljava/lang/MatchException;";
constexpr char kRuntimeException[] = "Ljava/lang/RuntimeException;";
constexpr char kIncompatibleClassChangeError[] =
    "Ljava/lang/IncompatibleClassChangeError;";
// The signatures of MatchException(String, Throwable), which D8 replaces with
// RuntimeException(String, Throwable), and of IncompatibleClassChangeError().
constexpr char kMessageAndCause[] =
    "(Ljava/lang/String;Ljava/lang/Throwable;)V";
constexpr char kNoArguments[] = "()V";

// The instructions from `from` to `to`, both inclusive, in code order.
struct Range {
  lir::Instruction* from;
  lir::Instruction* to;
};

// Returns the first bytecode at or after `instr`, skipping labels, debug
// annotations and other non-bytecode nodes, or nullptr.
lir::Bytecode* SkipNonBytecodes(lir::Instruction* instr) {
  for (auto* node = instr; node != nullptr; node = node->next) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(node)) {
      return bytecode;
    }
  }
  return nullptr;
}

// Returns the first bytecode after `instr`, or nullptr.
lir::Bytecode* NextBytecode(lir::Instruction* instr) {
  return instr == nullptr ? nullptr : SkipNonBytecodes(instr->next);
}

// Returns the target of a branch instruction, or the payload label of a
// switch instruction, or nullptr.
lir::Label* BranchTarget(const lir::Bytecode* bytecode) {
  for (auto* operand : bytecode->operands) {
    if (auto* code_location = dynamic_cast<lir::CodeLocation*>(operand)) {
      return code_location->label;
    }
  }
  return nullptr;
}

// Returns the register of operand `index` of `bytecode`, or -1.
int Register(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return -1;
  }
  auto* reg = dynamic_cast<lir::VReg*>(bytecode->operands[index]);
  return reg == nullptr ? -1 : static_cast<int>(reg->reg);
}

// Returns the registers that the invoke `bytecode` passes to its method.
std::vector<int> Arguments(const lir::Bytecode* bytecode) {
  std::vector<int> registers;
  if (bytecode->operands.empty()) {
    return registers;
  }
  if (auto* list = dynamic_cast<lir::VRegList*>(bytecode->operands[0])) {
    for (auto reg : list->registers) {
      registers.push_back(static_cast<int>(reg));
    }
  } else if (auto* range =
                 dynamic_cast<lir::VRegRange*>(bytecode->operands[0])) {
    for (int i = 0; i < range->count; ++i) {
      registers.push_back(static_cast<int>(range->base_reg) + i);
    }
  }
  return registers;
}

bool IsSwitch(dex::Opcode opcode) {
  return opcode == dex::OP_PACKED_SWITCH || opcode == dex::OP_SPARSE_SWITCH;
}

bool IsGoto(dex::Opcode opcode) {
  return opcode == dex::OP_GOTO || opcode == dex::OP_GOTO_16 ||
         opcode == dex::OP_GOTO_32;
}

bool IsMove(dex::Opcode opcode) {
  return opcode >= dex::OP_MOVE && opcode <= dex::OP_MOVE_OBJECT_16;
}

// Returns true if `bytecode` writes register `reg` with its first operand, a
// register or a pair of registers.
bool Writes(const lir::Bytecode* bytecode, int reg) {
  if (bytecode->operands.empty()) {
    return false;
  }
  if (auto* vreg = dynamic_cast<lir::VReg*>(bytecode->operands[0])) {
    return static_cast<int>(vreg->reg) == reg;
  }
  if (auto* pair = dynamic_cast<lir::VRegPair*>(bytecode->operands[0])) {
    return static_cast<int>(pair->base_reg) == reg ||
           static_cast<int>(pair->base_reg) + 1 == reg;
  }
  return false;
}

// Returns true if `bytecode` loads 0, which is also null, into a register.
bool LoadsZero(const lir::Bytecode* bytecode) {
  if (bytecode->opcode != dex::OP_CONST_4 &&
      bytecode->opcode != dex::OP_CONST_16) {
    return false;
  }
  auto* value = bytecode->operands.size() >= 2
                    ? dynamic_cast<lir::Const32*>(bytecode->operands[1])
                    : nullptr;
  return value != nullptr && value->u.u4_value == 0;
}

// Returns the descriptor of the type operand `index` of `bytecode`, or
// nullptr.
const char* TypeOperand(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return nullptr;
  }
  auto* type = dynamic_cast<lir::Type*>(bytecode->operands[index]);
  return type != nullptr && type->ir_type != nullptr &&
                 type->ir_type->descriptor != nullptr
             ? type->ir_type->descriptor->c_str()
             : nullptr;
}

// Returns the method that `bytecode` calls with invoke-direct, or nullptr.
const ir::MethodDecl* DirectlyInvokedMethod(const lir::Bytecode* bytecode) {
  if (bytecode->opcode != dex::OP_INVOKE_DIRECT &&
      bytecode->opcode != dex::OP_INVOKE_DIRECT_RANGE) {
    return nullptr;
  }
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<lir::Method*>(operand)) {
      return method->ir_method;
    }
  }
  return nullptr;
}

// Returns true if `method` is the constructor of `type` with `signature`.
bool IsConstructor(const ir::MethodDecl* method, const char* type,
                   const char* signature) {
  return method != nullptr && method->parent != nullptr &&
         method->parent->descriptor != nullptr && method->name != nullptr &&
         method->prototype != nullptr &&
         strcmp(method->parent->descriptor->c_str(), type) == 0 &&
         strcmp(method->name->c_str(), "<init>") == 0 &&
         method->prototype->Signature() == signature;
}

// Returns the line number of `instr`, which the last line annotation before it
// gives, or -1.
int LineOf(lir::Instruction* instr) {
  for (auto* node = instr; node != nullptr; node = node->prev) {
    auto* annotation = dynamic_cast<lir::DbgInfoAnnotation*>(node);
    if (annotation != nullptr &&
        annotation->dbg_opcode == dex::DBG_ADVANCE_LINE &&
        !annotation->operands.empty()) {
      if (auto* line =
              dynamic_cast<lir::LineNumber*>(annotation->operands[0])) {
        return static_cast<int>(line->line);
      }
    }
  }
  return -1;
}

// Returns the bytecode that runs after `bytecode`, which belongs to the code
// that throws the exception. Inside try blocks, D8 can put a goto in that
// code. Then this function adds the range from `*start` to the goto to
// `ranges`, moves `*start` to the first bytecode at the target of the goto
// and returns it. Returns nullptr if the code goes back to one of `visited`,
// the bytecodes that it ran before, since then it loops.
lir::Bytecode* NextInThrow(lir::Bytecode* bytecode, lir::Instruction** start,
                           std::vector<Range>* ranges,
                           std::set<const lir::Bytecode*>* visited) {
  lir::Bytecode* next = NextBytecode(bytecode);
  if (next != nullptr && IsGoto(next->opcode)) {
    lir::Bytecode* target = SkipNonBytecodes(BranchTarget(next));
    if (target == nullptr) {
      return nullptr;
    }
    ranges->push_back({*start, next});
    *start = target;
    next = target;
  }
  if (next != nullptr && !visited->insert(next).second) {
    return nullptr;
  }
  return next;
}

// The code that throws the exception of a switch that covers every case.
struct Throw {
  // The ranges of the code, which gotos can split.
  std::vector<Range> ranges;
  // The new-instance, the invoke-direct and the throw. The other bytecodes,
  // moves and loads of null, can have no line of their own.
  std::vector<lir::Bytecode*> positioned;
};

// Matches the code that throws the exception of a switch that covers every
// case, from the first bytecode at or after `start`:
//     new-instance vE, Ljava/lang/MatchException;   (or RuntimeException)
//     const/4 vN, #0
//     invoke-direct {vE, vN, vN}, Ljava/lang/MatchException;-><init>(...)V
//     throw vE
// or, for IncompatibleClassChangeError, without the const and with
// invoke-direct {vE}. D8 can put moves for other values anywhere in that code,
// load null before the new-instance or before the switch, and put gotos in the
// code inside try blocks. For registers above v15, it passes two registers
// that it loads with null to an invoke-direct/range. Returns nothing for other
// code.
std::optional<Throw> MatchThrow(lir::Instruction* start) {
  Throw result;
  lir::Bytecode* bytecode = SkipNonBytecodes(start);
  start = bytecode;
  std::set<const lir::Bytecode*> visited = {bytecode};
  // The registers that the code loads null into, and the one of the exception.
  std::vector<int> nulls;
  int exception = -1;
  // Returns true if `move` is one of the moves for other values that D8 can
  // put anywhere in the code, which write none of its registers.
  auto is_other_move = [&](const lir::Bytecode* move) {
    return IsMove(move->opcode) && !Writes(move, exception) &&
           std::none_of(nulls.begin(), nulls.end(),
                        [move](int reg) { return Writes(move, reg); });
  };
  // Returns the bytecode of the code after `current`, past such moves.
  auto next = [&](lir::Bytecode* current) {
    lir::Bytecode* following = current;
    do {
      following = NextInThrow(following, &start, &result.ranges, &visited);
    } while (following != nullptr && is_other_move(following));
    return following;
  };
  if (bytecode != nullptr && is_other_move(bytecode)) {
    bytecode = next(bytecode);
  }
  while (bytecode != nullptr && LoadsZero(bytecode)) {
    nulls.push_back(Register(bytecode, 0));
    bytecode = next(bytecode);
  }
  if (bytecode == nullptr || bytecode->opcode != dex::OP_NEW_INSTANCE) {
    return std::nullopt;
  }
  const char* type = TypeOperand(bytecode, 1);
  if (type == nullptr) {
    return std::nullopt;
  }
  bool has_cause = strcmp(type, kMatchException) == 0 ||
                   strcmp(type, kRuntimeException) == 0;
  if (!has_cause && strcmp(type, kIncompatibleClassChangeError) != 0) {
    return std::nullopt;
  }
  exception = Register(bytecode, 0);
  result.positioned.push_back(bytecode);
  bytecode = next(bytecode);
  while (bytecode != nullptr && LoadsZero(bytecode)) {
    nulls.push_back(Register(bytecode, 0));
    bytecode = next(bytecode);
  }
  if (bytecode == nullptr ||
      !IsConstructor(DirectlyInvokedMethod(bytecode), type,
                     has_cause ? kMessageAndCause : kNoArguments)) {
    return std::nullopt;
  }
  std::vector<int> arguments = Arguments(bytecode);
  if (arguments.empty() || arguments[0] != exception) {
    return std::nullopt;
  }
  if (has_cause) {
    // The message and the cause are null: a register passed as both, which
    // only null can be, or two registers that the code loads null into.
    if (arguments.size() != 3 || arguments[1] == exception ||
        arguments[2] == exception) {
      return std::nullopt;
    }
    for (int reg : nulls) {
      if (reg != arguments[1] && reg != arguments[2]) {
        return std::nullopt;
      }
    }
    if (arguments[1] != arguments[2] &&
        (std::count(nulls.begin(), nulls.end(), arguments[1]) == 0 ||
         std::count(nulls.begin(), nulls.end(), arguments[2]) == 0)) {
      return std::nullopt;
    }
  } else if (arguments.size() != 1 || !nulls.empty()) {
    return std::nullopt;
  }
  result.positioned.push_back(bytecode);
  bytecode = next(bytecode);
  if (bytecode == nullptr || bytecode->opcode != dex::OP_THROW ||
      Register(bytecode, 0) != exception) {
    return std::nullopt;
  }
  result.positioned.push_back(bytecode);
  result.ranges.push_back({start, bytecode});
  return result;
}

// Appends to `targets` the first bytecode of the code of each case of
// `switch_bytecode`, without duplicates, in order, except for the cases that go
// to one of `defaults`. Returns false if the cases aren't found.
bool CaseTargets(const lir::Bytecode* switch_bytecode,
                 const std::vector<lir::Bytecode*>& defaults,
                 std::vector<lir::Instruction*>* targets) {
  lir::Label* payload_label = BranchTarget(switch_bytecode);
  lir::Instruction* payload =
      payload_label == nullptr ? nullptr : payload_label->next;
  std::vector<lir::Label*> cases;
  if (auto* packed = dynamic_cast<lir::PackedSwitchPayload*>(payload)) {
    cases = packed->targets;
  } else if (auto* sparse = dynamic_cast<lir::SparseSwitchPayload*>(payload)) {
    for (const auto& switch_case : sparse->switch_cases) {
      cases.push_back(switch_case.target);
    }
  } else {
    return false;
  }
  for (lir::Label* label : cases) {
    lir::Bytecode* code = SkipNonBytecodes(label);
    if (code == nullptr) {
      return false;
    }
    if (std::find(defaults.begin(), defaults.end(), code) == defaults.end() &&
        std::find(targets->begin(), targets->end(), code) == targets->end()) {
      targets->push_back(code);
    }
  }
  return true;
}

}  // namespace

void ExhaustiveSwitchFilter::Filter(const FilterContext& context,
                                    ir::EncodedMethod* ir_method,
                                    lir::CodeIr& code_ir,
                                    FilterOutput& output) {
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    if (bytecode == nullptr || !IsSwitch(bytecode->opcode)) {
      continue;
    }
    // The default code follows the switch, or a goto to it does.
    lir::Bytecode* after = NextBytecode(bytecode);
    lir::Bytecode* jump = nullptr;
    lir::Bytecode* default_code = after;
    if (after != nullptr && IsGoto(after->opcode)) {
      jump = after;
      default_code = SkipNonBytecodes(BranchTarget(after));
    }
    std::optional<Throw> thrown = MatchThrow(default_code);
    if (!thrown.has_value()) {
      continue;
    }
    // The code is on the line of the switch.
    int line = LineOf(bytecode);
    if (!std::all_of(
            thrown->positioned.begin(), thrown->positioned.end(),
            [line](lir::Bytecode* code) { return LineOf(code) == line; })) {
      continue;
    }
    // The cases that go to the default code, or to the goto to it, add no
    // branch.
    std::vector<lir::Instruction*> targets;
    if (!CaseTargets(bytecode, {after, default_code}, &targets) ||
        targets.empty()) {
      continue;
    }
    output.ReplaceBranches(bytecode, std::move(targets));
    if (jump != nullptr) {
      output.Ignore(jump, jump);
    }
    for (const Range& range : thrown->ranges) {
      output.Ignore(range.from, range.to);
    }
  }
}

REGISTER_FILTER(ExhaustiveSwitchFilter)

}  // namespace coverage
