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

#include "tools/base/android-test/coverage/agent/native/string_switch_matcher.h"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"

namespace coverage {

namespace {

constexpr char kString[] = "Ljava/lang/String;";

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

// Returns true if the first operand of `bytecode` names register `reg`, as
// the destination of a const or a move does.
bool FirstOperandNames(const lir::Bytecode* bytecode, int reg) {
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

// Returns true if `bytecode` calls the method of String named `name` with the
// JNI `signature`.
bool CallsString(const lir::Bytecode* bytecode, const char* name,
                 const char* signature) {
  if (bytecode == nullptr ||
      (bytecode->opcode != dex::OP_INVOKE_VIRTUAL &&
       bytecode->opcode != dex::OP_INVOKE_VIRTUAL_RANGE)) {
    return false;
  }
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<lir::Method*>(operand)) {
      const ir::MethodDecl* decl = method->ir_method;
      return decl != nullptr && decl->parent != nullptr &&
             decl->parent->descriptor != nullptr && decl->name != nullptr &&
             decl->prototype != nullptr &&
             strcmp(decl->parent->descriptor->c_str(), kString) == 0 &&
             strcmp(decl->name->c_str(), name) == 0 &&
             decl->prototype->Signature() == signature;
    }
  }
  return false;
}

bool IsSwitch(dex::Opcode opcode) {
  return opcode == dex::OP_PACKED_SWITCH || opcode == dex::OP_SPARSE_SWITCH;
}

bool IsGoto(dex::Opcode opcode) {
  return opcode == dex::OP_GOTO || opcode == dex::OP_GOTO_16 ||
         opcode == dex::OP_GOTO_32;
}

// Returns the first bytecode that isn't a goto from `bytecode` on, following
// gotos, or nullptr if the gotos loop. D8 can jump to the code after a switch
// with a goto to the goto after the switch.
lir::Bytecode* FollowGotos(lir::Bytecode* bytecode) {
  std::set<lir::Bytecode*> followed;
  while (bytecode != nullptr && IsGoto(bytecode->opcode)) {
    if (!followed.insert(bytecode).second) {
      return nullptr;
    }
    bytecode = SkipNonBytecodes(BranchTarget(bytecode));
  }
  return bytecode;
}

// Returns true if `opcode` loads a constant into a register, as D8 does
// before a switch for the constants that the code after it shares, or copies a
// register.
bool IsConstOrMove(dex::Opcode opcode) {
  return (opcode >= dex::OP_CONST_4 && opcode <= dex::OP_CONST_STRING_JUMBO) ||
         opcode == dex::OP_CONST_CLASS ||
         (opcode >= dex::OP_MOVE && opcode <= dex::OP_MOVE_OBJECT_16);
}

// Returns true if `opcode` copies a 32-bit value from a register.
bool IsIntMove(dex::Opcode opcode) {
  return opcode == dex::OP_MOVE || opcode == dex::OP_MOVE_FROM16 ||
         opcode == dex::OP_MOVE_16;
}

// Returns true if `opcode` loads a 32-bit number into a register.
bool IsIntConst(dex::Opcode opcode) {
  return opcode == dex::OP_CONST_4 || opcode == dex::OP_CONST_16 ||
         opcode == dex::OP_CONST;
}

// Appends to `targets` the distinct targets of the cases of `switch_bytecode`,
// in order. Returns false if the payload of the switch isn't found.
bool SwitchTargets(const lir::Bytecode* switch_bytecode,
                   std::vector<lir::Label*>* targets) {
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
  for (lir::Label* target : cases) {
    if (std::find(targets->begin(), targets->end(), target) == targets->end()) {
      targets->push_back(target);
    }
  }
  return true;
}

// Matches the comparison of the String in register `subject` with a string,
// from the first bytecode at or after `start`:
//     const-string vT, "<string>"   (unless D8 loaded it earlier)
//     invoke-virtual {vS, vT}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
//     move-result vR
//     if-eqz vR, :next              (or if-nez vR, :case)
bool MatchComparison(lir::Instruction* start, int subject,
                     StringSwitch::Range* comparison) {
  lir::Bytecode* first = SkipNonBytecodes(start);
  if (first == nullptr) {
    return false;
  }
  lir::Bytecode* call = first;
  int string = -1;
  if (first->opcode == dex::OP_CONST_STRING ||
      first->opcode == dex::OP_CONST_STRING_JUMBO) {
    string = Register(first, 0);
    call = NextBytecode(first);
  }
  if (!CallsString(call, "equals", "(Ljava/lang/Object;)Z")) {
    return false;
  }
  std::vector<int> arguments = Arguments(call);
  if (arguments.size() != 2 || arguments[0] != subject ||
      (string >= 0 && arguments[1] != string)) {
    return false;
  }
  lir::Bytecode* result = NextBytecode(call);
  if (result == nullptr || result->opcode != dex::OP_MOVE_RESULT) {
    return false;
  }
  lir::Bytecode* jump = NextBytecode(result);
  if (jump == nullptr ||
      (jump->opcode != dex::OP_IF_EQZ && jump->opcode != dex::OP_IF_NEZ) ||
      Register(jump, 0) != Register(result, 0)) {
    return false;
  }
  *comparison = {first, jump};
  return true;
}

// Adds `code` to `cases` unless it is already there. Returns false if there
// is no code.
bool AddCase(lir::Bytecode* code, std::vector<lir::Bytecode*>* cases) {
  if (code == nullptr) {
    return false;
  }
  if (std::find(cases->begin(), cases->end(), code) == cases->end()) {
    cases->push_back(code);
  }
  return true;
}

// Returns the line number of `instr`, which the last line annotation before
// it gives, or -1.
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

// Returns the switch that the code from `code` on goes on with, after the
// constants and moves that D8 can hoist right before it for the code after it,
// which don't write the register of the switch. Returns nullptr if the code
// goes on with something else.
lir::Bytecode* SwitchAfterHoisted(lir::Bytecode* code) {
  lir::Bytecode* bytecode = code;
  while (bytecode != nullptr && IsConstOrMove(bytecode->opcode)) {
    bytecode = NextBytecode(bytecode);
  }
  if (bytecode == nullptr || !IsSwitch(bytecode->opcode)) {
    return nullptr;
  }
  int index = Register(bytecode, 0);
  for (lir::Bytecode* hoisted = code; hoisted != bytecode;
       hoisted = NextBytecode(hoisted)) {
    if (FirstOperandNames(hoisted, index)) {
      return nullptr;
    }
  }
  return bytecode;
}

// Matches the load of an index into a register at `load`, on line `line`,
// followed by a goto to the second switch, a switch on that register, or by
// that switch itself, possibly after constants that D8 hoists before it. The
// load of the default code loads -1 unless it is a move. `*second_switch` is
// set by the first load, and the others must go to the same switch.
bool MatchLoad(lir::Bytecode* load, int line, bool is_default,
               lir::Bytecode** second_switch, StringSwitch::Range* range) {
  if (load == nullptr || LineOf(load) != line) {
    return false;
  }
  if (IsIntConst(load->opcode)) {
    auto* value = load->operands.size() >= 2
                      ? dynamic_cast<lir::Const32*>(load->operands[1])
                      : nullptr;
    if (value == nullptr || (is_default && value->u.s4_value != -1)) {
      return false;
    }
  } else if (!IsIntMove(load->opcode)) {
    return false;
  }
  lir::Bytecode* last = load;
  lir::Bytecode* next = NextBytecode(load);
  if (next != nullptr && IsGoto(next->opcode)) {
    last = next;
    next = FollowGotos(next);
  }
  next = SwitchAfterHoisted(next);
  if (next == nullptr || Register(next, 0) != Register(load, 0) ||
      (*second_switch != nullptr && next != *second_switch)) {
    return false;
  }
  *second_switch = next;
  *range = {load, last};
  return true;
}

// Returns the first instruction of the method of `instr`.
lir::Instruction* FirstInstruction(lir::Instruction* instr) {
  while (instr->prev != nullptr) {
    instr = instr->prev;
  }
  return instr;
}

// The jumps of a method: the bytecodes that jump to each label, branches and
// switches, and the labels where its try blocks have handlers.
struct Jumps {
  std::map<const lir::Label*, std::vector<lir::Bytecode*>> to;
  std::set<const lir::Label*> handlers;
};

// Returns the jumps of the method of `instr`.
Jumps CollectJumps(lir::Instruction* instr) {
  Jumps jumps;
  auto add = [&jumps](const lir::Label* label, lir::Bytecode* bytecode) {
    std::vector<lir::Bytecode*>& sources = jumps.to[label];
    if (sources.empty() || sources.back() != bytecode) {
      sources.push_back(bytecode);
    }
  };
  for (auto* node = FirstInstruction(instr); node != nullptr;
       node = node->next) {
    if (auto* try_end = dynamic_cast<lir::TryBlockEnd*>(node)) {
      if (try_end->catch_all != nullptr) {
        jumps.handlers.insert(try_end->catch_all);
      }
      for (const lir::CatchHandler& handler : try_end->handlers) {
        jumps.handlers.insert(handler.label);
      }
      continue;
    }
    auto* bytecode = dynamic_cast<lir::Bytecode*>(node);
    if (bytecode == nullptr) {
      continue;
    }
    if (IsSwitch(bytecode->opcode)) {
      lir::Label* payload_label = BranchTarget(bytecode);
      lir::Instruction* payload =
          payload_label == nullptr ? nullptr : payload_label->next;
      if (auto* packed = dynamic_cast<lir::PackedSwitchPayload*>(payload)) {
        for (lir::Label* target : packed->targets) {
          add(target, bytecode);
        }
      } else if (auto* sparse =
                     dynamic_cast<lir::SparseSwitchPayload*>(payload)) {
        for (const auto& switch_case : sparse->switch_cases) {
          add(switch_case.target, bytecode);
        }
      }
    } else if ((dex::GetFlagsFromOpcode(bytecode->opcode) & dex::kBranch) !=
               0) {
      if (lir::Label* target = BranchTarget(bytecode)) {
        add(target, bytecode);
      }
    }
  }
  return jumps;
}

// Returns true if `opcode` only reads the register of its first operand, as
// branches, switches, returns, throws, monitors and stores do.
bool OnlyReadsFirstOperand(dex::Opcode opcode) {
  return (opcode >= dex::OP_RETURN && opcode <= dex::OP_RETURN_OBJECT) ||
         opcode == dex::OP_MONITOR_ENTER || opcode == dex::OP_MONITOR_EXIT ||
         opcode == dex::OP_FILL_ARRAY_DATA || opcode == dex::OP_THROW ||
         IsSwitch(opcode) ||
         (opcode >= dex::OP_IF_EQ && opcode <= dex::OP_IF_LEZ) ||
         (opcode >= dex::OP_APUT && opcode <= dex::OP_APUT_SHORT) ||
         (opcode >= dex::OP_IPUT && opcode <= dex::OP_IPUT_SHORT) ||
         (opcode >= dex::OP_SPUT && opcode <= dex::OP_SPUT_SHORT);
}

// Returns true if register `reg` holds -1 whenever `bytecode` runs: on each
// path to it, the last bytecode that writes `reg` loads -1 into it. D8 can load
// -1 once for several switches of a method. Paths from the start of the method
// or from a handler don't hold -1.
bool HoldsMinusOne(lir::Bytecode* bytecode, int reg) {
  const Jumps jumps = CollectJumps(bytecode);
  // The bytecodes before which paths to `bytecode` remain to be followed back.
  std::vector<lir::Instruction*> ends = {bytecode};
  std::set<lir::Label*> followed;
  while (!ends.empty()) {
    lir::Instruction* node = ends.back()->prev;
    ends.pop_back();
    // Whether a label lies between `node` and the code that it would fall
    // through to.
    bool before_label = false;
    for (;; node = node->prev) {
      if (node == nullptr) {
        return false;
      }
      if (auto* label = dynamic_cast<lir::Label*>(node)) {
        if (!followed.insert(label).second) {
          break;
        }
        if (jumps.handlers.count(label) > 0) {
          return false;
        }
        auto sources = jumps.to.find(label);
        if (sources != jumps.to.end()) {
          ends.insert(ends.end(), sources->second.begin(),
                      sources->second.end());
        }
        before_label = true;
        continue;
      }
      // Code never falls through to a payload.
      if (dynamic_cast<lir::PackedSwitchPayload*>(node) != nullptr ||
          dynamic_cast<lir::SparseSwitchPayload*>(node) != nullptr ||
          dynamic_cast<lir::ArrayData*>(node) != nullptr) {
        break;
      }
      auto* previous = dynamic_cast<lir::Bytecode*>(node);
      if (previous == nullptr) {
        continue;
      }
      if (before_label &&
          (dex::GetFlagsFromOpcode(previous->opcode) & dex::kContinue) == 0) {
        // The code before the label doesn't fall through to it.
        break;
      }
      before_label = false;
      if (FirstOperandNames(previous, reg) &&
          !OnlyReadsFirstOperand(previous->opcode)) {
        auto* value =
            IsIntConst(previous->opcode) && previous->operands.size() >= 2
                ? dynamic_cast<lir::Const32*>(previous->operands[1])
                : nullptr;
        if (value == nullptr || value->u.s4_value != -1) {
          return false;
        }
        break;
      }
    }
  }
  return true;
}

// Returns true if a bytecode from `range.from` to `range.to` names register
// `reg` first, as the ones that write it do.
bool NamesIn(const StringSwitch::Range& range, int reg) {
  for (lir::Bytecode* bytecode = range.from; bytecode != nullptr;
       bytecode = bytecode == range.to ? nullptr : NextBytecode(bytecode)) {
    if (FirstOperandNames(bytecode, reg)) {
      return true;
    }
  }
  return false;
}

// Matches the code at `code` of a case, or of the default code, whose load D8
// left out because the register of the second switch already holds the index:
// the code goes on with the second switch, possibly after constants that D8
// hoists before it. In release mode, D8 can give the second switch the line of
// such a constant. `*second_switch` is as for MatchLoad().
bool MatchLeftOutLoad(lir::Bytecode* code, lir::Bytecode** second_switch) {
  lir::Bytecode* next = SwitchAfterHoisted(FollowGotos(code));
  if (next == nullptr ||
      (*second_switch != nullptr && next != *second_switch)) {
    return false;
  }
  *second_switch = next;
  return true;
}

// Returns the if-eqz on register `subject` right before `call`, with no
// jump to `call` and no start of a line in between, or nullptr. In release
// mode, D8 gives no line to such a check at the start of a method, and the
// call starts the first line of the method. The code of a Kotlin class before
// its first line isn't reported, so the switch keeps its branches then.
lir::Bytecode* NullCheck(lir::Bytecode* call, int subject) {
  for (auto* node = call->prev; node != nullptr; node = node->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(node)) {
      return bytecode->opcode == dex::OP_IF_EQZ &&
                     Register(bytecode, 0) == subject
                 ? bytecode
                 : nullptr;
    }
    auto* annotation = dynamic_cast<lir::DbgInfoAnnotation*>(node);
    if (dynamic_cast<lir::Label*>(node) != nullptr ||
        (annotation != nullptr &&
         annotation->dbg_opcode == dex::DBG_ADVANCE_LINE)) {
      return nullptr;
    }
  }
  return nullptr;
}

// Returns true if a bytecode of the method of `code` other than those in
// `structure` jumps to `code`, directly or through gotos, or falls through to
// it.
bool IsReachedFromOutside(lir::Bytecode* code,
                          const std::set<lir::Bytecode*>& structure) {
  lir::Bytecode* previous = nullptr;
  for (auto* node = FirstInstruction(code); node != nullptr;
       node = node->next) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(node);
    if (bytecode == nullptr) {
      continue;
    }
    if (bytecode == code && previous != nullptr &&
        structure.count(previous) == 0 &&
        (dex::GetFlagsFromOpcode(previous->opcode) & dex::kContinue) != 0) {
      return true;
    }
    previous = bytecode;
    if (structure.count(bytecode) > 0) {
      continue;
    }
    std::vector<lir::Label*> targets;
    if (IsSwitch(bytecode->opcode)) {
      SwitchTargets(bytecode, &targets);
    } else if ((dex::GetFlagsFromOpcode(bytecode->opcode) & dex::kBranch) !=
               0) {
      if (lir::Label* target = BranchTarget(bytecode)) {
        targets.push_back(target);
      }
    }
    for (lir::Label* target : targets) {
      if (FollowGotos(SkipNonBytecodes(target)) == code) {
        return true;
      }
    }
  }
  return false;
}

}  // namespace

std::optional<StringSwitch> MatchStringSwitch(lir::Bytecode* hash_code) {
  if (!CallsString(hash_code, "hashCode", "()I")) {
    return std::nullopt;
  }
  std::vector<int> arguments = Arguments(hash_code);
  if (arguments.size() != 1) {
    return std::nullopt;
  }
  StringSwitch result;
  result.subject = arguments[0];
  lir::Bytecode* move_result = NextBytecode(hash_code);
  if (move_result == nullptr || move_result->opcode != dex::OP_MOVE_RESULT) {
    return std::nullopt;
  }
  result.hash_code = {hash_code, move_result};
  int hash = Register(move_result, 0);
  // D8 can load the constants that cases share before the switch.
  lir::Bytecode* next = NextBytecode(move_result);
  while (next != nullptr && IsConstOrMove(next->opcode)) {
    if (FirstOperandNames(next, hash) ||
        FirstOperandNames(next, result.subject)) {
      return std::nullopt;
    }
    next = NextBytecode(next);
  }
  if (next == nullptr || !IsSwitch(next->opcode) || Register(next, 0) != hash) {
    return std::nullopt;
  }
  result.switch_bytecode = next;
  std::vector<lir::Label*> hashes;
  if (!SwitchTargets(next, &hashes) || hashes.empty()) {
    return std::nullopt;
  }
  lir::Bytecode* after = NextBytecode(next);
  if (after != nullptr && IsGoto(after->opcode)) {
    result.default_goto = after;
  }
  result.default_code = FollowGotos(after);
  if (result.default_code == nullptr) {
    return std::nullopt;
  }
  // Each hash code goes to a chain of comparisons, which ends with a jump to
  // the default code. A packed-switch also goes to the default code for the
  // values between the hash codes.
  std::set<lir::Bytecode*> matched;
  for (lir::Label* hash_label : hashes) {
    if (FollowGotos(SkipNonBytecodes(hash_label)) == result.default_code) {
      continue;
    }
    lir::Instruction* start = hash_label;
    while (true) {
      StringSwitch::Range comparison;
      if (!MatchComparison(start, result.subject, &comparison) ||
          !matched.insert(comparison.from).second) {
        return std::nullopt;
      }
      result.comparisons.push_back(comparison);
      lir::Bytecode* jump = comparison.to;
      lir::Bytecode* target = SkipNonBytecodes(BranchTarget(jump));
      if (jump->opcode == dex::OP_IF_NEZ) {
        // Jumps to the code of the case, and goes on with the next comparison,
        // the default code or a goto to it.
        if (!AddCase(target, &result.cases)) {
          return std::nullopt;
        }
        lir::Bytecode* following = NextBytecode(jump);
        if (following == result.default_code) {
          break;
        }
        if (following != nullptr && IsGoto(following->opcode) &&
            FollowGotos(following) == result.default_code) {
          result.gotos.push_back(following);
          break;
        }
        start = jump->next;
      } else {
        // Goes on with the code of the case, and jumps to the next comparison
        // or to the default code.
        if (!AddCase(NextBytecode(jump), &result.cases)) {
          return std::nullopt;
        }
        if (FollowGotos(target) == result.default_code) {
          break;
        }
        start = BranchTarget(jump);
      }
    }
  }
  // When each hash code goes to the default code, nothing compares the
  // String: that is a switch on hashCode() in the source, not on a String.
  if (result.comparisons.empty()) {
    return std::nullopt;
  }
  result.null_check = NullCheck(hash_code, result.subject);
  if (result.null_check != nullptr) {
    lir::Bytecode* null_code =
        SkipNonBytecodes(BranchTarget(result.null_check));
    if (FollowGotos(null_code) != result.default_code) {
      result.null_code = null_code;
    }
  }
  std::set<lir::Bytecode*> structure = {result.switch_bytecode};
  for (lir::Bytecode* bytecode : {result.default_goto, result.null_check}) {
    if (bytecode != nullptr) {
      structure.insert(bytecode);
    }
  }
  for (const StringSwitch::Range& comparison : result.comparisons) {
    structure.insert(comparison.to);
  }
  structure.insert(result.gotos.begin(), result.gotos.end());
  result.default_code_shared =
      IsReachedFromOutside(result.default_code, structure);
  return result;
}

bool CasesStartOnLineOfSwitch(const StringSwitch& string_switch) {
  int line = LineOf(string_switch.switch_bytecode);
  for (lir::Bytecode* code : string_switch.cases) {
    if (LineOf(code) != line) {
      return false;
    }
  }
  return true;
}

std::optional<JavacStringSwitch> MatchJavacStringSwitch(
    const StringSwitch& string_switch) {
  int line = LineOf(string_switch.switch_bytecode);
  JavacStringSwitch result;
  std::vector<lir::Bytecode*> codes = string_switch.cases;
  codes.push_back(string_switch.default_code);
  bool case_left_out = false;
  bool default_left_out = false;
  for (size_t i = 0; i < codes.size(); ++i) {
    StringSwitch::Range load;
    bool is_default = i + 1 == codes.size();
    if (MatchLoad(codes[i], line, is_default, &result.second_switch, &load)) {
      result.loads.push_back(load);
    } else if (!MatchLeftOutLoad(codes[i], &result.second_switch)) {
      return std::nullopt;
    } else if (is_default) {
      default_left_out = true;
    } else {
      case_left_out = true;
    }
  }
  // Code where nothing loads an index isn't javac's. Where a load is left out,
  // the register of the second switch already holds the index. For a case,
  // that register can't be the one of the hash code. For the default code, it
  // holds -1 from before the first switch on and through the comparisons, so
  // no case can be left out too.
  int index = Register(result.second_switch, 0);
  if (result.loads.empty() ||
      (case_left_out &&
       (default_left_out ||
        index == Register(string_switch.switch_bytecode, 0)))) {
    return std::nullopt;
  }
  if (default_left_out) {
    for (const StringSwitch::Range& comparison : string_switch.comparisons) {
      if (NamesIn(comparison, index)) {
        return std::nullopt;
      }
    }
    if (!HoldsMinusOne(string_switch.switch_bytecode, index)) {
      return std::nullopt;
    }
  }
  return result;
}

}  // namespace coverage
