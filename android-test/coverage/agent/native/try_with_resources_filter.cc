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

#include "tools/base/android-test/coverage/agent/native/try_with_resources_filter.h"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <initializer_list>
#include <iterator>
#include <limits>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kThrowable[] = "Ljava/lang/Throwable;";
constexpr char kOneThrowable[] = "(Ljava/lang/Throwable;)V";
constexpr char kTwoThrowables[] =
    "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V";
// The static method with which javac 9 and 10 can close a resource.
constexpr char kCloseResource[] = "$closeResource";
constexpr char kCloseResourceSignature[] =
    "(Ljava/lang/Throwable;Ljava/lang/AutoCloseable;)V";

// What a register holds in a handler: the value that a register had before
// the handler (that register, 0 or more), or one of these. None of them is -1,
// which Register() returns for an operand that isn't a register.
constexpr int kCaught = -2;      // The exception that the handler caught.
constexpr int kSuppressed = -3;  // The exception that the close threw.
constexpr int kOtherValue = -4;  // Anything else.
// javac 7 to 10: the exception that the catch of Throwable caught and threw
// again, to the handler that closes the resource.
constexpr int kPrimary = -5;

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

// Returns the labels that `bytecode` jumps to: the target of a branch, or the
// cases of a switch.
std::vector<lir::Label*> JumpTargets(const lir::Bytecode* bytecode) {
  const dex::OpcodeFlags flags = dex::GetFlagsFromOpcode(bytecode->opcode);
  lir::Label* target = BranchTarget(bytecode);
  if (target == nullptr || (flags & (dex::kBranch | dex::kSwitch)) == 0) {
    return {};
  }
  if ((flags & dex::kBranch) != 0) {
    return {target};
  }
  // A switch refers to the label of its payload.
  std::vector<lir::Label*> cases;
  const lir::Instruction* node = target->next;
  while (dynamic_cast<const lir::DbgInfoAnnotation*>(node) != nullptr) {
    node = node->next;
  }
  if (auto* packed = dynamic_cast<const lir::PackedSwitchPayload*>(node)) {
    cases.assign(packed->targets.begin(), packed->targets.end());
  } else if (auto* sparse =
                 dynamic_cast<const lir::SparseSwitchPayload*>(node)) {
    for (const auto& switch_case : sparse->switch_cases) {
      cases.push_back(switch_case.target);
    }
  }
  return cases;
}

bool IsGoto(dex::Opcode opcode) {
  return opcode == dex::OP_GOTO || opcode == dex::OP_GOTO_16 ||
         opcode == dex::OP_GOTO_32;
}

// Returns true if `opcode` copies a value from one register to another.
bool IsMove(dex::Opcode opcode) {
  return opcode >= dex::OP_MOVE && opcode <= dex::OP_MOVE_OBJECT_16;
}

// Returns true if `opcode` only reads the register of its first operand.
bool OnlyReadsFirstOperand(dex::Opcode opcode) {
  return (opcode >= dex::OP_RETURN && opcode <= dex::OP_RETURN_OBJECT) ||
         opcode == dex::OP_MONITOR_ENTER || opcode == dex::OP_MONITOR_EXIT ||
         opcode == dex::OP_CHECK_CAST || opcode == dex::OP_FILL_ARRAY_DATA ||
         opcode == dex::OP_THROW || opcode == dex::OP_PACKED_SWITCH ||
         opcode == dex::OP_SPARSE_SWITCH ||
         (opcode >= dex::OP_IF_EQ && opcode <= dex::OP_IF_LEZ) ||
         (opcode >= dex::OP_APUT && opcode <= dex::OP_APUT_SHORT) ||
         (opcode >= dex::OP_IPUT && opcode <= dex::OP_IPUT_SHORT) ||
         (opcode >= dex::OP_SPUT && opcode <= dex::OP_SPUT_SHORT);
}

// Returns true if `bytecode` writes register `reg` with its first operand, a
// register or a pair of registers.
bool Writes(const lir::Bytecode* bytecode, int reg) {
  if (bytecode->operands.empty() || OnlyReadsFirstOperand(bytecode->opcode)) {
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

// Returns the register of operand `index` of `bytecode`, or -1.
int Register(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return -1;
  }
  auto* reg = dynamic_cast<const lir::VReg*>(bytecode->operands[index]);
  return reg == nullptr ? -1 : static_cast<int>(reg->reg);
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
  } else if (auto* range =
                 dynamic_cast<const lir::VRegRange*>(bytecode->operands[0])) {
    for (int i = 0; i < range->count; ++i) {
      registers.push_back(static_cast<int>(range->base_reg) + i);
    }
  }
  return registers;
}

// Returns the method that the invoke `bytecode` calls, or nullptr.
const ir::MethodDecl* Callee(const lir::Bytecode* bytecode) {
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<const lir::Method*>(operand)) {
      const ir::MethodDecl* decl = method->ir_method;
      if (decl != nullptr && decl->parent != nullptr &&
          decl->parent->descriptor != nullptr && decl->name != nullptr &&
          decl->prototype != nullptr) {
        return decl;
      }
      return nullptr;
    }
  }
  return nullptr;
}

// Returns true if `method` has the name `name` and the JNI signature
// `signature`.
bool HasNameAndSignature(const ir::MethodDecl* method, const char* name,
                         const char* signature) {
  return strcmp(method->name->c_str(), name) == 0 &&
         method->prototype->Signature() == signature;
}

// Returns true if `signature` takes one parameter of a class type and returns
// nothing.
bool TakesOneObject(const std::string& signature) {
  return signature.size() > 5 && signature.compare(0, 2, "(L") == 0 &&
         signature.find(';') == signature.size() - 3 &&
         signature.compare(signature.size() - 3, 3, ";)V") == 0;
}

// If `bytecode` closes a resource, returns the method that it calls and sets
// `reg` to the register of the resource. javac calls close() through a class
// or an interface. Instead, D8 can call the method that the class of the
// resource had before it became AutoCloseable, such as recycle() of TypedArray
// or release() of ContentProviderClient, or a static method that it generates
// and that takes the resource, such as the one that closes resources only
// known as AutoCloseable from API 19 on.
const ir::MethodDecl* CloseOf(const lir::Bytecode* bytecode, int* reg) {
  bool is_static = false;
  switch (bytecode->opcode) {
    case dex::OP_INVOKE_VIRTUAL:
    case dex::OP_INVOKE_VIRTUAL_RANGE:
    case dex::OP_INVOKE_INTERFACE:
    case dex::OP_INVOKE_INTERFACE_RANGE:
      break;
    case dex::OP_INVOKE_STATIC:
    case dex::OP_INVOKE_STATIC_RANGE:
      is_static = true;
      break;
    default:
      return nullptr;
  }
  const ir::MethodDecl* method = Callee(bytecode);
  if (method == nullptr) {
    return nullptr;
  }
  const char* name = method->name->c_str();
  if (!is_static && strcmp(name, "close") != 0 &&
      strcmp(name, "recycle") != 0 && strcmp(name, "release") != 0) {
    return nullptr;
  }
  const std::vector<int> arguments = Arguments(bytecode);
  if (arguments.size() != 1) {
    return nullptr;
  }
  const std::string signature = method->prototype->Signature();
  if (is_static ? !TakesOneObject(signature)
                : signature.compare(0, 2, "()") != 0) {
    return nullptr;
  }
  *reg = arguments[0];
  return method;
}

// If `bytecode` closes a resource through $closeResource, returns that method
// and sets `reg` to the register of the resource and `primary` to the register
// of the exception that it passes.
const ir::MethodDecl* CloseResourceOf(const lir::Bytecode* bytecode, int* reg,
                                      int* primary) {
  if (bytecode->opcode != dex::OP_INVOKE_STATIC &&
      bytecode->opcode != dex::OP_INVOKE_STATIC_RANGE) {
    return nullptr;
  }
  const ir::MethodDecl* method = Callee(bytecode);
  const std::vector<int> arguments = Arguments(bytecode);
  if (method == nullptr || arguments.size() != 2 ||
      !HasNameAndSignature(method, kCloseResource, kCloseResourceSignature)) {
    return nullptr;
  }
  *primary = arguments[0];
  *reg = arguments[1];
  return method;
}

// Returns true if `bytecode` loads 0, null, into register `reg`.
bool LoadsZero(const lir::Bytecode* bytecode, int reg) {
  if ((bytecode->opcode != dex::OP_CONST_4 &&
       bytecode->opcode != dex::OP_CONST_16 &&
       bytecode->opcode != dex::OP_CONST) ||
      Register(bytecode, 0) != reg || bytecode->operands.size() < 2) {
    return false;
  }
  auto* literal = dynamic_cast<const lir::Const32*>(bytecode->operands[1]);
  return literal != nullptr && literal->u.u4_value == 0;
}

// Returns true if `a` and `b` are the same method.
bool SameMethod(const ir::MethodDecl* a, const ir::MethodDecl* b) {
  return strcmp(a->parent->descriptor->c_str(),
                b->parent->descriptor->c_str()) == 0 &&
         strcmp(a->name->c_str(), b->name->c_str()) == 0 &&
         a->prototype->Signature() == b->prototype->Signature();
}

// What the registers hold in a handler.
class Values {
 public:
  int Of(int reg) const {
    auto it = values_.find(reg);
    return it == values_.end() ? reg : it->second;
  }

  void Set(int reg, int value) { values_[reg] = value; }

  // Updates the values for `move`, which copies the value of a register to
  // another one, or the values of a pair of registers.
  void Move(const lir::Bytecode* move) {
    if (move->operands.empty()) {
      return;
    }
    if (auto* pair = dynamic_cast<const lir::VRegPair*>(move->operands[0])) {
      Set(static_cast<int>(pair->base_reg), kOtherValue);
      Set(static_cast<int>(pair->base_reg) + 1, kOtherValue);
      return;
    }
    const int destination = Register(move, 0);
    const int source = Register(move, 1);
    if (destination >= 0) {
      Set(destination, source < 0 ? kOtherValue : Of(source));
    }
  }

  // One of the paths that meet at a bytecode: its values, and the value of the
  // exception that it throws.
  struct Branch {
    const Values* values;
    int thrown;
  };

  // Returns the values where `paths` meet: a register holds kCaught if it
  // holds the exception that each path throws on all of them, keeps a value
  // from before the handler if it has it on all of them, and holds kOtherValue
  // otherwise.
  static Values Meet(std::initializer_list<Branch> paths) {
    std::unordered_set<int> registers;
    for (const Branch& path : paths) {
      for (const auto& entry : path.values->values_) {
        registers.insert(entry.first);
      }
    }
    Values meet;
    for (int reg : registers) {
      const int first = paths.begin()->values->Of(reg);
      bool thrown = true;
      bool same = true;
      for (const Branch& path : paths) {
        thrown = thrown && path.values->Of(reg) == path.thrown;
        same = same && path.values->Of(reg) == first;
      }
      if (thrown) {
        meet.Set(reg, kCaught);
      } else {
        meet.Set(reg, same && first >= 0 ? first : kOtherValue);
      }
    }
    return meet;
  }

 private:
  std::unordered_map<int, int> values_;
};

// One path through a handler: it follows the gotos with which D8 splits the
// handler and skips the moves with which it shuffles registers and the nops
// that it adds for lines in debug builds, adding them all to `code`.
class Path {
 public:
  Path(Values values, std::vector<lir::Bytecode*>* code)
      : values_(std::move(values)), code_(code) {}

  // Returns the first bytecode at or after `bytecode` that isn't a goto, a move
  // or a nop, or nullptr at the end of the code or if the path loops.
  lir::Bytecode* From(lir::Bytecode* bytecode) {
    while (bytecode != nullptr && visited_.insert(bytecode).second) {
      code_->push_back(bytecode);
      if (IsGoto(bytecode->opcode)) {
        bytecode = SkipNonBytecodes(BranchTarget(bytecode));
      } else if (IsMove(bytecode->opcode)) {
        values_.Move(bytecode);
        bytecode = NextBytecode(bytecode);
      } else if (bytecode->opcode == dex::OP_NOP) {
        bytecode = NextBytecode(bytecode);
      } else {
        return bytecode;
      }
    }
    return nullptr;
  }

  // Returns the first bytecode after `bytecode` that From() stops at.
  lir::Bytecode* After(lir::Bytecode* bytecode) {
    return From(NextBytecode(bytecode));
  }

  const Values& values() const { return values_; }

 private:
  Values values_;
  std::vector<lir::Bytecode*>* code_;
  std::unordered_set<const lir::Bytecode*> visited_;
};

// Returns true if `bytecode` throws the caught exception.
bool ThrowsCaught(const lir::Bytecode* bytecode, const Values& values) {
  return bytecode != nullptr && bytecode->opcode == dex::OP_THROW &&
         values.Of(Register(bytecode, 0)) == kCaught;
}

// Returns true if `bytecode` adds the exception of the close to `primary`, the
// value of the exception to keep, as a suppressed one, by calling
// addSuppressed() or, below API 19, the static backport of D8.
bool Suppresses(const lir::Bytecode* bytecode, const Values& values,
                int primary) {
  const ir::MethodDecl* method = Callee(bytecode);
  const std::vector<int> arguments = Arguments(bytecode);
  if (method == nullptr || arguments.size() != 2 ||
      values.Of(arguments[0]) != primary ||
      values.Of(arguments[1]) != kSuppressed) {
    return false;
  }
  switch (bytecode->opcode) {
    case dex::OP_INVOKE_VIRTUAL:
    case dex::OP_INVOKE_VIRTUAL_RANGE:
      return strcmp(method->parent->descriptor->c_str(), kThrowable) == 0 &&
             HasNameAndSignature(method, "addSuppressed", kOneThrowable);
    case dex::OP_INVOKE_STATIC:
    case dex::OP_INVOKE_STATIC_RANGE:
      return method->prototype->Signature() == kTwoThrowables;
    default:
      return false;
  }
}

// The facts about a method that the filter needs.
struct MethodInfo {
  // The handlers that catch any exception or Throwable, each once.
  std::vector<lir::Label*> labels;
  // The bytecodes, in code order.
  std::vector<lir::Bytecode*> bytecodes;
  // The line of each bytecode, or -1.
  std::unordered_map<const lir::Bytecode*, int> lines;
  // The handler of the try block of each bytecode in one, if it catches any
  // exception or Throwable.
  std::unordered_map<const lir::Bytecode*, lir::Label*> handlers;
  // The first bytecode in code order that each of those handlers protects.
  std::unordered_map<const lir::Label*, lir::Bytecode*> first_protected;
  // The typed handlers that come before each of those handlers in a try
  // block: the catches of try statements inside the code that it protects.
  std::unordered_map<const lir::Label*, std::vector<lir::Label*>>
      inner_handlers;
};

int LineOf(const MethodInfo& info, const lir::Bytecode* bytecode) {
  auto it = info.lines.find(bytecode);
  return it == info.lines.end() ? -1 : it->second;
}

// Returns the first bytecode in code order that the handler at `label`
// protects, or nullptr.
lir::Bytecode* FirstProtected(const MethodInfo& info, const lir::Label* label) {
  auto it = info.first_protected.find(label);
  return it == info.first_protected.end() ? nullptr : it->second;
}

// What the handler of a try-with-resources statement matched.
struct Handler {
  // Its bytecodes.
  std::vector<lir::Bytecode*> code;
  // The register of the resource before the handler.
  int resource = kOtherValue;
  // The close() that it calls.
  const ir::MethodDecl* close = nullptr;
  // Whether it checks the resource for null.
  bool checks_null = false;
  // Whether it closes the resource through $closeResource.
  bool close_resource = false;
  // For ECJ, the handlers of the try block of the close when the block ends:
  // the suppression of the resource and the other entries into it.
  std::unordered_set<const lir::Label*> entries;
  // For javac 7 to 10, which close the resource at each exit of the block as a
  // finally block does: whether all these closes count, not just the last one.
  // javac gives them the line of the close of the handler, `line`.
  bool every_close = false;
  int line = -1;
};

// Matches the handler of the close in a handler, at `label`: it catches the
// exception of the close, adds it to the exception with the value `primary`
// and throws the caught one. `values` are those at the close. Sets the call
// and the throw.
bool MatchSuppression(lir::Label* label, Values values, int primary,
                      std::vector<lir::Bytecode*>* code, lir::Bytecode** call,
                      lir::Bytecode** rethrown) {
  lir::Bytecode* suppressed = SkipNonBytecodes(label);
  if (suppressed == nullptr || suppressed->opcode != dex::OP_MOVE_EXCEPTION) {
    return false;
  }
  code->push_back(suppressed);
  values.Set(Register(suppressed, 0), kSuppressed);
  Path path(std::move(values), code);
  *call = path.After(suppressed);
  if (*call == nullptr || !Suppresses(*call, path.values(), primary)) {
    return false;
  }
  *rethrown = path.After(*call);
  return ThrowsCaught(*rethrown, path.values());
}

// Matches the handler of a try-with-resources statement at `label`, which
// closes the resource. It adds the exception of the close to the exception
// with the value `primary`: the exception that it caught or, for javac 7 to
// 10, the one that the catch of Throwable caught. `entry` are the values when
// it starts. Returns false if it isn't one.
bool MatchHandler(lir::Label* label, const MethodInfo& info, Values entry,
                  int primary, Handler* handler) {
  lir::Bytecode* caught = SkipNonBytecodes(label);
  if (caught == nullptr || caught->opcode != dex::OP_MOVE_EXCEPTION) {
    return false;
  }
  std::vector<lir::Bytecode*>& code = handler->code;
  code.push_back(caught);
  entry.Set(Register(caught, 0), kCaught);
  Path main(std::move(entry), &code);
  lir::Bytecode* close = main.After(caught);
  // The check for null, with the values at that point.
  lir::Bytecode* check = nullptr;
  Values at_check;
  if (close != nullptr && close->opcode == dex::OP_IF_EQZ) {
    check = close;
    at_check = main.values();
    close = main.After(check);
  }
  if (close == nullptr) {
    return false;
  }
  int closed = -1;
  int passed = -1;
  // javac 9 and 10 can close the resource through $closeResource, which adds
  // the exception of the close to the primary one itself, the one that the
  // first handler caught.
  handler->close = CloseResourceOf(close, &closed, &passed);
  handler->close_resource = handler->close != nullptr;
  if (!handler->close_resource) {
    handler->close = CloseOf(close, &closed);
  }
  if (handler->close == nullptr) {
    return false;
  }
  handler->resource = main.values().Of(closed);
  handler->line = LineOf(info, close);
  handler->checks_null = check != nullptr;
  if (check != nullptr &&
      at_check.Of(Register(check, 0)) != handler->resource) {
    return false;
  }
  if (handler->close_resource && main.values().Of(passed) != kPrimary) {
    return false;
  }
  auto inner = info.handlers.find(close);
  if (!handler->close_resource && inner == info.handlers.end()) {
    return false;
  }
  const Values at_close = main.values();
  lir::Bytecode* thrown = main.After(close);
  if (!ThrowsCaught(thrown, main.values())) {
    return false;
  }
  lir::Bytecode* call = nullptr;
  lir::Bytecode* rethrown = nullptr;
  if (!handler->close_resource &&
      !MatchSuppression(inner->second, at_close, primary, &code, &call,
                        &rethrown)) {
    return false;
  }
  // A null resource goes straight to a throw of the caught exception.
  lir::Bytecode* skipped = nullptr;
  if (check != nullptr) {
    Path null_resource(std::move(at_check), &code);
    skipped = null_resource.From(SkipNonBytecodes(BranchTarget(check)));
    if (!ThrowsCaught(skipped, null_resource.values())) {
      return false;
    }
  }
  // javac gives the whole handler the line of the try statement, or javac 7 to
  // 10 the line of the end of its block. These bytecodes can throw, so D8
  // keeps their lines in release mode too.
  for (const lir::Bytecode* other : {call, thrown, rethrown, skipped}) {
    if (other != nullptr && LineOf(info, other) != LineOf(info, close)) {
      return false;
    }
  }
  return true;
}

// Matches the first handler of a try-with-resources statement of javac 7 to
// 10, at `label`: it catches Throwable and throws the exception again, to the
// handler that closes the resource, keeping it as the primary exception.
// Returns the label of that handler, or nullptr, adds the bytecodes to `code`
// and sets `values` to the values at the throw.
lir::Label* MatchPrimary(lir::Label* label, const MethodInfo& info,
                         std::vector<lir::Bytecode*>* code, Values* values) {
  lir::Bytecode* caught = SkipNonBytecodes(label);
  if (caught == nullptr || caught->opcode != dex::OP_MOVE_EXCEPTION) {
    return nullptr;
  }
  code->push_back(caught);
  Values entry;
  entry.Set(Register(caught, 0), kPrimary);
  Path path(std::move(entry), code);
  lir::Bytecode* thrown = path.After(caught);
  if (thrown == nullptr || thrown->opcode != dex::OP_THROW ||
      path.values().Of(Register(thrown, 0)) != kPrimary) {
    return nullptr;
  }
  auto next = info.handlers.find(thrown);
  if (next == info.handlers.end()) {
    return nullptr;
  }
  *values = path.values();
  return next->second;
}

// Returns the bytecode before `instr`, or nullptr.
lir::Bytecode* PreviousBytecode(lir::Instruction* instr) {
  if (instr == nullptr) {
    return nullptr;
  }
  for (auto* node = instr->prev; node != nullptr; node = node->prev) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(node)) {
      return bytecode;
    }
  }
  return nullptr;
}

// Returns true if `bytecode` is in a try block whose handler is at `label`.
bool ProtectedBy(const MethodInfo& info, const lir::Bytecode* bytecode,
                 const lir::Label* label) {
  auto it = info.handlers.find(bytecode);
  return it != info.handlers.end() && it->second == label;
}

// Returns true if `bytecode` is in a try block whose handler is one of
// `labels`.
bool ProtectedByOneOf(const MethodInfo& info, const lir::Bytecode* bytecode,
                      const std::unordered_set<const lir::Label*>& labels) {
  auto it = info.handlers.find(bytecode);
  return it != info.handlers.end() && labels.count(it->second) > 0;
}

// Updates `copies`, the registers that hold the resource, for `bytecode`: a
// move of one of them adds its destination, and any other write removes the
// register that it writes.
void UpdateCopies(const lir::Bytecode* bytecode,
                  std::unordered_set<int>* copies) {
  const int destination = Register(bytecode, 0);
  if (IsMove(bytecode->opcode) && destination >= 0) {
    const int source = Register(bytecode, 1);
    if (source >= 0 && copies->count(source) > 0) {
      copies->insert(destination);
    } else {
      copies->erase(destination);
    }
    return;
  }
  for (auto it = copies->begin(); it != copies->end();) {
    it = Writes(bytecode, *it) ? copies->erase(it) : std::next(it);
  }
}

// Adds the bytecodes of `close`, a close of the resource of `handler` when the
// block ends, to `code`: its check for null `check`, if any, the close, and a
// goto right after it on the same line. A close through $closeResource passes
// null as the primary exception, which D8 loads on the same line before the
// check or the call; that load is added too.
void AddClose(lir::Bytecode* check, lir::Bytecode* close,
              const Handler& handler, const MethodInfo& info,
              std::vector<lir::Bytecode*>* code) {
  if (check != nullptr) {
    code->push_back(check);
  }
  code->push_back(close);
  if (handler.close_resource) {
    const int passed = Arguments(close)[0];
    for (lir::Bytecode* previous = PreviousBytecode(close);
         previous != nullptr && LineOf(info, previous) == LineOf(info, close);
         previous = PreviousBytecode(previous)) {
      if (Writes(previous, passed)) {
        if (LoadsZero(previous, passed)) {
          code->push_back(previous);
        }
        break;
      }
    }
  }
  // D8 can merge the goto with which the block jumps over the handler with a
  // goto on another line that follows it, which belongs to that line.
  lir::Bytecode* next = NextBytecode(close);
  if (next != nullptr && IsGoto(next->opcode) &&
      LineOf(info, next) == LineOf(info, close)) {
    code->push_back(next);
  }
}

// D8 can merge the close at an early exit of the block, such as a continue,
// with the close `close` when it is the same code: the early exit then checks
// the resource for null like `check`, if any, and goes to that close, on its
// line. Adds those bytecodes to `code`.
void AddMergedExits(lir::Bytecode* check, lir::Bytecode* close,
                    const MethodInfo& info, std::vector<lir::Bytecode*>* code) {
  for (lir::Bytecode* bytecode : info.bytecodes) {
    if (!IsGoto(bytecode->opcode) ||
        SkipNonBytecodes(BranchTarget(bytecode)) != close ||
        LineOf(info, bytecode) != LineOf(info, close)) {
      continue;
    }
    if (check != nullptr) {
      lir::Bytecode* exit_check = PreviousBytecode(bytecode);
      if (exit_check == nullptr || exit_check->opcode != dex::OP_IF_EQZ ||
          Register(exit_check, 0) != Register(check, 0) ||
          SkipNonBytecodes(BranchTarget(exit_check)) !=
              SkipNonBytecodes(BranchTarget(check)) ||
          LineOf(info, exit_check) != LineOf(info, close)) {
        continue;
      }
      code->push_back(exit_check);
    }
    code->push_back(bytecode);
  }
}

// Returns the bytecode after the last write of register `reg` before
// `bytecode`, or the first bytecode of the method if there is none.
lir::Bytecode* AfterLastWrite(lir::Bytecode* bytecode, int reg) {
  for (lir::Bytecode* previous = PreviousBytecode(bytecode);
       previous != nullptr; previous = PreviousBytecode(previous)) {
    if (Writes(previous, reg)) {
      return NextBytecode(previous);
    }
    bytecode = previous;
  }
  return bytecode;
}

// Returns the bytecodes of the block of a statement of javac 11 or later that
// lead to the bytecodes that the handler at `label` protects without being
// protected themselves, such as a switch or a branch at the start of the
// block. They come from the line of the try statement (`handler.line`) on,
// after the resource is stored, and lead there through fallthroughs and
// forward jumps. D8 can put the code of another branch before the block, such
// as the default of a switch whose case holds the statement, but that code
// doesn't lead into the block, and a jump back to the start of the block comes
// from a loop, from the code after the statement. Without lines, there are
// none.
std::vector<lir::Bytecode*> BlockEntries(
    lir::Label* label, const Handler& handler, const MethodInfo& info,
    const std::unordered_set<const lir::Bytecode*>& in_handler) {
  std::vector<lir::Bytecode*> entries;
  if (handler.line == -1) {
    return entries;
  }
  std::unordered_map<const lir::Bytecode*, size_t> position;
  for (size_t i = 0; i < info.bytecodes.size(); ++i) {
    position[info.bytecodes[i]] = i;
  }
  // The bytecodes that can be part of the block and that fall through or
  // jump forward to each bytecode.
  std::unordered_map<const lir::Bytecode*, std::vector<lir::Bytecode*>> sources;
  for (size_t i = 0; i < info.bytecodes.size(); ++i) {
    lir::Bytecode* bytecode = info.bytecodes[i];
    if (in_handler.count(bytecode) > 0 || Writes(bytecode, handler.resource) ||
        LineOf(info, bytecode) < handler.line) {
      continue;
    }
    if ((dex::GetFlagsFromOpcode(bytecode->opcode) & dex::kContinue) != 0) {
      lir::Bytecode* next = NextBytecode(bytecode);
      if (next != nullptr) {
        sources[next].push_back(bytecode);
      }
    }
    for (lir::Label* target : JumpTargets(bytecode)) {
      auto it = position.find(SkipNonBytecodes(target));
      if (target != label && it != position.end() && it->second > i) {
        sources[it->first].push_back(bytecode);
      }
    }
  }
  std::unordered_set<const lir::Bytecode*> reached;
  std::vector<const lir::Bytecode*> worklist;
  for (lir::Bytecode* bytecode : info.bytecodes) {
    if (ProtectedBy(info, bytecode, label)) {
      reached.insert(bytecode);
      worklist.push_back(bytecode);
    }
  }
  while (!worklist.empty()) {
    auto it = sources.find(worklist.back());
    worklist.pop_back();
    if (it == sources.end()) {
      continue;
    }
    for (lir::Bytecode* source : it->second) {
      if (reached.insert(source).second) {
        entries.push_back(source);
        worklist.push_back(source);
      }
    }
  }
  return entries;
}

// For a close `close`, with its check for null `check`, returns the line of
// the return statement inside the block that it closes the resource for, or
// numeric_limits<int>::max() if it isn't one. Both javac and ECJ put a close
// on the line of the end of the block, and an early return after it, past any
// closes of outer statements, on an earlier line, the line of the return.
int ExitLine(lir::Bytecode* check, lir::Bytecode* close,
             const MethodInfo& info) {
  constexpr int kBlockEnd = std::numeric_limits<int>::max();
  const int close_line = LineOf(info, close);
  if (close_line == -1) {
    return kBlockEnd;
  }
  auto after_close = [&](lir::Bytecode* check_instr,
                         lir::Bytecode* close_instr) -> lir::Bytecode* {
    lir::Bytecode* next = NextBytecode(close_instr);
    if (next != nullptr && IsGoto(next->opcode) &&
        LineOf(info, next) == LineOf(info, close_instr)) {
      next = SkipNonBytecodes(BranchTarget(next));
    }
    if (check_instr != nullptr &&
        SkipNonBytecodes(BranchTarget(check_instr)) != next) {
      return nullptr;
    }
    return next;
  };
  lir::Bytecode* after = after_close(check, close);
  std::unordered_set<const lir::Bytecode*> visited;
  while (after != nullptr && visited.insert(after).second) {
    if (after->opcode == dex::OP_NOP || IsMove(after->opcode) ||
        (after->opcode >= dex::OP_CONST_4 &&
         after->opcode <= dex::OP_CONST_CLASS)) {
      after = NextBytecode(after);
      continue;
    }
    lir::Bytecode* outer_check = nullptr;
    lir::Bytecode* outer = after;
    if (outer->opcode == dex::OP_IF_EQZ) {
      outer_check = outer;
      outer = NextBytecode(outer);
    }
    int closed = -1;
    int passed = -1;
    if (outer != nullptr && LoadsZero(outer, Register(outer, 0))) {
      outer = NextBytecode(outer);
    }
    if (outer != nullptr && LineOf(info, outer) >= close_line &&
        (CloseOf(outer, &closed) != nullptr ||
         CloseResourceOf(outer, &closed, &passed) != nullptr) &&
        (outer_check == nullptr || Register(outer_check, 0) == closed)) {
      after = after_close(outer_check, outer);
      continue;
    }
    break;
  }
  if (after == nullptr || after->opcode < dex::OP_RETURN_VOID ||
      after->opcode > dex::OP_RETURN_OBJECT) {
    return kBlockEnd;
  }
  const int return_line = LineOf(info, after);
  return return_line != -1 && return_line < close_line ? return_line
                                                       : kBlockEnd;
}

// Finds the close of the resource of `handler` when the block of its statement
// ends, and adds its bytecodes to `code` (see AddClose()). The close is
// reached from the block along control-flow paths outside `handler` before the
// registers that hold the resource are overwritten: D8 puts the handlers after
// the code that follows the statement, though it can put code of the block
// after the handler, it can copy the resource to another register before the
// close, and once the statement ends, it can reuse those registers.
// For ECJ, the close is in a try block that goes to one of `handler.entries`.
// For javac 7 to 10, all closes on the line of the close of the handler count,
// and the early exits merged with them; otherwise a close not followed by a
// return from an earlier line of the block is preferred, or the one whose
// return is on the last line, and of the tied closes, the last one before the
// code of the handler counts, or else the last one after it.
// Returns that close, or nullptr if there is none.
lir::Bytecode* MatchClose(lir::Label* label, lir::Bytecode* first,
                          const Handler& handler, const MethodInfo& info,
                          std::vector<lir::Bytecode*>* code) {
  if (first == nullptr) {
    return nullptr;
  }
  // Without the handler after `first`, D8 didn't lay this out as a
  // try-with-resources statement.
  bool handler_follows = false;
  for (const lir::Instruction* node = first; node != nullptr;
       node = node->next) {
    if (node == label) {
      handler_follows = true;
      break;
    }
  }
  if (!handler_follows) {
    return nullptr;
  }
  std::unordered_set<const lir::Bytecode*> in_handler(handler.code.begin(),
                                                      handler.code.end());
  // javac 7 to 10 close the resource at each exit of the block, as a finally
  // block does, and the build-time report filters all these closes. An early
  // exit can come before `first`, so look from where the resource is stored
  // on, or from the start of the method for a parameter, which the code before
  // the statement can close on its own line. Look from there for ECJ too: its
  // handler has the line of the end of the block, but only the closes of the
  // statement are in try blocks that go to `handler.entries`.
  std::vector<lir::Bytecode*> starts =
      handler.every_close || !handler.entries.empty()
          ? std::vector<lir::Bytecode*>{AfterLastWrite(first, handler.resource)}
          : BlockEntries(label, handler, info, in_handler);
  // At each visited bytecode, the registers known to hold the resource on some
  // path to it.
  std::unordered_map<const lir::Bytecode*, std::unordered_set<int>> states;
  std::vector<lir::Bytecode*> worklist;
  auto enqueue = [&](lir::Bytecode* target,
                     const std::unordered_set<int>& copies) {
    if (target == nullptr || copies.empty() || in_handler.count(target) > 0) {
      return;
    }
    std::unordered_set<int>& state = states[target];
    const size_t before = state.size();
    state.insert(copies.begin(), copies.end());
    if (state.size() > before) {
      worklist.push_back(target);
    }
  };
  for (lir::Bytecode* start : starts) {
    enqueue(start, {handler.resource});
  }
  // Every bytecode that the handler protects is also part of the block, even
  // when entered through an inner handler.
  for (lir::Bytecode* bytecode : info.bytecodes) {
    if (ProtectedBy(info, bytecode, label)) {
      enqueue(bytecode, {handler.resource});
    }
  }
  // So is the code of a catch of a try statement inside the block, which only
  // an exception leads to, even when it can't throw.
  auto inner = info.inner_handlers.find(label);
  if (inner != info.inner_handlers.end()) {
    for (lir::Label* inner_handler : inner->second) {
      enqueue(SkipNonBytecodes(inner_handler), {handler.resource});
    }
  }
  auto matches_close = [&](lir::Bytecode* call,
                           const std::unordered_set<int>& live,
                           lir::Bytecode** out_check) -> bool {
    if (call == nullptr || in_handler.count(call) > 0) {
      return false;
    }
    int closed = -1;
    int passed = -1;
    const ir::MethodDecl* method = handler.close_resource
                                       ? CloseResourceOf(call, &closed, &passed)
                                       : CloseOf(call, &closed);
    // A close in the block, which the handler protects, doesn't count.
    if (method == nullptr || live.count(closed) == 0 ||
        !SameMethod(method, handler.close) || ProtectedBy(info, call, label) ||
        (handler.every_close && LineOf(info, call) != handler.line) ||
        (!handler.entries.empty() &&
         !ProtectedByOneOf(info, call, handler.entries))) {
      return false;
    }
    lir::Bytecode* previous = PreviousBytecode(call);
    if (handler.close_resource && previous != nullptr &&
        LoadsZero(previous, passed)) {
      previous = PreviousBytecode(previous);
    }
    const bool checked =
        previous != nullptr && in_handler.count(previous) == 0 &&
        previous->opcode == dex::OP_IF_EQZ && Register(previous, 0) == closed;
    if (handler.checks_null && !checked) {
      return false;
    }
    *out_check = handler.checks_null ? previous : nullptr;
    return true;
  };
  std::unordered_map<const lir::Bytecode*, lir::Bytecode*> matched;
  while (!worklist.empty()) {
    lir::Bytecode* bytecode = worklist.back();
    worklist.pop_back();
    std::unordered_set<int> copies = states[bytecode];
    lir::Bytecode* check = nullptr;
    if (matches_close(bytecode, copies, &check)) {
      matched.emplace(bytecode, check);
      if (!handler.every_close) {
        continue;
      }
    } else if (!handler.every_close && handler.checks_null &&
               bytecode->opcode == dex::OP_IF_EQZ &&
               in_handler.count(bytecode) == 0) {
      lir::Bytecode* call = NextBytecode(bytecode);
      if (handler.close_resource && call != nullptr &&
          LoadsZero(call, Register(call, 0))) {
        call = NextBytecode(call);
      }
      if (matches_close(call, copies, &check) && check == bytecode) {
        matched.emplace(call, check);
        // Don't walk past a close of the statement into later closes of the
        // same parameter.
        continue;
      }
      // Nor past an early exit that D8 merges with a close of the statement:
      // it checks the resource like that close does and goes to it.
      if (call != nullptr && IsGoto(call->opcode)) {
        lir::Bytecode* close = SkipNonBytecodes(BranchTarget(call));
        if (matches_close(close, copies, &check) && check != nullptr &&
            Register(check, 0) == Register(bytecode, 0) &&
            SkipNonBytecodes(BranchTarget(check)) ==
                SkipNonBytecodes(BranchTarget(bytecode))) {
          matched.emplace(close, check);
          continue;
        }
      }
    }
    UpdateCopies(bytecode, &copies);
    if (copies.empty()) {
      continue;
    }
    if ((dex::GetFlagsFromOpcode(bytecode->opcode) & dex::kContinue) != 0) {
      enqueue(NextBytecode(bytecode), copies);
    }
    for (lir::Label* target : JumpTargets(bytecode)) {
      if (target != label) {
        enqueue(SkipNonBytecodes(target), copies);
      }
    }
  }
  if (matched.empty()) {
    return nullptr;
  }
  std::vector<std::pair<lir::Bytecode*, lir::Bytecode*>> closes;
  // The number of closes before the code of the handler, in code order. D8
  // can start the handler with a goto to the rest of its code, which it puts
  // after other code of the block.
  size_t before_handler = 0;
  const lir::Bytecode* handler_start = SkipNonBytecodes(label);
  if (handler_start != nullptr &&
      handler_start->opcode == dex::OP_MOVE_EXCEPTION) {
    lir::Bytecode* next = NextBytecode(SkipNonBytecodes(label));
    if (next != nullptr && IsGoto(next->opcode) && in_handler.count(next) > 0) {
      handler_start = SkipNonBytecodes(BranchTarget(next));
    }
  }
  bool after_handler = false;
  for (lir::Bytecode* bytecode : info.bytecodes) {
    after_handler = after_handler || bytecode == handler_start;
    auto it = matched.find(bytecode);
    if (it != matched.end()) {
      closes.emplace_back(it->second, bytecode);
      if (!after_handler) {
        ++before_handler;
      }
    }
  }
  if (!handler.every_close) {
    // ExitLine() doesn't tell the close at the end of the block from the close
    // of an early exit that isn't a return, such as a break: then the last
    // close before the handler counts, where javac puts the close at the end of
    // the block, or else the last one after it.
    size_t chosen = 0;
    int best_line = ExitLine(closes[0].first, closes[0].second, info);
    for (size_t i = 1; i < closes.size(); ++i) {
      const int line = ExitLine(closes[i].first, closes[i].second, info);
      if (line > best_line ||
          (line == best_line &&
           (i < before_handler || chosen >= before_handler))) {
        chosen = i;
        best_line = line;
      }
    }
    closes = {closes[chosen]};
  }
  for (const auto& [matched_check, close] : closes) {
    AddClose(matched_check, close, handler, info, code);
    if (handler.every_close) {
      AddMergedExits(matched_check, close, info, code);
    }
  }
  return closes.back().second;
}

// Matches a try-with-resources statement of javac whose handler, or first
// handler for javac 7 to 10, is at `label`, and adds its bytecodes to `code`.
bool MatchJavac(lir::Label* label, const MethodInfo& info,
                std::vector<lir::Bytecode*>* code) {
  Handler handler;
  // javac 7 to 10 first catch Throwable and throw it again, to the handler
  // that closes the resource.
  std::vector<lir::Bytecode*> primary;
  if (!MatchHandler(label, info, Values(), kCaught, &handler)) {
    handler = Handler();
    Values values;
    lir::Label* next = MatchPrimary(label, info, &primary, &values);
    if (next == nullptr ||
        !MatchHandler(next, info, std::move(values), kPrimary, &handler)) {
      return false;
    }
  }
  // Without line numbers, the closes of early exits look like a close of a
  // parameter before the statement, so only the one when the block ends counts.
  if (!primary.empty() && handler.line != -1) {
    handler.every_close = true;
  }
  if (MatchClose(label, FirstProtected(info, label), handler, info, code) ==
      nullptr) {
    return false;
  }
  code->insert(code->end(), handler.code.begin(), handler.code.end());
  code->insert(code->end(), primary.begin(), primary.end());
  return true;
}

// What a suppression of ECJ matched.
struct EcjSuppression {
  // Its check of the exception to throw for null, and the call that adds the
  // exception of the close to it.
  lir::Bytecode* test = nullptr;
  lir::Bytecode* call = nullptr;
  // Where its paths meet, and the values there.
  lir::Bytecode* next = nullptr;
  Values values;
};

// Matches the suppression of ECJ at `label`, the handler of the close of a
// resource in the handler of a try-with-resources statement. It catches the
// exception of the close and makes it the exception to throw if there is none
// yet, or else adds it to that exception, unless it is that exception, which
// it also catches when the handler throws it:
//     move-exception vT
//     if-eqz vP, :replace  (or if-nez vP, with the branches swapped)
//     if-eq vP, vT, :next  (or if-ne vP, vT, with the branches swapped)
//     invoke-virtual {vP, vT},
//         Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V
//     goto :next
//   :replace
//     move-object vP, vT
//   :next
// D8 can also move the exception to throw to another register on each path.
// `values` are those at the close, where the exception to throw has the value
// kCaught. Adds the bytecodes to `code` and sets `suppression`. Returns false
// if it isn't one.
bool MatchEcjSuppression(lir::Label* label, Values values,
                         std::vector<lir::Bytecode*>* code,
                         EcjSuppression* suppression) {
  lir::Bytecode* suppressed = SkipNonBytecodes(label);
  if (suppressed == nullptr || suppressed->opcode != dex::OP_MOVE_EXCEPTION) {
    return false;
  }
  code->push_back(suppressed);
  values.Set(Register(suppressed, 0), kSuppressed);
  Path start(std::move(values), code);
  lir::Bytecode* test = start.After(suppressed);
  if (test == nullptr ||
      (test->opcode != dex::OP_IF_EQZ && test->opcode != dex::OP_IF_NEZ) ||
      start.values().Of(Register(test, 0)) != kCaught) {
    return false;
  }
  lir::Bytecode* if_null = SkipNonBytecodes(BranchTarget(test));
  lir::Bytecode* if_not_null = NextBytecode(test);
  if (test->opcode == dex::OP_IF_NEZ) {
    std::swap(if_null, if_not_null);
  }
  Path replace(start.values(), code);
  lir::Bytecode* replaced = replace.From(if_null);
  Path other(start.values(), code);
  lir::Bytecode* same = other.From(if_not_null);
  if (same == nullptr ||
      (same->opcode != dex::OP_IF_EQ && same->opcode != dex::OP_IF_NE)) {
    return false;
  }
  const int first = other.values().Of(Register(same, 0));
  const int second = other.values().Of(Register(same, 1));
  if (!(first == kCaught && second == kSuppressed) &&
      !(first == kSuppressed && second == kCaught)) {
    return false;
  }
  lir::Bytecode* if_same = SkipNonBytecodes(BranchTarget(same));
  lir::Bytecode* if_other = NextBytecode(same);
  if (same->opcode == dex::OP_IF_NE) {
    std::swap(if_same, if_other);
  }
  Path keep(other.values(), code);
  lir::Bytecode* kept = keep.From(if_same);
  Path add(other.values(), code);
  lir::Bytecode* call = add.From(if_other);
  if (call == nullptr || !Suppresses(call, add.values(), kCaught)) {
    return false;
  }
  lir::Bytecode* added = add.After(call);
  if (replaced == nullptr || replaced != kept || replaced != added) {
    return false;
  }
  suppression->test = test;
  suppression->call = call;
  suppression->next = replaced;
  // Where the exception of the close replaced it, it is the one to throw.
  suppression->values = Values::Meet({{&replace.values(), kSuppressed},
                                      {&keep.values(), kCaught},
                                      {&add.values(), kCaught}});
  return true;
}

// A resource that the handler of a try-with-resources statement of ECJ closes.
struct EcjResource {
  // The resource, the close() that the handler calls and whether it checks the
  // resource for null.
  Handler handler;
  // The check of the exception to throw for null of the suppression around the
  // close.
  lir::Bytecode* test = nullptr;
};

// Matches the handler of a try-with-resources statement of ECJ at `label`: it
// catches any exception, which becomes the exception to throw, then closes
// each resource, the last one declared first, in a try block that goes to a
// suppression like the one above, and throws the exception to throw:
//   :handler
//     move-exception vP
//     if-eqz vR, :throw  (left out when the resource is created with new)
//     invoke-interface {vR}, LFoo;->close()V  (its try block goes to :suppress)
//   :throw
//     throw vP
//   :suppress
//     ... (the suppression, then the same for the next resource, or throw vP)
// ECJ gives the whole handler the line of the end of the block. Adds the
// bytecodes to `code` and the resources to `resources`, in the order of the
// closes. Returns false if it isn't one.
bool MatchEcjHandler(lir::Label* label, const MethodInfo& info,
                     std::vector<lir::Bytecode*>* code,
                     std::vector<EcjResource>* resources) {
  lir::Bytecode* caught = SkipNonBytecodes(label);
  if (caught == nullptr || caught->opcode != dex::OP_MOVE_EXCEPTION) {
    return false;
  }
  code->push_back(caught);
  Values values;
  values.Set(Register(caught, 0), kCaught);
  lir::Bytecode* next = NextBytecode(caught);
  // The bytecodes that can throw, whose lines D8 keeps in release mode too.
  std::vector<const lir::Bytecode*> throwing;
  std::unordered_set<const lir::Label*> suppressions;
  for (;;) {
    Path main(values, code);
    lir::Bytecode* close = main.From(next);
    if (!resources->empty() && ThrowsCaught(close, main.values())) {
      throwing.push_back(close);
      break;
    }
    // The check for null, with the values at that point.
    lir::Bytecode* check = nullptr;
    Values at_check;
    if (close != nullptr && close->opcode == dex::OP_IF_EQZ) {
      check = close;
      at_check = main.values();
      close = main.After(check);
    }
    EcjResource resource;
    Handler& closing = resource.handler;
    int closed = -1;
    closing.close = close == nullptr ? nullptr : CloseOf(close, &closed);
    if (closing.close == nullptr) {
      return false;
    }
    closing.resource = main.values().Of(closed);
    closing.checks_null = check != nullptr;
    if (check != nullptr &&
        at_check.Of(Register(check, 0)) != closing.resource) {
      return false;
    }
    auto suppression = info.handlers.find(close);
    if (suppression == info.handlers.end() ||
        !suppressions.insert(suppression->second).second) {
      return false;
    }
    const Values at_close = main.values();
    lir::Bytecode* thrown = main.After(close);
    if (!ThrowsCaught(thrown, main.values())) {
      return false;
    }
    throwing.push_back(close);
    throwing.push_back(thrown);
    // A null resource goes straight to a throw of the exception to throw.
    if (check != nullptr) {
      Path null_resource(std::move(at_check), code);
      lir::Bytecode* skipped =
          null_resource.From(SkipNonBytecodes(BranchTarget(check)));
      if (!ThrowsCaught(skipped, null_resource.values())) {
        return false;
      }
      throwing.push_back(skipped);
    }
    EcjSuppression matched;
    if (!MatchEcjSuppression(suppression->second, at_close, code, &matched)) {
      return false;
    }
    throwing.push_back(matched.call);
    resource.test = matched.test;
    resources->push_back(std::move(resource));
    values = std::move(matched.values);
    next = matched.next;
  }
  for (const lir::Bytecode* bytecode : throwing) {
    if (LineOf(info, bytecode) != LineOf(info, throwing.front())) {
      return false;
    }
  }
  return true;
}

// Returns the handlers that go on with the suppression of a resource of ECJ
// at its check `test`: the suppression, and the other entries into it that D8
// can give the creation of the resource and its close when the block ends.
// They catch the exception and can load null or move values first. Adds their
// bytecodes to `code`.
std::unordered_set<const lir::Label*> Entries(
    const lir::Bytecode* test, const MethodInfo& info,
    std::vector<lir::Bytecode*>* code) {
  std::unordered_set<const lir::Label*> entries;
  for (lir::Label* label : info.labels) {
    lir::Bytecode* caught = SkipNonBytecodes(label);
    if (caught == nullptr || caught->opcode != dex::OP_MOVE_EXCEPTION) {
      continue;
    }
    std::vector<lir::Bytecode*> entry = {caught};
    Path path(Values(), &entry);
    lir::Bytecode* bytecode = path.After(caught);
    while (bytecode != nullptr && bytecode != test &&
           LoadsZero(bytecode, Register(bytecode, 0))) {
      bytecode = path.After(bytecode);
    }
    if (bytecode == test) {
      entries.insert(label);
      code->insert(code->end(), entry.begin(), entry.end());
    }
  }
  return entries;
}

// The creation of a resource of ECJ is in the first try block that goes to
// one of its `entries`. Returns the bytecode after the last write of the
// register of `resource` in that try block, or in the moves right after it,
// with which D8 can copy the resource to its register when many registers are
// in use. Returns the first bytecode of the try block if none writes the
// register, as for a resource that comes from a parameter, or nullptr.
lir::Bytecode* AfterCreation(
    const std::unordered_set<const lir::Label*>& entries, int resource,
    const MethodInfo& info) {
  const std::vector<lir::Bytecode*>& bytecodes = info.bytecodes;
  size_t first = 0;
  while (first < bytecodes.size() &&
         !ProtectedByOneOf(info, bytecodes[first], entries)) {
    ++first;
  }
  if (first == bytecodes.size()) {
    return nullptr;
  }
  const lir::Label* entry = info.handlers.find(bytecodes[first])->second;
  lir::Bytecode* start = bytecodes[first];
  size_t i = first;
  for (; i + 1 < bytecodes.size() && ProtectedBy(info, bytecodes[i], entry);
       ++i) {
    if (Writes(bytecodes[i], resource)) {
      start = bytecodes[i + 1];
    }
  }
  for (; i + 1 < bytecodes.size() && IsMove(bytecodes[i]->opcode); ++i) {
    if (Writes(bytecodes[i], resource)) {
      start = bytecodes[i + 1];
    }
  }
  return start;
}

// Matches a try-with-resources statement of ECJ whose handler is at `label`,
// and adds its bytecodes to `code`. The block closes the resources too, each
// in a try block that goes to its suppression or another entry into it.
bool MatchEcj(lir::Label* label, const MethodInfo& info,
              std::vector<lir::Bytecode*>* code) {
  std::vector<lir::Bytecode*> handler_code;
  std::vector<EcjResource> resources;
  if (!MatchEcjHandler(label, info, &handler_code, &resources)) {
    return false;
  }
  std::vector<lir::Bytecode*> closes;
  for (EcjResource& resource : resources) {
    Handler& handler = resource.handler;
    handler.entries = Entries(resource.test, info, &handler_code);
    handler.code = handler_code;
    // When the block starts with another statement, the handlers of that
    // statement protect its start, so the first bytecode that `label` protects
    // can come after the close: look from the creation of the resource on.
    if (MatchClose(label, FirstProtected(info, label), handler, info,
                   &closes) == nullptr &&
        MatchClose(label,
                   AfterCreation(handler.entries, handler.resource, info),
                   handler, info, &closes) == nullptr) {
      return false;
    }
  }
  code->insert(code->end(), closes.begin(), closes.end());
  code->insert(code->end(), handler_code.begin(), handler_code.end());
  return true;
}

}  // namespace

void TryWithResourcesFilter::Filter(const FilterContext& context,
                                    ir::EncodedMethod* ir_method,
                                    lir::CodeIr& code_ir,
                                    FilterOutput& output) {
  // The handlers that catch any exception or Throwable, each once, and the
  // handler of each try block.
  MethodInfo info;
  std::vector<lir::Label*>& labels = info.labels;
  std::unordered_set<const lir::Label*> seen;
  std::unordered_map<const lir::TryBlockBegin*, lir::Label*> handler_of_try;
  for (auto* instr : code_ir.instructions) {
    auto* end = dynamic_cast<lir::TryBlockEnd*>(instr);
    if (end == nullptr) {
      continue;
    }
    // A catch of Throwable gets every exception that no earlier handler of the
    // try block catches, so the catch of any exception never runs.
    lir::Label* handler = nullptr;
    std::vector<lir::Label*> inner;
    for (const lir::CatchHandler& typed : end->handlers) {
      if (typed.ir_type != nullptr && typed.ir_type->descriptor != nullptr &&
          strcmp(typed.ir_type->descriptor->c_str(), kThrowable) == 0) {
        handler = typed.label;
        break;
      }
      inner.push_back(typed.label);
    }
    if (handler == nullptr) {
      handler = end->catch_all;
    }
    if (handler == nullptr) {
      continue;
    }
    handler_of_try[end->try_begin] = handler;
    std::vector<lir::Label*>& inner_handlers = info.inner_handlers[handler];
    for (lir::Label* inner_handler : inner) {
      if (std::find(inner_handlers.begin(), inner_handlers.end(),
                    inner_handler) == inner_handlers.end()) {
        inner_handlers.push_back(inner_handler);
      }
    }
    if (seen.insert(handler).second) {
      labels.push_back(handler);
    }
  }
  if (labels.empty()) {
    return;
  }
  int line = -1;
  lir::Label* current = nullptr;
  for (auto* instr : code_ir.instructions) {
    if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
      info.bytecodes.push_back(bytecode);
      info.lines[bytecode] = line;
      if (current != nullptr) {
        info.handlers[bytecode] = current;
        info.first_protected.emplace(current, bytecode);
      }
    } else if (auto* annotation =
                   dynamic_cast<const lir::DbgInfoAnnotation*>(instr)) {
      if (annotation->dbg_opcode == dex::DBG_ADVANCE_LINE &&
          !annotation->operands.empty()) {
        if (auto* number =
                dynamic_cast<const lir::LineNumber*>(annotation->operands[0])) {
          line = number->line;
        }
      }
    } else if (auto* begin = dynamic_cast<const lir::TryBlockBegin*>(instr)) {
      auto it = handler_of_try.find(begin);
      current = it == handler_of_try.end() ? nullptr : it->second;
    } else if (dynamic_cast<const lir::TryBlockEnd*>(instr) != nullptr) {
      current = nullptr;
    }
  }
  for (lir::Label* label : labels) {
    std::vector<lir::Bytecode*> code;
    if (!MatchJavac(label, info, &code) && !MatchEcj(label, info, &code)) {
      continue;
    }
    // Each bytecode is ignored on its own, in code order, since D8 can lay out
    // the handler in pieces.
    std::unordered_set<const lir::Bytecode*> ignored(code.begin(), code.end());
    for (auto* instr : code_ir.instructions) {
      auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
      if (bytecode != nullptr && ignored.count(bytecode) > 0) {
        output.Ignore(bytecode, bytecode);
      }
    }
  }
}

REGISTER_NON_KOTLIN_FILTER(TryWithResourcesFilter)

}  // namespace coverage
