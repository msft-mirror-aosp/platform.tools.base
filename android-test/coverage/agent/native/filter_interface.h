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

#ifndef COVERAGE_AGENT_FILTER_INTERFACE_H_
#define COVERAGE_AGENT_FILTER_INTERFACE_H_

#include <memory>
#include <vector>
#include "slicer/code_ir.h"
#include "slicer/control_flow_graph.h"

namespace coverage {

class IFilter {
 public:
  virtual ~IFilter() = default;

  // Return true if the branch at the end of the block should be
  // filtered/suppressed.
  virtual bool FilterBranch(ir::EncodedMethod* ir_method,
                            lir::Instruction* branch_instr) = 0;
};

typedef std::unique_ptr<IFilter> (*FilterFactory)();

// Central registry to manage and instantiate all active branch filters
// dynamically
class FilterRegistry {
 public:
  static FilterRegistry& Instance() {
    static FilterRegistry instance;
    return instance;
  }

  void RegisterFilter(FilterFactory factory) { factories_.push_back(factory); }

  std::vector<std::unique_ptr<IFilter>> CreateFilters() {
    std::vector<std::unique_ptr<IFilter>> filters;
    for (auto factory : factories_) {
      filters.push_back(factory());
    }
    return filters;
  }

 private:
  FilterRegistry() = default;
  std::vector<FilterFactory> factories_;
};

// Helper class to trigger factory registration during static library
// initialization
class FilterRegistrar {
 public:
  FilterRegistrar(FilterFactory factory) {
    FilterRegistry::Instance().RegisterFilter(factory);
  }
};

// Macro to automatically register any filter subclass within the FilterRegistry
#define REGISTER_FILTER(ClassName)                      \
  static std::unique_ptr<IFilter> Create##ClassName() { \
    return std::make_unique<ClassName>();               \
  }                                                     \
  static FilterRegistrar g_registrar_##ClassName(&Create##ClassName);

}  // namespace coverage

#endif  // COVERAGE_AGENT_FILTER_INTERFACE_H_
