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

#include <array>
#include <cstddef>
#include <memory>
#include <set>
#include <string>
#include <vector>
#include "slicer/code_ir.h"
#include "slicer/control_flow_graph.h"
#include "slicer/dex_format.h"

namespace coverage {

// Descriptor of the kotlin.Metadata annotation, whose presence identifies a
// class produced by the Kotlin compiler.
inline constexpr char kKotlinMetadataDescriptor[] = "Lkotlin/Metadata;";

// Class-level information made available to filters.
struct FilterContext {
  // Descriptor of the enclosing class, e.g. "Lcom/example/Foo;".
  std::string class_name;
  // Descriptor of the super class, or empty if unavailable.
  std::string super_class_name;
  // Access flags of the enclosing class (dex::kAcc*).
  dex::u4 class_access_flags = 0;
  // Descriptors of the annotations present on the enclosing class.
  std::set<std::string> class_annotations;
  // Source file name of the enclosing class, or empty if unavailable.
  std::string source_file;
  // Raw SourceDebugExtension (Kotlin SMAP) of the enclosing class, or empty.
  std::string smap;

  bool IsKotlinClass() const {
    return class_annotations.count(kKotlinMetadataDescriptor) > 0;
  }
};

// Sink through which filters report what must be excluded from coverage.
class FilterOutput {
 public:
  virtual ~FilterOutput() = default;

  // Marks the instructions from `from` to `to` (both inclusive, in code order)
  // as ignored. Ignored instructions are neither counted nor reported, and
  // ignoring a branch or switch instruction also removes its branches.
  // Ignoring does not affect probes: a block whose instructions are all ignored
  // is still probed, so that branches into it can be observed. A method with
  // nothing left to report is not instrumented.
  virtual void Ignore(lir::Instruction* from, lir::Instruction* to) = 0;

  // Replaces the outgoing branches of the block terminated by `source` with
  // one branch per instruction in `targets`.
  virtual void ReplaceBranches(lir::Instruction* source,
                               std::vector<lir::Instruction*> targets) = 0;
};

class IFilter {
 public:
  virtual ~IFilter() = default;

  // Inspects a whole method once and reports exclusions through `output`.
  virtual void Filter(const FilterContext& context,
                      ir::EncodedMethod* ir_method, lir::CodeIr& code_ir,
                      FilterOutput& output) {}

  // Legacy block-level hook: returns true if the branch at the end of the
  // block should be demoted to sequential flow. Superseded by Filter().
  virtual bool FilterBranch(ir::EncodedMethod* ir_method,
                            lir::Instruction* branch_instr) {
    return false;
  }
};

// Filter sets. kCommon filters run on every class, kKotlin filters only on
// classes annotated with kotlin.Metadata and kNonKotlin filters only on the
// remaining classes.
enum class FilterSet { kCommon = 0, kKotlin = 1, kNonKotlin = 2 };

inline constexpr size_t kFilterSetCount = 3;

typedef std::unique_ptr<IFilter> (*FilterFactory)();

// Central registry to manage and instantiate all active filters dynamically.
class FilterRegistry {
 public:
  static FilterRegistry& Instance() {
    static FilterRegistry instance;
    return instance;
  }

  void RegisterFilter(FilterSet set, FilterFactory factory) {
    factories_[static_cast<size_t>(set)].push_back(factory);
  }

  std::vector<std::unique_ptr<IFilter>> CreateFilters(FilterSet set) {
    std::vector<std::unique_ptr<IFilter>> filters;
    for (auto factory : factories_[static_cast<size_t>(set)]) {
      filters.push_back(factory());
    }
    return filters;
  }

 private:
  FilterRegistry() = default;
  std::array<std::vector<FilterFactory>, kFilterSetCount> factories_;
};

// Helper class to trigger factory registration during static library
// initialization.
class FilterRegistrar {
 public:
  FilterRegistrar(FilterSet set, FilterFactory factory) {
    FilterRegistry::Instance().RegisterFilter(set, factory);
  }
};

#define COVERAGE_REGISTER_FILTER_IN_SET(ClassName, Set)             \
  static std::unique_ptr<::coverage::IFilter> Create##ClassName() { \
    return std::make_unique<ClassName>();                           \
  }                                                                 \
  static ::coverage::FilterRegistrar g_registrar_##ClassName(       \
      Set, &Create##ClassName);

// Macros to automatically register a filter subclass within the
// FilterRegistry, in the common, Kotlin-only or non-Kotlin-only set.
#define REGISTER_FILTER(ClassName) \
  COVERAGE_REGISTER_FILTER_IN_SET(ClassName, ::coverage::FilterSet::kCommon)
#define REGISTER_KOTLIN_FILTER(ClassName) \
  COVERAGE_REGISTER_FILTER_IN_SET(ClassName, ::coverage::FilterSet::kKotlin)
#define REGISTER_NON_KOTLIN_FILTER(ClassName) \
  COVERAGE_REGISTER_FILTER_IN_SET(ClassName, ::coverage::FilterSet::kNonKotlin)

}  // namespace coverage

#endif  // COVERAGE_AGENT_FILTER_INTERFACE_H_
