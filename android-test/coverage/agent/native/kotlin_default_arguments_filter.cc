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

#include "tools/base/android-test/coverage/agent/native/kotlin_default_arguments_filter.h"

#include <cstdint>
#include <cstring>
#include <string_view>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr std::string_view kDefaultSuffix = "$default";
constexpr char kDefaultConstructorMarker[] =
    "Lkotlin/jvm/internal/DefaultConstructorMarker;";
constexpr char kUnsupportedOperationException[] =
    "Ljava/lang/UnsupportedOperationException;";
constexpr std::string_view kSuperCallsMessage =
    "Super calls with default arguments not supported in this target";

// What a register holds, as far as this filter is concerned.
enum : uint8_t {
  // A mask parameter: one bit for each parameter whose default value to use.
  kMask = 1 << 0,
  // The last parameter, which open functions check to reject super calls.
  kLastParameter = 1 << 1,
  // A mask and the bit of a parameter: whether to use its default value.
  kMaskBit = 1 << 2,
};

// Returns true if `ir_method` is a $default method, which evaluates the
// default values of a function.
bool IsDefaultArgumentsMethod(const ir::EncodedMethod* ir_method) {
  std::string_view name = ir_method->decl->name->c_str();
  return name.size() >= kDefaultSuffix.size() &&
         name.substr(name.size() - kDefaultSuffix.size()) == kDefaultSuffix;
}

// Returns true if `ir_method` is a constructor that evaluates the default
// values of another constructor. Its last parameter is the marker.
bool IsDefaultArgumentsConstructor(const ir::EncodedMethod* ir_method) {
  const ir::TypeList* params = ir_method->decl->prototype->param_types;
  return strcmp(ir_method->decl->name->c_str(), "<init>") == 0 &&
         params != nullptr && params->types.size() >= 2 &&
         strcmp(params->types.back()->descriptor->c_str(),
                kDefaultConstructorMarker) == 0;
}

// Returns the register of operand `index` of `bytecode`, the first one of a
// register pair, or -1.
int Register(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return -1;
  }
  if (auto* reg = dynamic_cast<lir::VReg*>(bytecode->operands[index])) {
    return static_cast<int>(reg->reg);
  }
  if (auto* pair = dynamic_cast<lir::VRegPair*>(bytecode->operands[index])) {
    return static_cast<int>(pair->base_reg);
  }
  return -1;
}

// Returns true if operand `index` of `bytecode` is a register pair.
bool IsPair(const lir::Bytecode* bytecode, size_t index) {
  return index < bytecode->operands.size() &&
         dynamic_cast<lir::VRegPair*>(bytecode->operands[index]) != nullptr;
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

// Returns the target of a branch instruction, or nullptr.
lir::Label* BranchTarget(const lir::Bytecode* branch) {
  for (auto* operand : branch->operands) {
    if (auto* code_location = dynamic_cast<lir::CodeLocation*>(operand)) {
      return code_location->label;
    }
  }
  return nullptr;
}

// Returns the throw that ends the code starting at `first`, if that code is:
//
//   new-instance vX, UnsupportedOperationException
//   const-string vY, "Super calls with default arguments not supported..."
//   invoke-direct {vX, vY}, UnsupportedOperationException.<init>(String)
//   throw vX
lir::Bytecode* MatchSuperCallsError(lir::Bytecode* first) {
  if (first == nullptr || first->opcode != dex::OP_NEW_INSTANCE ||
      first->operands.size() < 2) {
    return nullptr;
  }
  auto* type = dynamic_cast<lir::Type*>(first->operands[1]);
  if (type == nullptr || type->ir_type == nullptr ||
      type->ir_type->descriptor == nullptr ||
      strcmp(type->ir_type->descriptor->c_str(),
             kUnsupportedOperationException) != 0) {
    return nullptr;
  }
  lir::Bytecode* message = NextBytecode(first);
  if (message == nullptr ||
      (message->opcode != dex::OP_CONST_STRING &&
       message->opcode != dex::OP_CONST_STRING_JUMBO) ||
      message->operands.size() < 2) {
    return nullptr;
  }
  auto* text = dynamic_cast<lir::String*>(message->operands[1]);
  if (text == nullptr || text->ir_string == nullptr) {
    return nullptr;
  }
  std::string_view value = text->ir_string->c_str();
  if (value.substr(0, kSuperCallsMessage.size()) != kSuperCallsMessage) {
    return nullptr;
  }
  lir::Bytecode* init = NextBytecode(message);
  if (init == nullptr || init->opcode != dex::OP_INVOKE_DIRECT ||
      init->operands.size() < 2) {
    return nullptr;
  }
  auto* method = dynamic_cast<lir::Method*>(init->operands[1]);
  const ir::MethodDecl* decl = method == nullptr ? nullptr : method->ir_method;
  if (decl == nullptr || decl->parent == nullptr ||
      decl->parent->descriptor == nullptr || decl->name == nullptr ||
      decl->prototype == nullptr ||
      strcmp(decl->parent->descriptor->c_str(),
             kUnsupportedOperationException) != 0 ||
      strcmp(decl->name->c_str(), "<init>") != 0 ||
      decl->prototype->Signature() != "(Ljava/lang/String;)V") {
    return nullptr;
  }
  lir::Bytecode* error = NextBytecode(init);
  return error != nullptr && error->opcode == dex::OP_THROW ? error : nullptr;
}

// Ignores `jump` on the last parameter and the code that it leads to, if that
// code rejects a super call:
//
//   if-nez vLast, :error
//   ...
//   :error
//   new-instance vX, UnsupportedOperationException
//   ...
//   throw vX
//
// D8 moves that code to the end of the method. Without that, the code follows
// an if-eqz.
void IgnoreSuperCallsCheck(lir::Bytecode* jump, FilterOutput& output) {
  lir::Bytecode* first = nullptr;
  if (jump->opcode == dex::OP_IF_NEZ) {
    lir::Label* target = BranchTarget(jump);
    first = target == nullptr ? nullptr : SkipNonBytecodes(target);
  } else {
    first = NextBytecode(jump);
  }
  lir::Bytecode* error = MatchSuperCallsError(first);
  if (error != nullptr) {
    output.Ignore(jump, jump);
    output.Ignore(first, error);
  }
}

}  // namespace

size_t KotlinDefaultArgumentsFilter::NumberOfMaskParameters(size_t parameters) {
  return parameters < 2 ? 0 : (parameters - 2) / 33 + 1;
}

void KotlinDefaultArgumentsFilter::Filter(const FilterContext& context,
                                          ir::EncodedMethod* ir_method,
                                          lir::CodeIr& code_ir,
                                          FilterOutput& output) {
  if ((ir_method->access_flags & dex::kAccSynthetic) == 0 ||
      ir_method->decl == nullptr || ir_method->decl->name == nullptr ||
      ir_method->decl->prototype == nullptr ||
      (!IsDefaultArgumentsMethod(ir_method) &&
       !IsDefaultArgumentsConstructor(ir_method))) {
    return;
  }
  const ir::Code* code = ir_method->code;
  const ir::TypeList* params = ir_method->decl->prototype->param_types;
  if (code == nullptr || params == nullptr || params->types.size() < 2 ||
      code->ins_count > code->registers) {
    return;
  }

  // The parameters are in the last registers, after `this` for constructors.
  // The masks are right before the last parameter.
  std::vector<uint8_t> holds(code->registers, 0);
  const size_t count = params->types.size();
  const size_t first_mask = count - 1 - NumberOfMaskParameters(count);
  size_t reg = code->registers - code->ins_count;
  if ((ir_method->access_flags & dex::kAccStatic) == 0) {
    ++reg;
  }
  for (size_t i = 0; i < count && reg < holds.size(); ++i) {
    if (i == count - 1) {
      holds[reg] = kLastParameter;
    } else if (i >= first_mask) {
      holds[reg] = kMask;
    }
    const char* descriptor = params->types[i]->descriptor->c_str();
    reg += (descriptor[0] == 'J' || descriptor[0] == 'D') ? 2 : 1;
  }
  auto has = [&holds](int r, uint8_t what) {
    return r >= 0 && static_cast<size_t>(r) < holds.size() &&
           (holds[r] & what) != 0;
  };
  auto set = [&holds](int r, uint8_t what) {
    if (r >= 0 && static_cast<size_t>(r) < holds.size()) {
      holds[r] = what;
    }
  };

  // Follows what the registers hold through the code. D8 can copy a mask to
  // another register, load the bit into a register, and move a constant
  // between the check of the bit and its jump. One pass in code order is
  // enough: D8 keeps the checks in order, and only reuses the register of a
  // mask after its last check.
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    if (bytecode == nullptr) {
      continue;
    }
    const dex::Opcode op = bytecode->opcode;
    const int dest = Register(bytecode, 0);
    if (op == dex::OP_IF_EQZ || op == dex::OP_IF_NEZ) {
      if (has(dest, kMaskBit)) {
        // if ((mask & bit) != 0) { the default value }
        output.Ignore(bytecode, bytecode);
      } else if (has(dest, kLastParameter)) {
        IgnoreSuperCallsCheck(bytecode, output);
      }
      continue;
    }
    uint8_t value = 0;
    if (op >= dex::OP_MOVE && op <= dex::OP_MOVE_OBJECT_16) {
      const int src = Register(bytecode, 1);
      if (src >= 0 && static_cast<size_t>(src) < holds.size()) {
        value = holds[src];
      }
    } else if (op == dex::OP_AND_INT_LIT8 || op == dex::OP_AND_INT_LIT16) {
      if (has(Register(bytecode, 1), kMask)) {
        value = kMaskBit;
      }
    } else if (op == dex::OP_AND_INT || op == dex::OP_AND_INT_2ADDR) {
      // and-int vA, vB, vC, or and-int/2addr vA, vB for vA = vA & vB.
      const int x = Register(bytecode, op == dex::OP_AND_INT ? 1 : 0);
      const int y = Register(bytecode, op == dex::OP_AND_INT ? 2 : 1);
      if (has(x, kMask) || has(y, kMask)) {
        value = kMaskBit;
      }
    } else if (!WritesFirstOperand(op)) {
      continue;
    }
    set(dest, value);
    if (IsPair(bytecode, 0)) {
      set(dest + 1, 0);
    }
  }
}

REGISTER_KOTLIN_FILTER(KotlinDefaultArgumentsFilter)

}  // namespace coverage
