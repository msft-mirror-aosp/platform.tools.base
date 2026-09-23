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

#ifndef COVERAGE_AGENT_SYNTHETIC_FILTER_H_
#define COVERAGE_AGENT_SYNTHETIC_FILTER_H_

#include "slicer/code_ir.h"
#include "slicer/control_flow_graph.h"

namespace coverage {

/**
 * High-level orchestrator for branch filtering.
 *
 * Queries the statically registered IFilter collection to determine if a basic
 * block's conditional branching should be demoted to sequential block flow,
 * keeping coverage focused on user-written code.
 */
class SyntheticFilter {
 public:
  // Checks if the given basic block represents a compiler-generated/synthetic
  // branch to suppress.
  static bool IsSyntheticBranch(ir::EncodedMethod* ir_method,
                               const lir::BasicBlock& block);
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_SYNTHETIC_FILTER_H_
