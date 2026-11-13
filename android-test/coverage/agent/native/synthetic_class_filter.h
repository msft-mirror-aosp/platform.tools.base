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

#ifndef COVERAGE_AGENT_SYNTHETIC_CLASS_FILTER_H_
#define COVERAGE_AGENT_SYNTHETIC_CLASS_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the methods of synthetic classes, which compilers generate, such as
 * the classes of Kotlin function and property references. The parts of Kotlin
 * multifile classes are synthetic too, so their code isn't reported. D8 marks
 * the companion classes of interfaces that it desugars as synthetic too, but
 * they hold the default and static methods of the interfaces, so they are kept.
 */
class SyntheticClassFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_SYNTHETIC_CLASS_FILTER_H_
