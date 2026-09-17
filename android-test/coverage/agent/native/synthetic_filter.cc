#include "synthetic_filter.h"
#include <memory>
#include <vector>
#include "filter_interface.h"

namespace coverage {

namespace {

// Resolves all statically registered IFilter implementations from our central
// FilterRegistry. This completely decouples this orchestrator from individual
// filter files, eliminating any manual vector additions and preventing
// registration errors.
std::vector<std::unique_ptr<IFilter>> CreateActiveFilters() {
  return FilterRegistry::Instance().CreateFilters();
}

} // namespace

bool SyntheticFilter::IsSyntheticBranch(ir::EncodedMethod* ir_method,
                                       const lir::BasicBlock& block) {
  if (ir_method == nullptr || block.region.last == nullptr) {
    return false;
  }

  auto* last_bytecode = dynamic_cast<lir::Bytecode*>(block.region.last);
  if (last_bytecode == nullptr) {
    return false;
  }

  auto flags = dex::GetFlagsFromOpcode(last_bytecode->opcode);
  bool is_candidate = (flags & dex::kBranch) || (flags & dex::kSwitch);
  if (!is_candidate) {
    return false;
  }

  static const std::vector<std::unique_ptr<IFilter>> filters =
      CreateActiveFilters();

  for (const auto& filter : filters) {
    if (filter->FilterBranch(ir_method, last_bytecode)) {
      return true;  // Suppress branch immediately if matched by any active
                    // filter
    }
  }

  return false;
}

}  // namespace coverage
