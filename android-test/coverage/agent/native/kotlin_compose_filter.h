#ifndef COVERAGE_AGENT_KOTLIN_COMPOSE_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_COMPOSE_FILTER_H_

#include "filter_interface.h"

namespace coverage {

/**
 * Filter for Jetpack Compose compiler-generated branch structures.
 *
 * Demotes synthetic branches using boundary range-wiping,
 * endRestartGroup branch suppressions, and isTraceInProgress conditional
 * branch suppressions.
 */
class KotlinComposeFilter : public IFilter {
 public:
  bool FilterBranch(ir::EncodedMethod* ir_method,
                    lir::Instruction* branch_instr) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_COMPOSE_FILTER_H_
