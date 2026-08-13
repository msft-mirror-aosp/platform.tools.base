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

#include "tools/base/android-test/coverage/agent/native/register_scanner.h"
#include <algorithm>
#include <cstring>

namespace coverage {

namespace {

// Visitor implementation that traverses Slicer's LIR instruction operands and
// marks any register index read or written in any instruction as "used".
class RegisterUsageScannerImpl : public lir::Visitor {
 public:
  std::vector<bool> used;

  explicit RegisterUsageScannerImpl(dex::u4 regs) : used(regs, false) {}

  virtual bool Visit(lir::Bytecode* bytecode) override {
    for (auto operand : bytecode->operands) {
      if (operand != nullptr) {
        operand->Accept(this);
      }
    }
    return true;
  }

  virtual bool Visit(lir::DbgInfoAnnotation* dbg_annotation) override {
    for (auto operand : dbg_annotation->operands) {
      if (operand != nullptr) {
        operand->Accept(this);
      }
    }
    return true;
  }

  // Visit a single 32-bit register operand.
  virtual bool Visit(lir::VReg* vreg) override {
    if (vreg->reg < used.size()) {
      used[vreg->reg] = true;
    }
    return true;
  }

  // Visit a 64-bit wide register pair (covers base_reg and base_reg + 1).
  virtual bool Visit(lir::VRegPair* vreg_pair) override {
    if (vreg_pair->base_reg < used.size()) {
      used[vreg_pair->base_reg] = true;
    }
    if (vreg_pair->base_reg + 1 < used.size()) {
      used[vreg_pair->base_reg + 1] = true;
    }
    return true;
  }

  // Visit a range of registers (e.g. for invoke-direct/range).
  virtual bool Visit(lir::VRegRange* vreg_range) override {
    for (int i = 0; i < vreg_range->count; ++i) {
      if (vreg_range->base_reg + i < used.size()) {
        used[vreg_range->base_reg + i] = true;
      }
    }
    return true;
  }

  // Visit an explicit list of registers (e.g. for standard invoke-static).
  virtual bool Visit(lir::VRegList* vreg_list) override {
    for (auto reg : vreg_list->registers) {
      if (reg < used.size()) {
        used[reg] = true;
      }
    }
    return true;
  }
};

}  // namespace

dex::u4 RegisterScanner::FindUnusedScratchRegister(ir::EncodedMethod* ir_method,
                                                   lir::CodeIr& code_ir,
                                                   bool& found_unused) {
  found_unused = false;
  dex::u4 scratch_reg = 0;

  // 1. Visit all instructions in the method to map active register usage.
  RegisterUsageScannerImpl scanner(ir_method->code->registers);
  for (auto instr : code_ir.instructions) {
    instr->Accept(&scanner);
  }

  // 2. Scan for a completely unused register index.
  // We limit our search strictly to index < 16 because standard Dalvik
  // instructions (like OP_CONST/4 or OP_INVOKE_STATIC) use a 4-bit field and
  // can only address registers v0 to v15.
  dex::u4 registers = ir_method->code->registers;
  dex::u4 ins_count = ir_method->code->ins_count;
  if (registers >= ins_count) {
    dex::u4 local_regs = registers - ins_count;
    dex::u4 limit = std::min(local_regs, 16U);
    for (dex::u4 i = 0; i < limit; ++i) {
      if (!scanner.used[i]) {
        scratch_reg = i;
        found_unused = true;
        break;
      }
    }
  }

  return scratch_reg;
}

}  // namespace coverage
