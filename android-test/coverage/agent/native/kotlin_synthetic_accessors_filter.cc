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

#include "tools/base/android-test/coverage/agent/native/kotlin_synthetic_accessors_filter.h"

#include <string_view>

#include "slicer/code_ir.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr std::string_view kAccessorPrefix = "access$";

}  // namespace

void KotlinSyntheticAccessorsFilter::Filter(const FilterContext& context,
                                            ir::EncodedMethod* ir_method,
                                            lir::CodeIr& code_ir,
                                            FilterOutput& output) {
  std::string_view name = ir_method->decl->name->c_str();
  if ((ir_method->access_flags & dex::kAccSynthetic) == 0 ||
      name.substr(0, kAccessorPrefix.size()) != kAccessorPrefix) {
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

REGISTER_KOTLIN_FILTER(KotlinSyntheticAccessorsFilter)

}  // namespace coverage
