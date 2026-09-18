#include "kotlin_lateinit_filter.h"
#include <cstring>
#include "filter_interface.h"
#include "slicer/dex_bytecode.h"

namespace coverage {

namespace {

// Helper to find the next bytecode instruction, skipping non-bytecode elements
lir::Bytecode* GetNextBytecode(lir::Instruction* instr) {
  if (instr == nullptr) return nullptr;
  for (auto* p = instr->next; p != nullptr; p = p->next) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(p)) {
      return bytecode;
    }
  }
  return nullptr;
}

// Scans a block starting from the start_instr to find a call to
// throwUninitializedPropertyAccessException.
bool IsLateinitThrowBlock(lir::Instruction* start_instr) {
  if (start_instr == nullptr) return false;

  // Restrict the forward search window to a tight, constant-time bound.
  // Unlike stack-based bytecode where intermediate values are constrained by
  // LIFO operand stack evaluation, register-based DEX bytecode allows flexible
  // instruction scheduling. The compiler routinely interleaves unrelated
  // register moves and debug metadata (LineNumber nodes) inside the block. An
  // upper-bound of 5 provides the perfect safety window to bypass these
  // compiler-inserted helper nodes, while guaranteeing O(1) constant-time
  // execution (preventing O(N^2) search performance degradation on large
  // methods) and preventing false-positive branch suppressions on unrelated
  // far-away code blocks.
  const int max_instructions = 5;

  int count = 0;
  for (auto* instr = start_instr; instr != nullptr && count < max_instructions;
       instr = instr->next, ++count) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      dex::Opcode op = bytecode->opcode;
      if (op == dex::OP_INVOKE_STATIC || op == dex::OP_INVOKE_STATIC_RANGE) {
        for (auto* operand : bytecode->operands) {
          if (auto* method_operand = dynamic_cast<lir::Method*>(operand)) {
            if (method_operand->ir_method != nullptr &&
                method_operand->ir_method->name != nullptr &&
                method_operand->ir_method->parent != nullptr &&
                method_operand->ir_method->parent->descriptor != nullptr) {
              const char* parent_class =
                  method_operand->ir_method->parent->descriptor->c_str();
              const char* m_name = method_operand->ir_method->name->c_str();
              if (strstr(parent_class, "kotlin/jvm/internal/Intrinsics") !=
                      nullptr &&
                  strstr(m_name, "throwUninitializedPropertyAccessException") !=
                      nullptr) {
                return true;
              }
            }
          }
        }
      }
    }
  }
  return false;
}

}  // namespace

bool KotlinLateinitFilter::FilterBranch(ir::EncodedMethod* ir_method,
                                        lir::Instruction* branch_instr) {
  auto* bytecode = dynamic_cast<lir::Bytecode*>(branch_instr);
  if (bytecode == nullptr) {
    return false;
  }

  dex::Opcode op = bytecode->opcode;
  // Lateinit checks compile to conditional jumps: IF_EQZ or IF_NEZ
  if (op != dex::OP_IF_EQZ && op != dex::OP_IF_NEZ) {
    return false;
  }

  // Case 1: IF_EQZ v0, label (if null, jump to label where the throw block
  // resides)
  if (op == dex::OP_IF_EQZ) {
    for (auto* operand : bytecode->operands) {
      if (auto* code_loc = dynamic_cast<lir::CodeLocation*>(operand)) {
        if (code_loc->label != nullptr && code_loc->label->next != nullptr) {
          if (IsLateinitThrowBlock(code_loc->label->next)) {
            return true;
          }
        }
      }
    }
  }

  // Case 2: IF_NEZ v0, label (if not null, jump past the throw block. The throw
  // block is directly next!)
  if (op == dex::OP_IF_NEZ) {
    if (auto* next_bytecode = GetNextBytecode(branch_instr)) {
      if (IsLateinitThrowBlock(next_bytecode)) {
        return true;
      }
    }
  }

  return false;
}

// Statically register this class in our central FilterRegistry upon dynamic
// library load.
REGISTER_FILTER(KotlinLateinitFilter)

}  // namespace coverage
