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

#ifndef COVERAGE_AGENT_EXHAUSTIVE_SWITCH_FILTER_H_
#define COVERAGE_AGENT_EXHAUSTIVE_SWITCH_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the default case that javac adds to a switch that covers every
 * constant of an enum or every subtype of a sealed type without a default case
 * of its own. It throws MatchException or IncompatibleClassChangeError, which
 * only happens if a class changed after the switch was compiled. D8 replaces
 * MatchException with RuntimeException, with the same arguments: two nulls.
 * The default case is filtered when it is on the line of the switch, along
 * with the goto that leads to it, and the switch keeps the branches of its
 * other cases. A default case of the source that throws one of these
 * exceptions in the same way, on the line of the switch, is filtered too.
 */
class ExhaustiveSwitchFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_EXHAUSTIVE_SWITCH_FILTER_H_
