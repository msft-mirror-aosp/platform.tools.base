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
