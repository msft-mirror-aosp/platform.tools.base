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

#include "tools/base/android-test/coverage/agent/native/synchronized_filter.h"

#include <cstddef>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

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

// Returns the target of a branch instruction, or nullptr.
lir::Label* BranchTarget(const lir::Bytecode* bytecode) {
  for (auto* operand : bytecode->operands) {
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
  auto* reg = dynamic_cast<const lir::VReg*>(bytecode->operands[index]);
  return reg == nullptr ? -1 : static_cast<int>(reg->reg);
}

bool IsGoto(dex::Opcode opcode) {
  return opcode == dex::OP_GOTO || opcode == dex::OP_GOTO_16 ||
         opcode == dex::OP_GOTO_32;
}

bool IsMove(dex::Opcode opcode) {
  return opcode >= dex::OP_MOVE && opcode <= dex::OP_MOVE_OBJECT_16;
}

// The registers that hold the exception that a handler caught.
using ExceptionRegisters = std::unordered_set<int>;

// Updates `exception` for `move`, which copies the exception to another
// register if it moves it from one of them, and otherwise overwrites its
// destination.
void TrackMove(const lir::Bytecode* move, ExceptionRegisters* exception) {
  if (move->operands.empty()) {
    return;
  }
  if (auto* pair = dynamic_cast<const lir::VRegPair*>(move->operands[0])) {
    exception->erase(static_cast<int>(pair->base_reg));
    exception->erase(static_cast<int>(pair->base_reg) + 1);
    return;
  }
  const int destination = Register(move, 0);
  if (exception->count(Register(move, 1)) > 0) {
    exception->insert(destination);
  } else {
    exception->erase(destination);
  }
}

// Follows the code of a handler from `bytecode` through gotos and the moves
// with which D8 shuffles registers, and adds them to `path`. Returns the first
// other bytecode, or nullptr at the end of the code or if the gotos loop.
lir::Bytecode* SkipShuffles(lir::Bytecode* bytecode,
                            ExceptionRegisters* exception,
                            std::vector<lir::Bytecode*>* path) {
  std::unordered_set<const lir::Bytecode*> visited;
  while (bytecode != nullptr && visited.insert(bytecode).second) {
    if (IsGoto(bytecode->opcode)) {
      path->push_back(bytecode);
      bytecode = SkipNonBytecodes(BranchTarget(bytecode));
    } else if (IsMove(bytecode->opcode)) {
      TrackMove(bytecode, exception);
      path->push_back(bytecode);
      bytecode = NextBytecode(bytecode);
    } else {
      return bytecode;
    }
  }
  return nullptr;
}

}  // namespace

void SynchronizedFilter::Filter(const FilterContext& context,
                                ir::EncodedMethod* ir_method,
                                lir::CodeIr& code_ir, FilterOutput& output) {
  // The handlers of the try blocks that catch any exception, each once, and
  // the line of each bytecode.
  std::vector<lir::Label*> handlers;
  std::unordered_set<const lir::Label*> seen;
  std::unordered_map<const lir::Bytecode*, int> lines;
  int line = -1;
  for (auto* instr : code_ir.instructions) {
    if (auto* bytecode = dynamic_cast<const lir::Bytecode*>(instr)) {
      lines[bytecode] = line;
    } else if (auto* annotation =
                   dynamic_cast<const lir::DbgInfoAnnotation*>(instr)) {
      if (annotation->dbg_opcode == dex::DBG_ADVANCE_LINE &&
          !annotation->operands.empty()) {
        if (auto* number =
                dynamic_cast<const lir::LineNumber*>(annotation->operands[0])) {
          line = number->line;
        }
      }
    } else if (auto* end = dynamic_cast<const lir::TryBlockEnd*>(instr)) {
      if (end->catch_all != nullptr && seen.insert(end->catch_all).second) {
        handlers.push_back(end->catch_all);
      }
    }
  }
  // The handler must catch the exception, exit the monitor of a register that
  // doesn't hold it and throw it, on the line of the exit. D8 may put the exit
  // and the throw before the handler, the exit inside the try block, and jump
  // there, and it may move registers in between. A catch of Throwable, which is
  // a catch of any exception in dex too, may also save the exception, exit the
  // monitor when it leaves the block and throw the exception later, but not on
  // the line of the exit.
  for (lir::Label* handler : handlers) {
    lir::Bytecode* caught = SkipNonBytecodes(handler);
    if (caught == nullptr || caught->opcode != dex::OP_MOVE_EXCEPTION ||
        Register(caught, 0) < 0) {
      continue;
    }
    ExceptionRegisters exception = {Register(caught, 0)};
    std::vector<lir::Bytecode*> path = {caught};
    lir::Bytecode* monitor_exit =
        SkipShuffles(NextBytecode(caught), &exception, &path);
    if (monitor_exit == nullptr ||
        monitor_exit->opcode != dex::OP_MONITOR_EXIT ||
        exception.count(Register(monitor_exit, 0)) > 0) {
      continue;
    }
    path.push_back(monitor_exit);
    lir::Bytecode* rethrow =
        SkipShuffles(NextBytecode(monitor_exit), &exception, &path);
    if (rethrow == nullptr || rethrow->opcode != dex::OP_THROW ||
        exception.count(Register(rethrow, 0)) == 0 ||
        lines[rethrow] != lines[monitor_exit]) {
      continue;
    }
    path.push_back(rethrow);
    // Each bytecode is ignored on its own, since the exit and the throw can
    // come before the handler.
    for (lir::Bytecode* bytecode : path) {
      output.Ignore(bytecode, bytecode);
    }
  }
}

REGISTER_FILTER(SynchronizedFilter)

}  // namespace coverage
