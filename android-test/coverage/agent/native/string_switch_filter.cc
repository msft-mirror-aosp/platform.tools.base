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

#include "tools/base/android-test/coverage/agent/native/string_switch_filter.h"

#include <algorithm>
#include <optional>
#include <utility>
#include <vector>

#include "slicer/code_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"
#include "tools/base/android-test/coverage/agent/native/string_switch_matcher.h"

namespace coverage {

namespace {

// Adds `target` to `targets` unless it is already there.
void AddTarget(lir::Instruction* target,
               std::vector<lir::Instruction*>* targets) {
  if (std::find(targets->begin(), targets->end(), target) == targets->end()) {
    targets->push_back(target);
  }
}

}  // namespace

void StringSwitchFilter::Filter(const FilterContext& context,
                                ir::EncodedMethod* ir_method,
                                lir::CodeIr& code_ir, FilterOutput& output) {
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    std::optional<StringSwitch> string_switch =
        bytecode == nullptr ? std::nullopt : MatchStringSwitch(bytecode);
    // In classes that javac compiles, StringSwitchJavacFilter filters the code
    // that javac generates, and cases that start on the line of the switch are
    // javac's loads of the index, which go on with a second switch. This
    // filter leaves both alone.
    if (!string_switch.has_value() ||
        (!context.IsKotlinClass() &&
         (CasesStartOnLineOfSwitch(*string_switch) ||
          MatchJavacStringSwitch(*string_switch).has_value()))) {
      continue;
    }
    // Only the Kotlin compiler checks the String for null. The check runs
    // whenever the switch does, and for null too, so it gets the branches
    // instead of the switch.
    lir::Bytecode* null_check =
        context.IsKotlinClass() ? string_switch->null_check : nullptr;
    // When other code goes to the default code too, only the goto after the
    // switch, if there is one, runs for the default branch alone, and it
    // stands for that branch: jumps straight to the default code, such as the
    // null check's or a failed comparison's, don't cover it. In other classes,
    // a check that looks like the null check is other code.
    bool default_code_shared =
        string_switch->default_code_shared ||
        (null_check == nullptr && string_switch->null_check != nullptr &&
         string_switch->null_code == nullptr);
    lir::Instruction* default_target =
        default_code_shared && string_switch->default_goto != nullptr
            ? string_switch->default_goto
            : string_switch->default_code;
    std::vector<lir::Instruction*> targets = {default_target};
    for (lir::Bytecode* code : string_switch->cases) {
      AddTarget(code, &targets);
    }
    if (null_check != nullptr && string_switch->null_code != nullptr) {
      AddTarget(string_switch->null_code, &targets);
    }
    output.Ignore(string_switch->hash_code.from, string_switch->hash_code.to);
    if (null_check != nullptr) {
      output.Ignore(string_switch->switch_bytecode,
                    string_switch->switch_bytecode);
    }
    if (string_switch->default_goto != nullptr) {
      output.Ignore(string_switch->default_goto, string_switch->default_goto);
    }
    for (const StringSwitch::Range& comparison : string_switch->comparisons) {
      output.Ignore(comparison.from, comparison.to);
    }
    for (lir::Bytecode* jump : string_switch->gotos) {
      output.Ignore(jump, jump);
    }
    output.ReplaceBranches(
        null_check != nullptr ? null_check : string_switch->switch_bytecode,
        std::move(targets));
  }
}

REGISTER_FILTER(StringSwitchFilter)

}  // namespace coverage
