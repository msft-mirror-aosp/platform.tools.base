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

#ifndef COVERAGE_CONSTRUCTOR_ANALYZER_H_
#define COVERAGE_CONSTRUCTOR_ANALYZER_H_

#include <string_view>
#include <vector>
#include "slicer/code_ir.h"

namespace coverage {

class ConstructorAnalyzer {
 public:
  // Analyzes the method to locate all super() or this() constructor call
  // instructions. Returns an empty vector if not found or if the method is not
  // a constructor.
  static std::vector<lir::Instruction*> FindSuperCallInstructions(
      lir::CodeIr& code_ir, std::string_view method_name);

  // Returns whether the method is a constructor that Kotlin generates for
  // default arguments: a synthetic <init> with line numbers, whose parameters
  // end with an int mask and a kotlin.jvm.internal.DefaultConstructorMarker.
  static bool IsDefaultArgumentsConstructor(const ir::EncodedMethod* ir_method);

  // Returns the this() call of a constructor for default arguments, or nullptr
  // if the constructor doesn't have the exact shape that Kotlin generates.
  static lir::Instruction* FindDefaultArgumentsDelegation(lir::CodeIr& code_ir);
};

}  // namespace coverage

#endif  // COVERAGE_CONSTRUCTOR_ANALYZER_H_
