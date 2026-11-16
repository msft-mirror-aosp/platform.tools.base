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

#ifndef COVERAGE_AGENT_KOTLIN_JVM_OVERLOADS_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_JVM_OVERLOADS_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the methods that the Kotlin compiler generates for the functions and
 * constructors annotated with @JvmOverloads. Each one passes its arguments,
 * placeholders for the other parameters and the mask of the parameters that
 * take their default values to the $default method of the function, or to the
 * constructor that takes a DefaultConstructorMarker. KotlinGeneratedFilter
 * ignores them when they have no line numbers, but Kotlin 2.0 and 2.1 give them
 * one: the last line of the declaration, on the return after the call. As in
 * the build-time report, only the overloads that return nothing are filtered;
 * those that return a value keep their return.
 */
class KotlinJvmOverloadsFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_JVM_OVERLOADS_FILTER_H_
