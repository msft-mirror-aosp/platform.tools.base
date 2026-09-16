#include "kotlin_generated_filter.h"
#include "filter_interface.h"

namespace coverage {

bool KotlinGeneratedFilter::FilterBranch(ir::EncodedMethod* ir_method,
                                         lir::Instruction* branch_instr) {
  // Empty for now - to be populated later
  return false;
}

REGISTER_FILTER(KotlinGeneratedFilter)

}  // namespace coverage
