#include "synthetic_filter.h"
#include "slicer/dex_bytecode.h"
#include <algorithm>
#include <cstring>

namespace coverage {

namespace {

// Scans backward in the flat instruction stream to detect synthetic coroutine suspension checks
bool IsSyntheticInstructionFlow(lir::Instruction* branch_instr, lir::Instruction* block_first) {
  for (auto* instr = branch_instr->prev; instr != nullptr; instr = instr->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (op >= dex::OP_INVOKE_VIRTUAL && op <= dex::OP_INVOKE_INTERFACE_RANGE) {
        for (auto* operand : bytecode->operands) {
          if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
            if (method_operand->ir_method != nullptr &&
                method_operand->ir_method->parent != nullptr &&
                method_operand->ir_method->parent->descriptor != nullptr) {
              const char* parent_class = method_operand->ir_method->parent->descriptor->c_str();
              const char* p_method_name = (method_operand->ir_method->name != nullptr) ? method_operand->ir_method->name->c_str() : "";

              // Coroutine suspension checks via getCOROUTINE_SUSPENDED
              if (strcmp(parent_class, "Lkotlin/coroutines/intrinsics/IntrinsicsKt;") == 0 ||
                  strcmp(parent_class, "Lkotlin/coroutines/intrinsics/IntrinsicsKt__IntrinsicsJvmKt;") == 0) {
                if (strcmp(p_method_name, "getCOROUTINE_SUSPENDED") == 0) {
                  return true;
                }
              }
            }
          }
        }
      } else if (op >= dex::OP_SGET && op <= dex::OP_SGET_SHORT) {
        // Coroutine suspension checks via static field COROUTINE_SUSPENDED
        for (auto* operand : bytecode->operands) {
          if (auto* field_operand = dynamic_cast<lir::Field*>(operand)) {
            if (field_operand->ir_field != nullptr &&
                field_operand->ir_field->parent != nullptr &&
                field_operand->ir_field->parent->descriptor != nullptr) {
              const char* parent_class = field_operand->ir_field->parent->descriptor->c_str();
              const char* field_name = (field_operand->ir_field->name != nullptr) ? field_operand->ir_field->name->c_str() : "";
              if (strcmp(field_name, "COROUTINE_SUSPENDED") == 0 &&
                  strcmp(parent_class, "Lkotlin/coroutines/intrinsics/CoroutineSingletons;") == 0) {
                return true;
              }
            }
          }
        }
      }
    }
    if (instr == block_first) {
      break;
    }
  }
  return false;
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
  if (!(flags & dex::kBranch) || !(flags & dex::kContinue)) {
    return false;
  }

  // Detect coroutine suspension checks
  if (IsSyntheticInstructionFlow(last_bytecode, block.region.first)) {
    return true;
  }

  return false;
}

}  // namespace coverage