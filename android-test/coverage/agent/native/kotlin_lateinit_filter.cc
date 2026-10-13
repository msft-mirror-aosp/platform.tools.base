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

#include "tools/base/android-test/coverage/agent/native/kotlin_lateinit_filter.h"

#include <cstring>

#include "slicer/dex_bytecode.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

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

bool IsThrowUninitializedPropertyAccessException(
    const lir::Bytecode* bytecode) {
  if (bytecode == nullptr ||
      (bytecode->opcode != dex::OP_INVOKE_STATIC &&
       bytecode->opcode != dex::OP_INVOKE_STATIC_RANGE)) {
    return false;
  }
  for (auto* operand : bytecode->operands) {
    auto* method = dynamic_cast<lir::Method*>(operand);
    if (method == nullptr || method->ir_method == nullptr) {
      continue;
    }
    const ir::MethodDecl* decl = method->ir_method;
    return decl->parent != nullptr && decl->parent->descriptor != nullptr &&
           decl->name != nullptr && decl->prototype != nullptr &&
           strcmp(decl->parent->descriptor->c_str(),
                  "Lkotlin/jvm/internal/Intrinsics;") == 0 &&
           strcmp(decl->name->c_str(),
                  "throwUninitializedPropertyAccessException") == 0 &&
           decl->prototype->Signature() == "(Ljava/lang/String;)V";
  }
  return false;
}

// Matches the const-string and the call to
// Intrinsics.throwUninitializedPropertyAccessException() that follow `instr`,
// and returns the call.
lir::Bytecode* NextIsIntrinsic(lir::Instruction* instr) {
  lir::Bytecode* cursor = NextBytecode(instr);
  if (cursor == nullptr || (cursor->opcode != dex::OP_CONST_STRING &&
                            cursor->opcode != dex::OP_CONST_STRING_JUMBO)) {
    return nullptr;
  }
  cursor = NextBytecode(cursor);
  return IsThrowUninitializedPropertyAccessException(cursor) ? cursor : nullptr;
}

// if-nez, as in direct field reads: an initialized value jumps over the code
// that throws, which ends right before the jump target. Zero to two
// instructions follow the call.
void MatchIfNez(lir::Bytecode* start, FilterOutput& output) {
  lir::Bytecode* target = SkipNonBytecodes(BranchTarget(start));
  if (target == nullptr) {
    return;
  }
  lir::Bytecode* cursor = NextIsIntrinsic(start);
  for (int extra = 0; extra <= 2 && cursor != nullptr; ++extra) {
    if (NextBytecode(cursor) == target) {
      output.Ignore(start, cursor);
      return;
    }
    cursor = NextBytecode(cursor);
  }
}

// if-eqz, as in generated getters: an uninitialized value jumps to code that
// throws and then returns or throws.
void MatchIfEqz(lir::Bytecode* start, FilterOutput& output) {
  lir::Label* label = BranchTarget(start);
  if (label == nullptr) {
    return;
  }
  lir::Bytecode* cursor = NextIsIntrinsic(label);
  cursor = NextBytecode(cursor);
  cursor = NextBytecode(cursor);
  if (cursor != nullptr && (cursor->opcode == dex::OP_RETURN_OBJECT ||
                            cursor->opcode == dex::OP_THROW)) {
    output.Ignore(start, start);
    output.Ignore(label, cursor);
  }
}

}  // namespace

void KotlinLateinitFilter::Filter(const FilterContext& context,
                                  ir::EncodedMethod* ir_method,
                                  lir::CodeIr& code_ir, FilterOutput& output) {
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    if (bytecode == nullptr) {
      continue;
    }
    if (bytecode->opcode == dex::OP_IF_NEZ) {
      MatchIfNez(bytecode, output);
    } else if (bytecode->opcode == dex::OP_IF_EQZ) {
      MatchIfEqz(bytecode, output);
    }
  }
}

REGISTER_KOTLIN_FILTER(KotlinLateinitFilter)

}  // namespace coverage
