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

#include "tools/base/android-test/coverage/agent/native/kotlin_jvm_overloads_filter.h"

#include <string>
#include <string_view>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr std::string_view kDefaultSuffix = "$default";
constexpr std::string_view kDefaultConstructorMarkerSuffix =
    "Lkotlin/jvm/internal/DefaultConstructorMarker;)V";

bool EndsWith(std::string_view text, std::string_view suffix) {
  return text.size() >= suffix.size() &&
         text.compare(text.size() - suffix.size(), suffix.size(), suffix) == 0;
}

// Returns true if `instr` is the annotation of a line number, including the
// annotation of the start line of the debug information, which slicer adds
// first and which isn't a line of the method.
bool IsLine(const lir::Instruction* instr) {
  auto* annotation = dynamic_cast<const lir::DbgInfoAnnotation*>(instr);
  return annotation != nullptr &&
         annotation->dbg_opcode == dex::DBG_ADVANCE_LINE;
}

// Returns true if `opcode` is a move with which the agent copies a parameter
// back to its register when it grows the frame of a method.
bool IsAgentCopy(dex::Opcode opcode) {
  return opcode == dex::OP_MOVE_16 || opcode == dex::OP_MOVE_WIDE_16 ||
         opcode == dex::OP_MOVE_OBJECT_16;
}

// Returns the method that the invoke `bytecode` calls, or nullptr.
const ir::MethodDecl* Callee(const lir::Bytecode* bytecode) {
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<const lir::Method*>(operand)) {
      const ir::MethodDecl* decl = method->ir_method;
      if (decl != nullptr && decl->name != nullptr &&
          decl->prototype != nullptr) {
        return decl;
      }
      return nullptr;
    }
  }
  return nullptr;
}

// Returns true if `bytecode` calls the $default method of a function, or a
// constructor that takes a DefaultConstructorMarker.
bool CallsWithDefaults(const lir::Bytecode* bytecode) {
  const ir::MethodDecl* method = Callee(bytecode);
  if (method == nullptr) {
    return false;
  }
  switch (bytecode->opcode) {
    case dex::OP_INVOKE_STATIC:
    case dex::OP_INVOKE_STATIC_RANGE:
      return EndsWith(method->name->c_str(), kDefaultSuffix);
    case dex::OP_INVOKE_DIRECT:
    case dex::OP_INVOKE_DIRECT_RANGE:
      return EndsWith(method->prototype->Signature(),
                      kDefaultConstructorMarkerSuffix);
    default:
      return false;
  }
}

}  // namespace

void KotlinJvmOverloadsFilter::Filter(const FilterContext& context,
                                      ir::EncodedMethod* ir_method,
                                      lir::CodeIr& code_ir,
                                      FilterOutput& output) {
  // The last two bytecodes other than the copies of the agent, and the number
  // of line annotations before each. When the agent grows the frame of a
  // constructor, it copies the parameters back right after the call of the
  // other constructor.
  lir::Bytecode* call = nullptr;
  lir::Bytecode* return_bytecode = nullptr;
  int lines_before_call = 0;
  int lines_before_return = 0;
  int lines = 0;
  lir::Instruction* last = nullptr;
  for (auto* instr : code_ir.instructions) {
    last = instr;
    if (IsLine(instr)) {
      ++lines;
    } else if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
               bytecode != nullptr && !IsAgentCopy(bytecode->opcode)) {
      call = return_bytecode;
      lines_before_call = lines_before_return;
      return_bytecode = bytecode;
      lines_before_return = lines;
    }
  }
  // Only the start line comes before the call, and a line starts after it,
  // with the return.
  if (call == nullptr || return_bytecode->opcode != dex::OP_RETURN_VOID ||
      lines_before_call > 1 || lines_before_return == lines_before_call ||
      !CallsWithDefaults(call)) {
    return;
  }
  output.Ignore(*code_ir.instructions.begin(), last);
}

REGISTER_KOTLIN_FILTER(KotlinJvmOverloadsFilter)

}  // namespace coverage
