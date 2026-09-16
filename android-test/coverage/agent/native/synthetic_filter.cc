#include "synthetic_filter.h"
#include <memory>
#include <vector>
#include "filter_interface.h"
#include "kotlin_compose_filter.h"
#include "kotlin_coroutine_filter.h"
#include "kotlin_generated_filter.h"

namespace coverage {

namespace {

// TODO(b/556724270): Migrate to a static self-registration registry pattern to
// eliminate manual vector additions.
std::vector<std::unique_ptr<IFilter>> CreateActiveFilters() {
  std::vector<std::unique_ptr<IFilter>> filters;
  filters.push_back(std::make_unique<KotlinComposeFilter>());
  filters.push_back(std::make_unique<KotlinCoroutineFilter>());
  filters.push_back(std::make_unique<KotlinGeneratedFilter>());
  return filters;
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
