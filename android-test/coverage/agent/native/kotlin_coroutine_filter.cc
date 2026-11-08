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

#include "tools/base/android-test/coverage/agent/native/kotlin_coroutine_filter.h"

#include <cstring>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kIntrinsics[] = "Lkotlin/coroutines/intrinsics/IntrinsicsKt;";
constexpr char kResult[] = "Lkotlin/ResultKt;";
constexpr char kDebugProbes[] =
    "Lkotlin/coroutines/jvm/internal/DebugProbesKt;";
constexpr char kContinuation[] = "Lkotlin/coroutines/Continuation;";
constexpr char kIllegalStateException[] = "Ljava/lang/IllegalStateException;";
constexpr char kResumeBeforeInvoke[] =
    "call to 'resume' before 'invoke' with coroutine";

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

// Returns true if `label` is between `instr` and the next bytecode.
bool LabelFollows(lir::Instruction* instr, lir::Label* label) {
  for (auto* node = instr->next; node != nullptr; node = node->next) {
    if (node == label) {
      return true;
    }
    if (dynamic_cast<lir::Bytecode*>(node) != nullptr) {
      return false;
    }
  }
  return false;
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

bool IsMove(dex::Opcode opcode) {
  return opcode >= dex::OP_MOVE && opcode <= dex::OP_MOVE_OBJECT_16;
}

bool IsGoto(dex::Opcode opcode) {
  return opcode == dex::OP_GOTO || opcode == dex::OP_GOTO_16 ||
         opcode == dex::OP_GOTO_32;
}

bool IsConst(dex::Opcode opcode) {
  switch (opcode) {
    case dex::OP_CONST_4:
    case dex::OP_CONST_16:
    case dex::OP_CONST:
    case dex::OP_CONST_HIGH16:
    case dex::OP_CONST_WIDE_16:
    case dex::OP_CONST_WIDE_32:
    case dex::OP_CONST_WIDE:
    case dex::OP_CONST_WIDE_HIGH16:
      return true;
    default:
      return false;
  }
}

bool IsInstanceFieldRead(dex::Opcode opcode) {
  return opcode >= dex::OP_IGET && opcode <= dex::OP_IGET_SHORT;
}

bool IsMoveResult(dex::Opcode opcode) {
  return opcode == dex::OP_MOVE_RESULT || opcode == dex::OP_MOVE_RESULT_WIDE ||
         opcode == dex::OP_MOVE_RESULT_OBJECT;
}

// Returns the method that `bytecode` invokes if its opcode is `opcode` or
// `range_opcode`, or nullptr.
const ir::MethodDecl* InvokedMethod(const lir::Bytecode* bytecode,
                                    dex::Opcode opcode,
                                    dex::Opcode range_opcode) {
  if (bytecode == nullptr ||
      (bytecode->opcode != opcode && bytecode->opcode != range_opcode)) {
    return nullptr;
  }
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<lir::Method*>(operand)) {
      return method->ir_method;
    }
  }
  return nullptr;
}

bool IsMethod(const ir::MethodDecl* decl, const char* owner, const char* name,
              const char* signature) {
  return decl != nullptr && decl->parent != nullptr &&
         decl->parent->descriptor != nullptr && decl->name != nullptr &&
         decl->prototype != nullptr &&
         strcmp(decl->parent->descriptor->c_str(), owner) == 0 &&
         strcmp(decl->name->c_str(), name) == 0 &&
         decl->prototype->Signature() == signature;
}

bool IsInvokeStatic(const lir::Bytecode* bytecode, const char* owner,
                    const char* name, const char* signature) {
  return IsMethod(InvokedMethod(bytecode, dex::OP_INVOKE_STATIC,
                                dex::OP_INVOKE_STATIC_RANGE),
                  owner, name, signature);
}

bool IsGetCoroutineSuspended(const lir::Bytecode* bytecode) {
  return IsInvokeStatic(bytecode, kIntrinsics, "getCOROUTINE_SUSPENDED",
                        "()Ljava/lang/Object;");
}

bool IsThrowOnFailure(const lir::Bytecode* bytecode) {
  return IsInvokeStatic(bytecode, kResult, "throwOnFailure",
                        "(Ljava/lang/Object;)V");
}

// Returns true if `bytecode` unboxes an inline value class.
bool IsUnboxImpl(const lir::Bytecode* bytecode) {
  const ir::MethodDecl* decl = InvokedMethod(bytecode, dex::OP_INVOKE_VIRTUAL,
                                             dex::OP_INVOKE_VIRTUAL_RANGE);
  return decl != nullptr && decl->name != nullptr &&
         strcmp(decl->name->c_str(), "unbox-impl") == 0;
}

bool IsTypeOperand(const lir::Bytecode* bytecode, size_t index,
                   const char* descriptor) {
  if (index >= bytecode->operands.size()) {
    return false;
  }
  auto* type = dynamic_cast<lir::Type*>(bytecode->operands[index]);
  return type != nullptr && type->ir_type != nullptr &&
         type->ir_type->descriptor != nullptr &&
         strcmp(type->ir_type->descriptor->c_str(), descriptor) == 0;
}

// Returns the register that `bytecode` writes if it is a move-result-object,
// or -1.
int MoveResultObjectRegister(const lir::Bytecode* bytecode) {
  return bytecode != nullptr && bytecode->opcode == dex::OP_MOVE_RESULT_OBJECT
             ? Register(bytecode, 0)
             : -1;
}

// Returns true if `bytecode` is an if-ne that compares `reg`.
bool IsIfNeWith(const lir::Bytecode* bytecode, int reg) {
  return bytecode != nullptr && bytecode->opcode == dex::OP_IF_NE && reg >= 0 &&
         (Register(bytecode, 0) == reg || Register(bytecode, 1) == reg);
}

bool IsReturnObject(const lir::Bytecode* bytecode, int reg) {
  return bytecode != nullptr && bytecode->opcode == dex::OP_RETURN_OBJECT &&
         Register(bytecode, 0) == reg;
}

// Returns the first bytecode that runs from `instr` on, other than moves and
// unconditional jumps. D8 can add moves between a suspension point and the
// code that continues after it.
lir::Bytecode* SkipMovesAndJumps(lir::Instruction* instr) {
  lir::Bytecode* bytecode = SkipNonBytecodes(instr);
  // Bounded, as jumps can form a loop.
  for (int steps = 0; bytecode != nullptr && steps < 16; ++steps) {
    if (IsMove(bytecode->opcode) || bytecode->opcode == dex::OP_NOP) {
      bytecode = NextBytecode(bytecode);
    } else if (IsGoto(bytecode->opcode)) {
      bytecode = SkipNonBytecodes(BranchTarget(bytecode));
    } else {
      return bytecode;
    }
  }
  return nullptr;
}

// Matches the code that throws when a coroutine is resumed in an unknown
// state, and returns its throw instruction.
lir::Bytecode* MatchResumeBeforeInvoke(lir::Bytecode* cursor) {
  if (cursor == nullptr || cursor->opcode != dex::OP_NEW_INSTANCE ||
      !IsTypeOperand(cursor, 1, kIllegalStateException)) {
    return nullptr;
  }
  cursor = NextBytecode(cursor);
  if (cursor == nullptr || (cursor->opcode != dex::OP_CONST_STRING &&
                            cursor->opcode != dex::OP_CONST_STRING_JUMBO)) {
    return nullptr;
  }
  auto* message = cursor->operands.size() > 1
                      ? dynamic_cast<lir::String*>(cursor->operands[1])
                      : nullptr;
  if (message == nullptr || message->ir_string == nullptr ||
      strcmp(message->ir_string->c_str(), kResumeBeforeInvoke) != 0) {
    return nullptr;
  }
  cursor = NextBytecode(cursor);
  if (!IsMethod(InvokedMethod(cursor, dex::OP_INVOKE_DIRECT,
                              dex::OP_INVOKE_DIRECT_RANGE),
                kIllegalStateException, "<init>", "(Ljava/lang/String;)V")) {
    return nullptr;
  }
  cursor = NextBytecode(cursor);
  return cursor != nullptr && cursor->opcode == dex::OP_THROW ? cursor
                                                              : nullptr;
}

// Matches the code that resumes a coroutine in one of its states, from the
// entry of the state: it restores the variables that the coroutine saved,
// rethrows the exception of a failed result, loads the result, and jumps to
// the code after the suspension point. Returns the jump.
lir::Bytecode* MatchResumption(lir::Label* entry) {
  if (entry == nullptr) {
    return nullptr;
  }
  bool throws_on_failure = false;
  for (auto* node = entry->next; node != nullptr; node = node->next) {
    if (dynamic_cast<lir::Label*>(node) != nullptr) {
      // The code is also reached from elsewhere.
      return nullptr;
    }
    auto* bytecode = dynamic_cast<lir::Bytecode*>(node);
    if (bytecode == nullptr) {
      continue;
    }
    const dex::Opcode opcode = bytecode->opcode;
    if (IsGoto(opcode)) {
      return throws_on_failure ? bytecode : nullptr;
    }
    if (IsThrowOnFailure(bytecode)) {
      if (throws_on_failure) {
        return nullptr;
      }
      throws_on_failure = true;
    } else if (IsUnboxImpl(bytecode)) {
      lir::Bytecode* move_result = NextBytecode(bytecode);
      if (move_result == nullptr || !IsMoveResult(move_result->opcode)) {
        return nullptr;
      }
      node = move_result;
    } else if (!IsInstanceFieldRead(opcode) && !IsMove(opcode) &&
               !IsConst(opcode) && opcode != dex::OP_CHECK_CAST &&
               opcode != dex::OP_NOP) {
      return nullptr;
    }
  }
  return nullptr;
}

// Filters the state machine of a suspending function or lambda:
//
//   (suspending functions only: get or create the continuation)
//   invoke-static IntrinsicsKt.getCOROUTINE_SUSPENDED()
//   move-result-object vS      (absent if there is no suspension point)
//   iget vL, vC, label
//   packed-switch vL, :states
//   (throw IllegalStateException("call to 'resume' before 'invoke' ..."))
//   :state_0
//   invoke-static ResultKt.throwOnFailure(result)
//   (the code of the function, with after each suspension point:)
//   if-ne vR, vS, :continuation
//   return-object vS
//   :state_n
//   (restore the saved variables)
//   invoke-static ResultKt.throwOnFailure(result)
//   (load the result)
//   goto :continuation
//
// D8 places each part where it sees fit, so they are matched one by one.
void MatchStateMachine(lir::CodeIr& code_ir, FilterOutput& output) {
  lir::Bytecode* get_suspended = nullptr;
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    if (bytecode != nullptr && IsGetCoroutineSuspended(bytecode)) {
      get_suspended = bytecode;
      break;
    }
  }
  if (get_suspended == nullptr) {
    return;
  }

  lir::Bytecode* cursor = NextBytecode(get_suspended);
  const int suspended_reg = MoveResultObjectRegister(cursor);
  if (suspended_reg >= 0) {
    cursor = NextBytecode(cursor);
  }
  if (cursor == nullptr || cursor->opcode != dex::OP_IGET) {
    return;
  }
  const int state_reg = Register(cursor, 0);
  // D8 can move constants of the code that follows before the switch.
  do {
    cursor = NextBytecode(cursor);
  } while (cursor != nullptr && IsConst(cursor->opcode));
  if (cursor == nullptr || cursor->opcode != dex::OP_PACKED_SWITCH ||
      Register(cursor, 0) != state_reg) {
    return;
  }
  lir::Bytecode* state_switch = cursor;
  lir::Label* payload_label = BranchTarget(state_switch);
  auto* states =
      payload_label == nullptr
          ? nullptr
          : dynamic_cast<lir::PackedSwitchPayload*>(payload_label->next);
  if (states == nullptr || states->first_key != 0 || states->targets.empty()) {
    return;
  }
  // Without suspension points, getCOROUTINE_SUSPENDED() is unused and only
  // the initial state exists.
  if (suspended_reg < 0 && states->targets.size() != 1) {
    return;
  }

  lir::Bytecode* initial_throw_on_failure =
      SkipNonBytecodes(states->targets[0]);
  if (initial_throw_on_failure == nullptr ||
      !IsThrowOnFailure(initial_throw_on_failure)) {
    return;
  }
  lir::Bytecode* unknown_state = NextBytecode(state_switch);
  lir::Bytecode* unknown_state_end = MatchResumeBeforeInvoke(unknown_state);
  if (unknown_state_end == nullptr) {
    return;
  }

  output.Ignore(*code_ir.instructions.begin(), state_switch);
  output.Ignore(unknown_state, unknown_state_end);
  output.Ignore(states->targets[0], initial_throw_on_failure);

  std::vector<lir::Bytecode*> continuations;
  for (size_t state = 1; state < states->targets.size(); ++state) {
    lir::Label* entry = states->targets[state];
    lir::Bytecode* jump = MatchResumption(entry);
    if (jump != nullptr) {
      output.Ignore(entry, jump);
      continuations.push_back(SkipMovesAndJumps(jump));
    }
  }

  // The check after a suspension point continues where the matching state
  // resumes.
  for (auto* instr : code_ir.instructions) {
    auto* check = dynamic_cast<lir::Bytecode*>(instr);
    if (check == nullptr || !IsIfNeWith(check, suspended_reg)) {
      continue;
    }
    lir::Bytecode* suspend = NextBytecode(check);
    if (!IsReturnObject(suspend, suspended_reg)) {
      continue;
    }
    lir::Bytecode* continuation = SkipMovesAndJumps(BranchTarget(check));
    for (lir::Bytecode* resumed : continuations) {
      if (resumed != nullptr && resumed == continuation) {
        output.Ignore(check, suspend);
        break;
      }
    }
  }
}

// Returns the check that follows `get_suspended` if it compares the result of
// getCOROUTINE_SUSPENDED() with another register, which is stored in
// `other_reg`, or nullptr.
lir::Bytecode* NextIsSuspensionCheck(lir::Bytecode* get_suspended,
                                     int* other_reg) {
  lir::Bytecode* move_result = NextBytecode(get_suspended);
  const int suspended_reg = MoveResultObjectRegister(move_result);
  if (suspended_reg < 0) {
    return nullptr;
  }
  lir::Bytecode* check = NextBytecode(move_result);
  if (!IsIfNeWith(check, suspended_reg)) {
    return nullptr;
  }
  *other_reg = Register(check, 0) == suspended_reg ? Register(check, 1)
                                                   : Register(check, 0);
  return check;
}

// Filters the check after a suspending call in tail position, which returns
// the result of the call if it suspended:
//
//   invoke-static IntrinsicsKt.getCOROUTINE_SUSPENDED()
//   move-result-object vS
//   if-ne vR, vS, :L
//   return-object vR
//   :L
void MatchTailCall(lir::Bytecode* get_suspended, FilterOutput& output) {
  int result_reg = -1;
  lir::Bytecode* check = NextIsSuspensionCheck(get_suspended, &result_reg);
  if (check == nullptr) {
    return;
  }
  lir::Bytecode* suspend = NextBytecode(check);
  if (IsReturnObject(suspend, result_reg) &&
      LabelFollows(suspend, BranchTarget(check))) {
    output.Ignore(get_suspended, suspend);
  }
}

// Filters the check that inlined suspendCoroutineUninterceptedOrReturn()
// makes before reporting a suspension:
//
//   invoke-static IntrinsicsKt.getCOROUTINE_SUSPENDED()
//   move-result-object vS
//   if-ne vR, vS, :L
//   (move-object vC, vX)
//   (check-cast vC, Continuation)
//   invoke-static DebugProbesKt.probeCoroutineSuspended(vC)
//   :L
void MatchSuspendCoroutineUninterceptedOrReturn(lir::Bytecode* get_suspended,
                                                FilterOutput& output) {
  int result_reg = -1;
  lir::Bytecode* check = NextIsSuspensionCheck(get_suspended, &result_reg);
  if (check == nullptr) {
    return;
  }
  lir::Bytecode* cursor = NextBytecode(check);
  if (cursor != nullptr && IsMove(cursor->opcode)) {
    cursor = NextBytecode(cursor);
  }
  if (cursor != nullptr && cursor->opcode == dex::OP_CHECK_CAST) {
    if (!IsTypeOperand(cursor, 1, kContinuation)) {
      return;
    }
    cursor = NextBytecode(cursor);
  }
  if (IsInvokeStatic(cursor, kDebugProbes, "probeCoroutineSuspended",
                     "(Lkotlin/coroutines/Continuation;)V") &&
      LabelFollows(cursor, BranchTarget(check))) {
    output.Ignore(check, cursor);
  }
}

}  // namespace

void KotlinCoroutineFilter::Filter(const FilterContext& context,
                                   ir::EncodedMethod* ir_method,
                                   lir::CodeIr& code_ir, FilterOutput& output) {
  MatchStateMachine(code_ir, output);
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    if (bytecode != nullptr && IsGetCoroutineSuspended(bytecode)) {
      MatchTailCall(bytecode, output);
      MatchSuspendCoroutineUninterceptedOrReturn(bytecode, output);
    }
  }
}

REGISTER_KOTLIN_FILTER(KotlinCoroutineFilter)

}  // namespace coverage
