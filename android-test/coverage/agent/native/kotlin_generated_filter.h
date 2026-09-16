#ifndef COVERAGE_AGENT_KOTLIN_GENERATED_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_GENERATED_FILTER_H_

#include "filter_interface.h"

namespace coverage {

class KotlinGeneratedFilter : public IFilter {
 public:
  bool FilterBranch(ir::EncodedMethod* ir_method,
                    lir::Instruction* branch_instr) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_GENERATED_FILTER_H_
