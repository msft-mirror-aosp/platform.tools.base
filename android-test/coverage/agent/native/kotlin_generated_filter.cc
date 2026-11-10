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

#include "tools/base/android-test/coverage/agent/native/kotlin_generated_filter.h"

#include "slicer/code_ir.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

// Returns the annotation of the first line number of the method, or nullptr if
// the method has no line numbers.
//
// Slicer places the annotation of each line number right before the first
// instruction of the line. In a method with debug information, it also places
// an annotation of the start line of the debug information before them, which
// is not a line number of the method.
lir::DbgInfoAnnotation* FindFirstLine(const lir::CodeIr& code_ir) {
  bool skipped_start_line = false;
  for (auto* instr : code_ir.instructions) {
    auto* annotation = dynamic_cast<lir::DbgInfoAnnotation*>(instr);
    if (annotation == nullptr ||
        annotation->dbg_opcode != dex::DBG_ADVANCE_LINE) {
      continue;
    }
    if (skipped_start_line) {
      return annotation;
    }
    skipped_start_line = true;
  }
  return nullptr;
}

}  // namespace

void KotlinGeneratedFilter::Filter(const FilterContext& context,
                                   ir::EncodedMethod* ir_method,
                                   lir::CodeIr& code_ir, FilterOutput& output) {
  if (context.source_file.empty()) {
    // The class was probably compiled without debug information. Its methods
    // then have no line numbers, but they are not all generated.
    return;
  }
  lir::DbgInfoAnnotation* first_line = FindFirstLine(code_ir);
  if (first_line != nullptr) {
    // Ignores the code before the first line number.
    for (auto* instr : code_ir.instructions) {
      if (instr == first_line) {
        break;
      }
      if (dynamic_cast<lir::Bytecode*>(instr) != nullptr) {
        output.Ignore(instr, instr);
      }
    }
    return;
  }
  // Ignores the whole method, which has no line numbers.
  lir::Instruction* last = nullptr;
  for (auto* instr : code_ir.instructions) {
    last = instr;
  }
  if (last != nullptr) {
    output.Ignore(*code_ir.instructions.begin(), last);
  }
}

REGISTER_KOTLIN_FILTER(KotlinGeneratedFilter)

}  // namespace coverage
