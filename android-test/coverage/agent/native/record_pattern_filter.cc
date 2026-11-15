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

#include "tools/base/android-test/coverage/agent/native/record_pattern_filter.h"

#include <cstddef>
#include <cstring>
#include <iterator>
#include <set>
#include <unordered_set>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kThrowable[] = "Ljava/lang/Throwable;";
constexpr char kMatchException[] = "Ljava/lang/MatchException;";
constexpr char kRuntimeException[] = "Ljava/lang/RuntimeException;";
constexpr char kToString[] = "()Ljava/lang/String;";
constexpr char kMessageAndCause[] =
    "(Ljava/lang/String;Ljava/lang/Throwable;)V";

// The instructions from `from` to `to`, both inclusive, in code order.
struct Range {
  lir::Instruction* from;
  lir::Instruction* to;
};

// Returns the first bytecode at or after `instr`, skipping labels, debug
// annotations and other non-bytecode nodes, or nullptr.
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
  return SkipNonBytecodes(instr->next);
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

// Returns true if `opcode` copies a value from one register to another.
bool IsMove(dex::Opcode opcode) {
  return opcode >= dex::OP_MOVE && opcode <= dex::OP_MOVE_OBJECT_16;
}

// Returns true if `opcode` copies an object from one register to another.
bool IsObjectMove(dex::Opcode opcode) {
  return opcode == dex::OP_MOVE_OBJECT ||
         opcode == dex::OP_MOVE_OBJECT_FROM16 ||
         opcode == dex::OP_MOVE_OBJECT_16;
}

bool IsGoto(dex::Opcode opcode) {
  return opcode == dex::OP_GOTO || opcode == dex::OP_GOTO_16 ||
         opcode == dex::OP_GOTO_32;
}

// Returns the register of operand `index` of `bytecode`, or -1.
int Register(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return -1;
  }
  auto* reg = dynamic_cast<const lir::VReg*>(bytecode->operands[index]);
  return reg == nullptr ? -1 : static_cast<int>(reg->reg);
}

// Returns true if `bytecode` writes register `reg` with its first operand, a
// register or a pair of registers.
bool Writes(const lir::Bytecode* bytecode, int reg) {
  if (bytecode->operands.empty()) {
    return false;
  }
  if (auto* vreg = dynamic_cast<const lir::VReg*>(bytecode->operands[0])) {
    return static_cast<int>(vreg->reg) == reg;
  }
  if (auto* pair = dynamic_cast<const lir::VRegPair*>(bytecode->operands[0])) {
    return static_cast<int>(pair->base_reg) == reg ||
           static_cast<int>(pair->base_reg) + 1 == reg;
  }
  return false;
}

// Returns the registers that the invoke `bytecode` passes to its method.
std::vector<int> Arguments(const lir::Bytecode* bytecode) {
  std::vector<int> registers;
  if (bytecode->operands.empty()) {
    return registers;
  }
  if (auto* list = dynamic_cast<const lir::VRegList*>(bytecode->operands[0])) {
    for (auto reg : list->registers) {
      registers.push_back(static_cast<int>(reg));
    }
  }
  return registers;
}

// Returns true if `bytecode` invokes the method `name` of `owner` with
// `signature`, with `opcode`.
bool Invokes(const lir::Bytecode* bytecode, dex::Opcode opcode,
             const char* owner, const char* name, const char* signature) {
  if (bytecode->opcode != opcode) {
    return false;
  }
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<const lir::Method*>(operand)) {
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

// Returns the descriptor of the type operand `index` of `bytecode`, or
// nullptr.
const char* TypeOperand(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return nullptr;
  }
  auto* type = dynamic_cast<const lir::Type*>(bytecode->operands[index]);
  return type != nullptr && type->ir_type != nullptr &&
                 type->ir_type->descriptor != nullptr
             ? type->ir_type->descriptor->c_str()
             : nullptr;
}

// The walk through a handler.
struct HandlerWalk {
  // The start of the current range of the handler.
  lir::Instruction* start = nullptr;
  // The ranges before it, which gotos end.
  std::vector<Range> ranges;
  // The bytecodes that the walk went through.
  std::unordered_set<const lir::Bytecode*> visited;
};

// Returns the bytecode after `bytecode` in the handler, or nullptr. D8 can
// split the handler with gotos: this function then follows them, adds the code
// from the start of the current range to each of them to the ranges of `walk`,
// and starts a new range where they lead. Returns nullptr if the handler goes
// back to a bytecode that it ran before, since then it loops.
lir::Bytecode* NextInHandler(lir::Bytecode* bytecode, HandlerWalk* walk) {
  lir::Bytecode* next = NextBytecode(bytecode);
  while (next != nullptr && IsGoto(next->opcode)) {
    if (!walk->visited.insert(next).second) {
      return nullptr;
    }
    walk->ranges.push_back({walk->start, next});
    next = SkipNonBytecodes(BranchTarget(next));
    walk->start = next;
  }
  if (next != nullptr && !walk->visited.insert(next).second) {
    return nullptr;
  }
  return next;
}

// Matches the handler at `handler` (see RecordPatternFilter), and returns its
// ranges, or an empty list. The handler is outside the scopes of the local
// variables, so D8 gives it low registers, and the invokes never need their
// /range forms.
std::vector<Range> MatchHandler(lir::Label* handler) {
  lir::Bytecode* caught = SkipNonBytecodes(handler);
  if (caught == nullptr || caught->opcode != dex::OP_MOVE_EXCEPTION) {
    return {};
  }
  HandlerWalk walk;
  walk.start = caught;
  walk.visited.insert(caught);
  // The registers that hold the caught exception, the one of the exception
  // that the handler throws, and the one of the message.
  std::set<int> causes = {Register(caught, 0)};
  int exception = -1;
  int message = -1;
  // Returns the bytecode of the handler after `current`, past the moves that
  // D8 can put in it: copies of the caught exception, which it adds to
  // `causes`, and moves that keep other values, which can overwrite a copy of
  // the caught exception but no other register of the handler.
  auto next = [&](lir::Bytecode* current) -> lir::Bytecode* {
    lir::Bytecode* following = NextInHandler(current, &walk);
    while (following != nullptr && IsMove(following->opcode)) {
      if (Writes(following, exception) || Writes(following, message)) {
        return nullptr;
      }
      bool copies_cause = IsObjectMove(following->opcode) &&
                          causes.count(Register(following, 1)) > 0;
      for (auto it = causes.begin(); it != causes.end();) {
        it = Writes(following, *it) ? causes.erase(it) : std::next(it);
      }
      if (copies_cause) {
        causes.insert(Register(following, 0));
      }
      following = NextInHandler(following, &walk);
    }
    return following;
  };
  lir::Bytecode* created = next(caught);
  if (created == nullptr || created->opcode != dex::OP_NEW_INSTANCE) {
    return {};
  }
  const char* type = TypeOperand(created, 1);
  if (type == nullptr || (strcmp(type, kRuntimeException) != 0 &&
                          strcmp(type, kMatchException) != 0)) {
    return {};
  }
  exception = Register(created, 0);
  causes.erase(exception);
  lir::Bytecode* described = next(created);
  if (described == nullptr ||
      !Invokes(described, dex::OP_INVOKE_VIRTUAL, kThrowable, "toString",
               kToString) ||
      Arguments(described).size() != 1 ||
      causes.count(Arguments(described)[0]) == 0) {
    return {};
  }
  lir::Bytecode* result = next(described);
  if (result == nullptr || result->opcode != dex::OP_MOVE_RESULT_OBJECT) {
    return {};
  }
  message = Register(result, 0);
  causes.erase(message);
  lir::Bytecode* constructor = next(result);
  if (constructor == nullptr || !Invokes(constructor, dex::OP_INVOKE_DIRECT,
                                         type, "<init>", kMessageAndCause)) {
    return {};
  }
  const std::vector<int> arguments = Arguments(constructor);
  if (arguments.size() != 3 || arguments[0] != exception ||
      arguments[1] != message || causes.count(arguments[2]) == 0) {
    return {};
  }
  lir::Bytecode* thrown = next(constructor);
  if (thrown == nullptr || thrown->opcode != dex::OP_THROW ||
      Register(thrown, 0) != exception) {
    return {};
  }
  walk.ranges.push_back({walk.start, thrown});
  return walk.ranges;
}

}  // namespace

void RecordPatternFilter::Filter(const FilterContext& context,
                                 ir::EncodedMethod* ir_method,
                                 lir::CodeIr& code_ir, FilterOutput& output) {
  // The handlers that catch any exception, each once: those of the try blocks
  // that catch any exception, and of those that catch Throwable.
  std::vector<lir::Label*> handlers;
  std::unordered_set<const lir::Label*> seen;
  for (auto* instr : code_ir.instructions) {
    auto* end = dynamic_cast<lir::TryBlockEnd*>(instr);
    if (end == nullptr) {
      continue;
    }
    if (end->catch_all != nullptr && seen.insert(end->catch_all).second) {
      handlers.push_back(end->catch_all);
    }
    for (const lir::CatchHandler& handler : end->handlers) {
      if (handler.ir_type != nullptr &&
          handler.ir_type->descriptor != nullptr &&
          strcmp(handler.ir_type->descriptor->c_str(), kThrowable) == 0 &&
          seen.insert(handler.label).second) {
        handlers.push_back(handler.label);
      }
    }
  }
  for (lir::Label* handler : handlers) {
    for (const Range& range : MatchHandler(handler)) {
      output.Ignore(range.from, range.to);
    }
  }
}

REGISTER_NON_KOTLIN_FILTER(RecordPatternFilter)

}  // namespace coverage
