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

#include "tools/base/android-test/coverage/agent/native/string_switch_javac_filter.h"

#include <optional>

#include "slicer/code_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"
#include "tools/base/android-test/coverage/agent/native/string_switch_matcher.h"

namespace coverage {

void StringSwitchJavacFilter::Filter(const FilterContext& context,
                                     ir::EncodedMethod* ir_method,
                                     lir::CodeIr& code_ir,
                                     FilterOutput& output) {
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    std::optional<StringSwitch> string_switch =
        bytecode == nullptr ? std::nullopt : MatchStringSwitch(bytecode);
    std::optional<JavacStringSwitch> javac =
        string_switch.has_value() ? MatchJavacStringSwitch(*string_switch)
                                  : std::nullopt;
    if (!javac.has_value()) {
      continue;
    }
    output.Ignore(string_switch->switch_bytecode,
                  string_switch->switch_bytecode);
    if (string_switch->default_goto != nullptr) {
      output.Ignore(string_switch->default_goto, string_switch->default_goto);
    }
    for (const StringSwitch::Range& comparison : string_switch->comparisons) {
      output.Ignore(comparison.from, comparison.to);
    }
    for (lir::Bytecode* jump : string_switch->gotos) {
      output.Ignore(jump, jump);
    }
    for (const StringSwitch::Range& load : javac->loads) {
      output.Ignore(load.from, load.to);
    }
  }
}

REGISTER_NON_KOTLIN_FILTER(StringSwitchJavacFilter)

}  // namespace coverage
