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

#ifndef COVERAGE_AGENT_KOTLIN_GENERATED_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_GENERATED_FILTER_H_

#include "filter_interface.h"

namespace coverage {

class KotlinGeneratedFilter : public IFilter {
 public:
  bool FilterBranch(ir::EncodedMethod* ir_method,
                    lir::Instruction* branch_instr) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_GENERATED_FILTER_H_
