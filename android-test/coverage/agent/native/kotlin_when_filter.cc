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

#include "tools/base/android-test/coverage/agent/native/kotlin_when_filter.h"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <string>
#include <string_view>
#include <unordered_set>
#include <utility>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kException[] = "Lkotlin/NoWhenBranchMatchedException;";

// The instructions from `from` to `to`, both inclusive, in code order.
struct Range {
  lir::Instruction* from;
  lir::Instruction* to;
};

using LabelSet = std::unordered_set<const lir::Label*>;

// Returns the first bytecode at or after `instr`, skipping labels, debug
// annotations and other non-bytecode nodes.
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

// Returns the last bytecode before `instr`, or nullptr.
lir::Bytecode* PreviousBytecode(lir::Instruction* instr) {
  for (auto* node = instr->prev; node != nullptr; node = node->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(node)) {
      return bytecode;
    }
  }
  return nullptr;
}

// Returns true if `label` is between `instr` and the next bytecode.
bool LabelFollows(lir::Instruction* instr, lir::Label* label) {
  for (auto* node = instr->next; node != nullptr; node = node->next) {
    if (node == label) {
      return true;
    }
    if (dynamic_cast<lir::Bytecode*>(node) != nullptr) {
      return false;
    }
  }
  return false;
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

// Returns true if `bytecode` passes register `reg`, and nothing else, to the
// method that it invokes.
bool PassesOnly(const lir::Bytecode* bytecode, int reg) {
  if (bytecode->operands.empty()) {
    return false;
  }
  if (auto* list = dynamic_cast<lir::VRegList*>(bytecode->operands[0])) {
    return list->registers.size() == 1 &&
           static_cast<int>(list->registers[0]) == reg;
  }
  if (auto* range = dynamic_cast<lir::VRegRange*>(bytecode->operands[0])) {
    return range->count == 1 && static_cast<int>(range->base_reg) == reg;
  }
  return false;
}

bool IsMove(dex::Opcode opcode) {
  return opcode >= dex::OP_MOVE && opcode <= dex::OP_MOVE_OBJECT_16;
}

// Returns true if `opcode` copies a 32-bit value from a register.
bool IsIntMove(dex::Opcode opcode) {
  return opcode == dex::OP_MOVE || opcode == dex::OP_MOVE_FROM16 ||
         opcode == dex::OP_MOVE_16;
}

bool IsGoto(dex::Opcode opcode) {
  return opcode == dex::OP_GOTO || opcode == dex::OP_GOTO_16 ||
         opcode == dex::OP_GOTO_32;
}

bool IsSwitch(dex::Opcode opcode) {
  return opcode == dex::OP_PACKED_SWITCH || opcode == dex::OP_SPARSE_SWITCH;
}

// Returns true if `opcode` only loads a number or a string into a register.
bool IsConst(dex::Opcode opcode) {
  return opcode >= dex::OP_CONST_4 && opcode <= dex::OP_CONST_STRING_JUMBO;
}

// Returns the method that `bytecode` invokes if its opcode is `opcode` or
// `range_opcode`, or nullptr.
const ir::MethodDecl* InvokedMethod(const lir::Bytecode* bytecode,
                                    dex::Opcode opcode,
                                    dex::Opcode range_opcode) {
  if (bytecode == nullptr ||
      (bytecode->opcode != opcode && bytecode->opcode != range_opcode)) {
    return nullptr;
  }
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<lir::Method*>(operand)) {
      return method->ir_method;
    }
  }
  return nullptr;
}

// Returns true if operand `index` of `bytecode` is the type `descriptor`.
bool IsTypeOperand(const lir::Bytecode* bytecode, size_t index,
                   const char* descriptor) {
  if (index >= bytecode->operands.size()) {
    return false;
  }
  auto* type = dynamic_cast<lir::Type*>(bytecode->operands[index]);
  return type != nullptr && type->ir_type != nullptr &&
         type->ir_type->descriptor != nullptr &&
         strcmp(type->ir_type->descriptor->c_str(), descriptor) == 0;
}

// Returns true if `bytecode` calls the constructor without parameters of
// NoWhenBranchMatchedException on register `reg`.
bool IsExceptionConstructorCall(const lir::Bytecode* bytecode, int reg) {
  const ir::MethodDecl* decl = InvokedMethod(bytecode, dex::OP_INVOKE_DIRECT,
                                             dex::OP_INVOKE_DIRECT_RANGE);
  return decl != nullptr && decl->parent != nullptr &&
         decl->parent->descriptor != nullptr && decl->name != nullptr &&
         decl->prototype != nullptr &&
         strcmp(decl->parent->descriptor->c_str(), kException) == 0 &&
         strcmp(decl->name->c_str(), "<init>") == 0 &&
         decl->prototype->Signature() == "()V" && PassesOnly(bytecode, reg);
}

// Returns the bytecode that runs after `bytecode`, which belongs to the code
// that throws NoWhenBranchMatchedException. Inside try blocks, D8 can put a
// goto in that code. Then this function adds the range from `*start` to the
// goto to `ranges`, moves `*start` to the target of the goto and returns the
// first bytecode there.
lir::Bytecode* NextInThrow(lir::Bytecode* bytecode, lir::Instruction** start,
                           std::vector<Range>* ranges) {
  lir::Bytecode* next = NextBytecode(bytecode);
  if (next == nullptr || !IsGoto(next->opcode)) {
    return next;
  }
  lir::Label* target = BranchTarget(next);
  if (target == nullptr) {
    return nullptr;
  }
  ranges->push_back({*start, next});
  *start = target;
  return NextBytecode(target);
}

// Returns the ranges of the code that throws NoWhenBranchMatchedException
// from `start` on, where `new_instance` creates it, or an empty list.
std::vector<Range> MatchThrow(lir::Instruction* start,
                              lir::Bytecode* new_instance) {
  if (new_instance->opcode != dex::OP_NEW_INSTANCE ||
      !IsTypeOperand(new_instance, 1, kException)) {
    return {};
  }
  int exception = Register(new_instance, 0);
  std::vector<Range> ranges;
  lir::Bytecode* call = NextInThrow(new_instance, &start, &ranges);
  if (call == nullptr || !IsExceptionConstructorCall(call, exception)) {
    return {};
  }
  lir::Bytecode* thrown = NextInThrow(call, &start, &ranges);
  if (thrown == nullptr || thrown->opcode != dex::OP_THROW ||
      Register(thrown, 0) != exception) {
    return {};
  }
  ranges.push_back({start, thrown});
  return ranges;
}

// Appends to `targets` the distinct targets of the cases of `switch_bytecode`,
// in order, except the cases that go to the default code, at
// `default_labels`. Returns false if the payload of the switch isn't found.
bool CaseTargets(const lir::Bytecode* switch_bytecode,
                 const LabelSet& default_labels,
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
  for (lir::Label* target : cases) {
    if (default_labels.count(target) == 0 &&
        std::find(targets->begin(), targets->end(), target) == targets->end()) {
      targets->push_back(target);
    }
  }
  return true;
}

// Returns the nearest if-eqz at or before `from` that jumps to one of
// `labels`, or nullptr.
lir::Bytecode* NearestCheckTo(lir::Instruction* from, const LabelSet& labels) {
  if (labels.empty()) {
    return nullptr;
  }
  for (auto* node = from; node != nullptr; node = node->prev) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(node);
    if (bytecode != nullptr && bytecode->opcode == dex::OP_IF_EQZ &&
        labels.count(BranchTarget(bytecode)) > 0) {
      return bytecode;
    }
  }
  return nullptr;
}

// Filters the code that throws NoWhenBranchMatchedException, if
// `new_instance` creates it, along with the check or the default case of the
// switch that leads to it. The filter assumes that no other code throws that
// exception.
void FilterThrow(lir::Bytecode* new_instance, FilterOutput& output) {
  // D8 can put moves before the new-instance, at the start of that code.
  lir::Instruction* start = new_instance;
  lir::Bytecode* before = PreviousBytecode(new_instance);
  while (before != nullptr && IsMove(before->opcode)) {
    start = before;
    before = PreviousBytecode(before);
  }
  std::vector<Range> ranges = MatchThrow(start, new_instance);
  if (ranges.empty()) {
    return;
  }
  // The labels at the start of that code, where jumps to it go.
  LabelSet labels;
  for (auto* node = new_instance->prev; node != nullptr && node != before;
       node = node->prev) {
    if (auto* label = dynamic_cast<lir::Label*>(node)) {
      labels.insert(label);
    }
  }
  if (before != nullptr && IsSwitch(before->opcode)) {
    // The code is the default case of the switch, which keeps the branches
    // of its other cases.
    std::vector<lir::Instruction*> targets;
    if (!CaseTargets(before, labels, &targets)) {
      return;
    }
    output.ReplaceBranches(before, std::move(targets));
  } else {
    lir::Bytecode* check = NearestCheckTo(before, labels);
    if (check == nullptr) {
      return;
    }
    output.Ignore(check, check);
  }
  for (const Range& range : ranges) {
    output.Ignore(range.from, range.to);
  }
}

bool StartsWith(std::string_view text, std::string_view prefix) {
  return text.substr(0, prefix.size()) == prefix;
}

bool EndsWith(std::string_view text, std::string_view suffix) {
  return text.size() >= suffix.size() &&
         text.substr(text.size() - suffix.size()) == suffix;
}

// Returns true if `bytecode` reads the array that maps the ordinals of an enum
// to the cases of a `when`.
bool IsWhenMappingRead(const lir::Bytecode* bytecode) {
  if (bytecode == nullptr || bytecode->opcode != dex::OP_SGET_OBJECT ||
      bytecode->operands.size() < 2) {
    return false;
  }
  auto* field = dynamic_cast<lir::Field*>(bytecode->operands[1]);
  const ir::FieldDecl* decl = field == nullptr ? nullptr : field->ir_field;
  return decl != nullptr && decl->parent != nullptr &&
         decl->parent->descriptor != nullptr && decl->name != nullptr &&
         EndsWith(decl->parent->descriptor->c_str(), "$WhenMappings;") &&
         StartsWith(decl->name->c_str(), "$EnumSwitchMapping$");
}

// Returns true if `bytecode` calls ordinal() on register `reg`.
bool IsOrdinalCall(const lir::Bytecode* bytecode, int reg) {
  const ir::MethodDecl* decl = InvokedMethod(bytecode, dex::OP_INVOKE_VIRTUAL,
                                             dex::OP_INVOKE_VIRTUAL_RANGE);
  return decl != nullptr && decl->name != nullptr &&
         decl->prototype != nullptr &&
         strcmp(decl->name->c_str(), "ordinal") == 0 &&
         decl->prototype->Signature() == "()I" && PassesOnly(bytecode, reg);
}

// Returns true if `bytecode` loads -1. Registers above v15 take a const/16.
bool IsConstMinusOne(const lir::Bytecode* bytecode) {
  if (bytecode == nullptr ||
      (bytecode->opcode != dex::OP_CONST_4 &&
       bytecode->opcode != dex::OP_CONST_16 &&
       bytecode->opcode != dex::OP_CONST) ||
      bytecode->operands.size() < 2) {
    return false;
  }
  auto* value = dynamic_cast<lir::Const32*>(bytecode->operands[1]);
  return value != nullptr && value->u.s4_value == -1;
}

// Matches the null check of a nullable enum subject that starts with `check`:
//     if-nez vS, :not_null
//     const vK, #-1
//     goto :switch
//   :not_null
//     sget-object vA, <class>$WhenMappings;.$EnumSwitchMapping$<n>:[I
//     invoke-virtual {vS}, <enum>;.ordinal:()I
//     move-result vO
//     aget vK, vA, vO
//   :switch
//     packed-switch vK, ...  (or sparse-switch)
// When the method uses -1 more than once, D8 loads it once, earlier. Then the
// const is a move to vK, or nothing when vK already holds -1, and D8 can
// invert the check to `if-eqz vS, :switch`, followed by the code that maps the
// ordinal. D8 can also move constants that several branches load before the
// switch. Returns the aget, or nullptr.
lir::Bytecode* MatchNullableEnumCheck(lir::Bytecode* check) {
  int subject = Register(check, 0);
  // The register of the case, if the code for null sets it.
  int key = -1;
  lir::Label* join = nullptr;
  lir::Bytecode* mapping = nullptr;
  if (check->opcode == dex::OP_IF_NEZ) {
    lir::Bytecode* jump = NextBytecode(check);
    if (IsConstMinusOne(jump) || (jump != nullptr && IsIntMove(jump->opcode))) {
      key = Register(jump, 0);
      jump = NextBytecode(jump);
    }
    if (jump == nullptr || !IsGoto(jump->opcode) ||
        !LabelFollows(jump, BranchTarget(check))) {
      return nullptr;
    }
    join = BranchTarget(jump);
    mapping = NextBytecode(jump);
  } else if (check->opcode == dex::OP_IF_EQZ) {
    join = BranchTarget(check);
    mapping = NextBytecode(check);
  } else {
    return nullptr;
  }
  if (!IsWhenMappingRead(mapping)) {
    return nullptr;
  }
  lir::Bytecode* ordinal = NextBytecode(mapping);
  if (!IsOrdinalCall(ordinal, subject)) {
    return nullptr;
  }
  lir::Bytecode* result = NextBytecode(ordinal);
  if (result == nullptr || result->opcode != dex::OP_MOVE_RESULT) {
    return nullptr;
  }
  lir::Bytecode* load = NextBytecode(result);
  if (load == nullptr || load->opcode != dex::OP_AGET ||
      (key >= 0 && Register(load, 0) != key) ||
      Register(load, 1) != Register(mapping, 0) ||
      Register(load, 2) != Register(result, 0) || join == nullptr ||
      !LabelFollows(load, join)) {
    return nullptr;
  }
  key = Register(load, 0);
  // Between the label where the code for null joins and the switch, there may
  // only be constants and moves that leave the case as is, and no other jump
  // target.
  lir::Bytecode* next = nullptr;
  for (auto* node = join->next; node != nullptr; node = node->next) {
    if (dynamic_cast<lir::Label*>(node) != nullptr) {
      return nullptr;
    }
    auto* bytecode = dynamic_cast<lir::Bytecode*>(node);
    if (bytecode == nullptr) {
      continue;
    }
    if (bytecode->opcode != dex::OP_NOP && !IsConst(bytecode->opcode) &&
        !IsMove(bytecode->opcode)) {
      next = bytecode;
      break;
    }
    if (FirstOperandNames(bytecode, key)) {
      return nullptr;
    }
  }
  if (next == nullptr || !IsSwitch(next->opcode) || Register(next, 0) != key) {
    return nullptr;
  }
  return load;
}

}  // namespace

void KotlinWhenFilter::Filter(const FilterContext& context,
                              ir::EncodedMethod* ir_method,
                              lir::CodeIr& code_ir, FilterOutput& output) {
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    if (bytecode == nullptr) {
      continue;
    }
    if (bytecode->opcode == dex::OP_NEW_INSTANCE) {
      FilterThrow(bytecode, output);
    } else if (lir::Bytecode* load = MatchNullableEnumCheck(bytecode)) {
      output.Ignore(bytecode, load);
    }
  }
}

REGISTER_KOTLIN_FILTER(KotlinWhenFilter)

}  // namespace coverage
