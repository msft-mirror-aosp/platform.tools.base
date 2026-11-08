/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "tools/base/android-test/coverage/agent/native/kotlin_compose_filter.h"

#include <cstring>
#include <unordered_set>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kComposer[] = "Landroidx/compose/runtime/Composer;";
constexpr char kComposerKt[] = "Landroidx/compose/runtime/ComposerKt;";

// Returns the first bytecode at or after `instr`, skipping labels, debug
// annotations and other non-bytecode nodes.
lir::Bytecode* SkipNonBytecodes(lir::Instruction* instr) {
  for (auto* node = instr; node != nullptr; node = node->next) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(node)) {
      return bytecode;
    }
  }
  return nullptr;
}

// Returns the first bytecode after `instr`, or nullptr.
lir::Bytecode* NextBytecode(lir::Instruction* instr) {
  return instr == nullptr ? nullptr : SkipNonBytecodes(instr->next);
}

// Returns the target of a branch instruction, or the payload label of a
// switch instruction, or nullptr.
lir::Label* BranchTarget(const lir::Bytecode* branch) {
  for (auto* operand : branch->operands) {
    if (auto* code_location = dynamic_cast<lir::CodeLocation*>(operand)) {
      return code_location->label;
    }
  }
  return nullptr;
}

// Returns the register of operand `index` of `bytecode`, or -1.
int Register(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return -1;
  }
  auto* reg = dynamic_cast<lir::VReg*>(bytecode->operands[index]);
  return reg == nullptr ? -1 : static_cast<int>(reg->reg);
}

// Returns true if `bytecode` invokes the method `name` of `owner` with
// `signature`.
bool Invokes(const lir::Bytecode* bytecode, const char* owner, const char* name,
             const char* signature) {
  if ((dex::GetFlagsFromOpcode(bytecode->opcode) & dex::kInvoke) == 0) {
    return false;
  }
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<lir::Method*>(operand)) {
      const ir::MethodDecl* decl = method->ir_method;
      return decl != nullptr && decl->parent != nullptr &&
             decl->parent->descriptor != nullptr && decl->name != nullptr &&
             decl->prototype != nullptr &&
             strcmp(decl->parent->descriptor->c_str(), owner) == 0 &&
             strcmp(decl->name->c_str(), name) == 0 &&
             decl->prototype->Signature() == signature;
    }
  }
  return false;
}

// Returns the jump with opcode `jump_opcode` that tests the result of
// `invoke`, moved to a register with `move_opcode`, or nullptr:
//
//   invoke ...
//   move-result vX
//   if-eqz vX, :target
lir::Bytecode* NextIsJumpOnResult(lir::Bytecode* invoke,
                                  dex::Opcode move_opcode,
                                  dex::Opcode jump_opcode) {
  lir::Bytecode* move = NextBytecode(invoke);
  if (move == nullptr || move->opcode != move_opcode) {
    return nullptr;
  }
  lir::Bytecode* jump = NextBytecode(move);
  if (jump == nullptr || jump->opcode != jump_opcode ||
      Register(jump, 0) != Register(move, 0)) {
    return nullptr;
  }
  return jump;
}

// Ignores the instructions from `jump` to its target, if the target follows
// it.
void IgnoreToTarget(lir::Bytecode* jump, FilterOutput& output) {
  lir::Label* target = jump == nullptr ? nullptr : BranchTarget(jump);
  if (target == nullptr) {
    return;
  }
  for (auto* node = jump->next; node != nullptr; node = node->next) {
    if (node == target) {
      output.Ignore(jump, target);
      return;
    }
  }
}

// Ignores the code that can run from `start`, following jumps and switches.
void IgnoreReachable(lir::CodeIr& code_ir, lir::Label* start,
                     FilterOutput& output) {
  std::unordered_set<lir::Instruction*> reached;
  std::vector<lir::Instruction*> pending = {start};
  while (!pending.empty()) {
    lir::Instruction* node = pending.back();
    pending.pop_back();
    for (; node != nullptr && reached.insert(node).second; node = node->next) {
      auto* bytecode = dynamic_cast<lir::Bytecode*>(node);
      if (bytecode == nullptr) {
        continue;
      }
      const dex::u4 flags = dex::GetFlagsFromOpcode(bytecode->opcode);
      if (flags & dex::kBranch) {
        pending.push_back(BranchTarget(bytecode));
      } else if (flags & dex::kSwitch) {
        lir::Label* payload = BranchTarget(bytecode);
        lir::Instruction* cases = payload == nullptr ? nullptr : payload->next;
        if (auto* packed = dynamic_cast<lir::PackedSwitchPayload*>(cases)) {
          pending.insert(pending.end(), packed->targets.begin(),
                         packed->targets.end());
        } else if (auto* sparse =
                       dynamic_cast<lir::SparseSwitchPayload*>(cases)) {
          for (const auto& switch_case : sparse->switch_cases) {
            pending.push_back(switch_case.target);
          }
        }
      }
      if ((flags & dex::kContinue) == 0) {
        break;
      }
    }
  }

  // Reports the code that was reached as ranges, in code order.
  lir::Instruction* from = nullptr;
  lir::Instruction* to = nullptr;
  for (auto* instr : code_ir.instructions) {
    if (reached.count(instr) > 0) {
      if (from == nullptr) {
        from = instr;
      }
      to = instr;
    } else if (from != nullptr) {
      output.Ignore(from, to);
      from = nullptr;
    }
  }
  if (from != nullptr) {
    output.Ignore(from, to);
  }
}

// Returns true if the method has a Composer parameter, which the Compose
// compiler adds to composable functions and lambdas.
bool IsComposable(const ir::EncodedMethod* ir_method) {
  const ir::Proto* proto = ir_method->decl->prototype;
  if (proto == nullptr || proto->param_types == nullptr) {
    return false;
  }
  for (const auto* type : proto->param_types->types) {
    if (type->descriptor != nullptr &&
        strcmp(type->descriptor->c_str(), kComposer) == 0) {
      return true;
    }
  }
  return false;
}

}  // namespace

void KotlinComposeFilter::Filter(const FilterContext& context,
                                 ir::EncodedMethod* ir_method,
                                 lir::CodeIr& code_ir, FilterOutput& output) {
  if (!IsComposable(ir_method)) {
    return;
  }
  lir::Instruction* first = *code_ir.instructions.begin();
  for (auto* instr : code_ir.instructions) {
    auto* invoke = dynamic_cast<lir::Bytecode*>(instr);
    if (invoke == nullptr) {
      continue;
    }
    if (Invokes(invoke, kComposer, "shouldExecute", "(ZI)Z")) {
      // if ($composer.shouldExecute(...)) { body } else { skipToGroupEnd() }
      // Ignores the code up to the check.
      lir::Bytecode* jump =
          NextIsJumpOnResult(invoke, dex::OP_MOVE_RESULT, dex::OP_IF_EQZ);
      if (jump != nullptr) {
        output.Ignore(first, jump);
      }
    } else if (Invokes(invoke, kComposer, "getSkipping", "()Z")) {
      // if (... || !$composer.skipping) { body } else { skipToGroupEnd() }
      // Ignores the code up to the check, and the code that can run from the
      // else branch: the call to skipToGroupEnd() and the code after the body.
      // D8 can place the else branch before the body.
      lir::Bytecode* jump =
          NextIsJumpOnResult(invoke, dex::OP_MOVE_RESULT, dex::OP_IF_NEZ);
      lir::Label* skip = jump == nullptr ? nullptr : BranchTarget(jump);
      if (skip != nullptr) {
        output.Ignore(first, jump);
        IgnoreReachable(code_ir, skip, output);
      }
    } else if (Invokes(invoke, kComposer, "endRestartGroup",
                       "()Landroidx/compose/runtime/ScopeUpdateScope;")) {
      // $composer.endRestartGroup()?.updateScope { ... }
      // Ignores the null check and the call to updateScope().
      IgnoreToTarget(NextIsJumpOnResult(invoke, dex::OP_MOVE_RESULT_OBJECT,
                                        dex::OP_IF_EQZ),
                     output);
    } else if (Invokes(invoke, kComposerKt, "isTraceInProgress", "()Z")) {
      // if (isTraceInProgress()) { traceEventStart(...) }, and the same with
      // traceEventEnd(). Ignores the check and the code that it guards.
      IgnoreToTarget(
          NextIsJumpOnResult(invoke, dex::OP_MOVE_RESULT, dex::OP_IF_EQZ),
          output);
    }
  }
}

REGISTER_KOTLIN_FILTER(KotlinComposeFilter)

}  // namespace coverage
