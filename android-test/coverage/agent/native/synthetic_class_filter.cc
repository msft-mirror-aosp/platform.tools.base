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

#include "tools/base/android-test/coverage/agent/native/synthetic_class_filter.h"

#include <string_view>

#include "slicer/code_ir.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

// The suffix of the companion class to which D8 moves the default and static
// methods of an interface when it desugars them.
constexpr std::string_view kCompanionClassSuffix = "$-CC;";

bool EndsWith(std::string_view text, std::string_view suffix) {
  return text.size() >= suffix.size() &&
         text.substr(text.size() - suffix.size()) == suffix;
}

}  // namespace

void SyntheticClassFilter::Filter(const FilterContext& context,
                                  ir::EncodedMethod* ir_method,
                                  lir::CodeIr& code_ir, FilterOutput& output) {
  if ((context.class_access_flags & dex::kAccSynthetic) == 0 ||
      EndsWith(context.class_name, kCompanionClassSuffix)) {
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

REGISTER_FILTER(SyntheticClassFilter)

}  // namespace coverage
