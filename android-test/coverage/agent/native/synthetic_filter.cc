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

#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

#include <memory>
#include <utility>
#include <vector>

#include "slicer/dex_bytecode.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

// Resolves the statically registered IFilter implementations of one set from
// our central FilterRegistry. Each set is instantiated once and shared, which
// is safe because filters are stateless.
const std::vector<std::unique_ptr<IFilter>>& GetFilters(FilterSet set) {
  static const std::vector<std::unique_ptr<IFilter>> common_filters =
      FilterRegistry::Instance().CreateFilters(FilterSet::kCommon);
  static const std::vector<std::unique_ptr<IFilter>> kotlin_filters =
      FilterRegistry::Instance().CreateFilters(FilterSet::kKotlin);
  static const std::vector<std::unique_ptr<IFilter>> non_kotlin_filters =
      FilterRegistry::Instance().CreateFilters(FilterSet::kNonKotlin);
  switch (set) {
    case FilterSet::kKotlin:
      return kotlin_filters;
    case FilterSet::kNonKotlin:
      return non_kotlin_filters;
    case FilterSet::kCommon:
    default:
      return common_filters;
  }
}

bool EndsWithBranchOrSwitch(const lir::BasicBlock& block) {
  auto* last_bytecode = dynamic_cast<lir::Bytecode*>(block.region.last);
  if (last_bytecode == nullptr) {
    return false;
  }
  auto flags = dex::GetFlagsFromOpcode(last_bytecode->opcode);
  return (flags & dex::kBranch) || (flags & dex::kSwitch);
}

// Legacy block-level check against an explicit list of filters.
bool IsSyntheticBranchWith(const std::vector<IFilter*>& filters,
                           ir::EncodedMethod* ir_method,
                           const lir::BasicBlock& block) {
  if (ir_method == nullptr || block.region.last == nullptr ||
      !EndsWithBranchOrSwitch(block)) {
    return false;
  }

  auto* last_bytecode = static_cast<lir::Bytecode*>(block.region.last);
  for (IFilter* filter : filters) {
    if (filter->FilterBranch(ir_method, last_bytecode)) {
      return true;  // Suppress branch immediately if matched by any filter
    }
  }
  return false;
}

// All registered filters, regardless of set.
std::vector<IFilter*> AllFilters() {
  std::vector<IFilter*> filters;
  for (FilterSet set :
       {FilterSet::kCommon, FilterSet::kKotlin, FilterSet::kNonKotlin}) {
    for (const auto& filter : GetFilters(set)) {
      filters.push_back(filter.get());
    }
  }
  return filters;
}

}  // namespace

void FilterResult::Ignore(lir::Instruction* from, lir::Instruction* to) {
  for (auto* instr = from; instr != nullptr; instr = instr->next) {
    ignored_.insert(instr);
    if (instr == to) {
      break;
    }
  }
}

void FilterResult::ReplaceBranches(lir::Instruction* source,
                                   std::vector<lir::Instruction*> targets) {
  if (source == nullptr) {
    return;
  }
  replaced_branches_[source] = std::move(targets);
}

void FilterResult::SuppressBranch(const lir::Instruction* branch_instr) {
  if (branch_instr != nullptr) {
    suppressed_branches_.insert(branch_instr);
  }
}

const std::vector<lir::Instruction*>* FilterResult::GetReplacedBranches(
    const lir::Instruction* source) const {
  auto it = replaced_branches_.find(source);
  return it == replaced_branches_.end() ? nullptr : &it->second;
}

std::vector<FilterSet> SyntheticFilter::SelectFilterSets(
    const FilterContext& context) {
  return {FilterSet::kCommon,
          context.IsKotlinClass() ? FilterSet::kKotlin : FilterSet::kNonKotlin};
}

FilterResult SyntheticFilter::Apply(
    const FilterContext& context, ir::EncodedMethod* ir_method,
    lir::CodeIr& code_ir, const std::vector<lir::BasicBlock>& blocks) {
  std::vector<IFilter*> filters;
  for (FilterSet set : SelectFilterSets(context)) {
    for (const auto& filter : GetFilters(set)) {
      filters.push_back(filter.get());
    }
  }
  return Apply(filters, context, ir_method, code_ir, blocks);
}

FilterResult SyntheticFilter::Apply(
    const std::vector<IFilter*>& filters, const FilterContext& context,
    ir::EncodedMethod* ir_method, lir::CodeIr& code_ir,
    const std::vector<lir::BasicBlock>& blocks) {
  FilterResult result;
  if (ir_method == nullptr) {
    return result;
  }

  for (IFilter* filter : filters) {
    filter->Filter(context, ir_method, code_ir, result);
  }

  // Legacy block-level filtering, kept until every filter reports its
  // decisions through Filter().
  for (const auto& block : blocks) {
    if (IsSyntheticBranchWith(filters, ir_method, block)) {
      result.SuppressBranch(block.region.last);
    }
  }
  return result;
}

bool SyntheticFilter::IsSyntheticBranch(ir::EncodedMethod* ir_method,
                                        const lir::BasicBlock& block) {
  return IsSyntheticBranchWith(AllFilters(), ir_method, block);
}

}  // namespace coverage
