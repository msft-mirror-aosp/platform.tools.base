#ifndef COVERAGE_AGENT_FILTER_INTERFACE_H_
#define COVERAGE_AGENT_FILTER_INTERFACE_H_

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

}  // namespace coverage

#endif  // COVERAGE_AGENT_FILTER_INTERFACE_H_
