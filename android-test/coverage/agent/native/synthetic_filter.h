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

#include <unordered_map>
#include <unordered_set>
#include <vector>
#include "slicer/code_ir.h"
#include "slicer/control_flow_graph.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Collects the decisions of all filters that ran over one method and answers
 * the instrumenter's per-instruction and per-block queries.
 */
class FilterResult : public FilterOutput {
 public:
  // FilterOutput:
  void Ignore(lir::Instruction* from, lir::Instruction* to) override;
  void ReplaceBranches(lir::Instruction* source,
                       std::vector<lir::Instruction*> targets) override;

  // Legacy: records that the branch at the end of a block was suppressed by
  // IFilter::FilterBranch().
  void SuppressBranch(const lir::Instruction* branch_instr);

  bool IsIgnored(const lir::Instruction* instr) const {
    return ignored_.count(instr) > 0;
  }

  bool IsBranchSuppressed(const lir::Instruction* branch_instr) const {
    return suppressed_branches_.count(branch_instr) > 0;
  }

  // Returns the replacement branch targets registered for the block terminated
  // by `source`, or nullptr if its branches were not replaced.
  const std::vector<lir::Instruction*>* GetReplacedBranches(
      const lir::Instruction* source) const;

 private:
  std::unordered_set<const lir::Instruction*> ignored_;
  std::unordered_set<const lir::Instruction*> suppressed_branches_;
  std::unordered_map<const lir::Instruction*, std::vector<lir::Instruction*>>
      replaced_branches_;
};

/**
 * High-level orchestrator for coverage filtering.
 *
 * Runs the statically registered IFilter collection over a method once and
 * returns the combined result, keeping coverage focused on user-written code.
 */
class SyntheticFilter {
 public:
  // Returns the filter sets that apply to a class: the common set plus either
  // the Kotlin set or the non-Kotlin set.
  static std::vector<FilterSet> SelectFilterSets(const FilterContext& context);

  // Runs all registered filters applicable to `context` over the method and
  // returns their combined decisions. `blocks` are the method's basic blocks.
  static FilterResult Apply(const FilterContext& context,
                            ir::EncodedMethod* ir_method, lir::CodeIr& code_ir,
                            const std::vector<lir::BasicBlock>& blocks);

  // Same as above with an explicit list of filters (used by tests).
  static FilterResult Apply(const std::vector<IFilter*>& filters,
                            const FilterContext& context,
                            ir::EncodedMethod* ir_method, lir::CodeIr& code_ir,
                            const std::vector<lir::BasicBlock>& blocks);

  // Legacy: checks if the given basic block ends with a compiler-generated
  // branch according to any registered IFilter::FilterBranch().
  static bool IsSyntheticBranch(ir::EncodedMethod* ir_method,
                                const lir::BasicBlock& block);
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_SYNTHETIC_FILTER_H_
