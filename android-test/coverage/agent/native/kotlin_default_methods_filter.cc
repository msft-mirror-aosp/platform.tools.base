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

#include "tools/base/android-test/coverage/agent/native/kotlin_default_methods_filter.h"

#include <string_view>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr std::string_view kDefaultImplsSuffix = "$DefaultImpls;";
constexpr std::string_view kAccessorPrefix = "access$";
constexpr std::string_view kDefaultMethodAccessorSuffix = "$jd";

bool StartsWith(std::string_view text, std::string_view prefix) {
  return text.substr(0, prefix.size()) == prefix;
}

bool EndsWith(std::string_view text, std::string_view suffix) {
  return text.size() >= suffix.size() &&
         text.substr(text.size() - suffix.size()) == suffix;
}

// Returns true if `opcode` is a move with which the agent copies a parameter
// back to its register when it grows the frame of a method.
bool IsAgentCopy(dex::Opcode opcode) {
  return opcode == dex::OP_MOVE_16 || opcode == dex::OP_MOVE_WIDE_16 ||
         opcode == dex::OP_MOVE_OBJECT_16;
}

// Returns the method that `bytecode` invokes, or nullptr.
const ir::MethodDecl* InvokedMethod(const lir::Bytecode* bytecode) {
  if ((dex::GetFlagsFromOpcode(bytecode->opcode) & dex::kInvoke) == 0) {
    return nullptr;
  }
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<lir::Method*>(operand)) {
      return method->ir_method;
    }
  }
  return nullptr;
}

// Returns true if `bytecode` passes register `reg`, and nothing else, to the
// method that it invokes.
bool PassesOnly(const lir::Bytecode* bytecode, dex::u4 reg) {
  if (bytecode->operands.empty()) {
    return false;
  }
  if (auto* list = dynamic_cast<lir::VRegList*>(bytecode->operands[0])) {
    return list->registers.size() == 1 && list->registers[0] == reg;
  }
  if (auto* range = dynamic_cast<lir::VRegRange*>(bytecode->operands[0])) {
    return range->count == 1 && range->base_reg == reg;
  }
  return false;
}

// Returns true if the code of `ir_method` starts with a call to a static method
// of a DefaultImpls class that only passes the first parameter, which is
// `this` in an instance method.
bool StartsWithCallToDefaultImpls(const ir::EncodedMethod* ir_method,
                                  const lir::CodeIr& code_ir) {
  const ir::Code* code = ir_method->code;
  if (code == nullptr || code->ins_count == 0 ||
      code->ins_count > code->registers) {
    return false;
  }
  // The parameters arrive in the last registers of the frame. When the agent
  // grows the frame, it first copies each of them back to the register before
  // it, where the code reads it, starting with the first parameter.
  dex::u4 first_parameter = code->registers - code->ins_count;
  bool copied = false;
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<const lir::Bytecode*>(instr);
    if (bytecode == nullptr) {
      continue;
    }
    if (IsAgentCopy(bytecode->opcode)) {
      if (!copied) {
        auto* reg = bytecode->operands.empty()
                        ? nullptr
                        : dynamic_cast<lir::VReg*>(bytecode->operands[0]);
        if (reg == nullptr) {
          return false;
        }
        first_parameter = reg->reg;
        copied = true;
      }
      continue;
    }
    if (bytecode->opcode != dex::OP_INVOKE_STATIC &&
        bytecode->opcode != dex::OP_INVOKE_STATIC_RANGE) {
      return false;
    }
    const ir::MethodDecl* method = InvokedMethod(bytecode);
    return method != nullptr && method->parent != nullptr &&
           method->parent->descriptor != nullptr &&
           EndsWith(method->parent->descriptor->c_str(), kDefaultImplsSuffix) &&
           PassesOnly(bytecode, first_parameter);
  }
  return false;
}

// Returns true if `code_ir` calls an accessor access$<name>$jd, through which
// the methods of a DefaultImpls class call the default methods of their
// interface.
bool CallsDefaultMethodAccessor(const lir::CodeIr& code_ir) {
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<const lir::Bytecode*>(instr);
    const ir::MethodDecl* method =
        bytecode == nullptr ? nullptr : InvokedMethod(bytecode);
    if (method == nullptr || method->name == nullptr) {
      continue;
    }
    std::string_view name = method->name->c_str();
    if (StartsWith(name, kAccessorPrefix) &&
        EndsWith(name, kDefaultMethodAccessorSuffix)) {
      return true;
    }
  }
  return false;
}

}  // namespace

void KotlinDefaultMethodsFilter::Filter(const FilterContext& context,
                                        ir::EncodedMethod* ir_method,
                                        lir::CodeIr& code_ir,
                                        FilterOutput& output) {
  if (!StartsWithCallToDefaultImpls(ir_method, code_ir) &&
      !(EndsWith(context.class_name, kDefaultImplsSuffix) &&
        CallsDefaultMethodAccessor(code_ir))) {
    return;
  }
  lir::Instruction* last = nullptr;
  for (auto* instr : code_ir.instructions) {
    last = instr;
  }
  if (last != nullptr) {
    output.Ignore(*code_ir.instructions.begin(), last);
  }
}

REGISTER_KOTLIN_FILTER(KotlinDefaultMethodsFilter)

}  // namespace coverage
