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

#ifndef COVERAGE_AGENT_KOTLIN_WHEN_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_WHEN_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the code that the Kotlin compiler generates for `when` expressions
 * and statements whose subject is an enum, a sealed class or a sealed
 * interface:
 * - A `when` whose branches cover every case still throws
 *   NoWhenBranchMatchedException when none of them matches, which only
 *   happens if the subject's type changed after the `when` was compiled. The
 *   throw is filtered, along with the check or the default case of the switch
 *   that leads to it.
 * - A nullable enum subject is checked for null before the switch on its
 *   case, which handles null as case -1. That check is filtered.
 */
class KotlinWhenFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_WHEN_FILTER_H_
