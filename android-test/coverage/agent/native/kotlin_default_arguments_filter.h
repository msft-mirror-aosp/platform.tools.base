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

#ifndef COVERAGE_AGENT_KOTLIN_DEFAULT_ARGUMENTS_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_DEFAULT_ARGUMENTS_FILTER_H_

#include <cstddef>

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the branches that the Kotlin compiler generates for default
 * arguments, in the synthetic $default methods and constructors that evaluate
 * the default values: the check of each default value, and the check that open
 * functions make to reject super calls.
 */
class KotlinDefaultArgumentsFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;

  // Returns how many of the `parameters` parameters of a $default method or
  // constructor are masks of the default values to use: one for every 32
  // parameters of the function or constructor.
  static size_t NumberOfMaskParameters(size_t parameters);
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_DEFAULT_ARGUMENTS_FILTER_H_
