#ifndef COVERAGE_AGENT_SYNTHETIC_FILTER_H_
#define COVERAGE_AGENT_SYNTHETIC_FILTER_H_

#include "slicer/code_ir.h"
#include "slicer/control_flow_graph.h"

namespace coverage {

class SyntheticFilter {
 public:
  // Checks if the given basic block represents a compiler-generated coroutine suspension return check.
  static bool IsSyntheticBranch(ir::EncodedMethod* ir_method,
                               const lir::BasicBlock& block);
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_SYNTHETIC_FILTER_H_