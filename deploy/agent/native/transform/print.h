/*
 * Copyright (C) 2025 The Android Open Source Project
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
 *
 */

#ifndef PRINT_H
#define PRINT_H

#include <sstream>

#include "slicer/instrumentation.h"

class BytecodeToString final : public lir::Visitor {
 public:
  std::string GetBytecodeString();
  bool Visit(lir::Bytecode* bytecode) override;
  bool Visit(lir::Label* label) override;
  bool Visit(lir::CodeLocation* location) override;
  bool Visit(lir::Const32* const32) override;
  bool Visit(lir::Const64* const64) override;
  bool Visit(lir::VReg* vreg) override;
  bool Visit(lir::VRegPair* vreg_pair) override;
  bool Visit(lir::VRegList* vreg_list) override;
  bool Visit(lir::VRegRange* vreg_range) override;
  bool Visit(lir::String* string) override;
  bool Visit(lir::Type* type) override;
  bool Visit(lir::Field* field) override;
  bool Visit(lir::Method* method) override;

 private:
  lir::Bytecode* bytecode_ = nullptr;
  lir::Label* label_ = nullptr;
  std::ostringstream operands_;
};

// No-op transform that prints the bytecode (opcode + operands) of a method to
// logcat. Intended for debugging transforms locally, not for final builds.
class Print final : public slicer::Transformation {
 public:
  bool Apply(lir::CodeIr* code_ir) override;
};

#endif  // PRINT_H
