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

#ifndef COVERAGE_AGENT_KOTLIN_SAFE_CALL_OPERATOR_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_SAFE_CALL_OPERATOR_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the branches that the Kotlin compiler generates for chains of safe
 * calls (?.). All the null checks of a chain jump to the same code, and each
 * of them counts as having taken its null branch when that code runs. When D8
 * makes the check of an elvis operator (?:) after the chain reach that code
 * through a goto, the filter gives that check the same null branch.
 */
class KotlinSafeCallOperatorFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_SAFE_CALL_OPERATOR_FILTER_H_
