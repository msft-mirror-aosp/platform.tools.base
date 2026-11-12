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

#include "tools/base/android-test/coverage/agent/native/kotlin_serializable_filter.h"

#include <cstddef>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kSerializerName[] = "serializer";
constexpr char kSerializerSignature[] = "()Lkotlinx/serialization/KSerializer;";
constexpr char kKSerializer[] = "Lkotlinx/serialization/KSerializer;";
constexpr char kCachedSerializerName[] = "get$cachedSerializer";
constexpr char kInstanceName[] = "INSTANCE";
constexpr char kConstructorName[] = "<init>";
constexpr char kNoParameters[] = "()V";
constexpr char kConstructorMarker[] =
    "Lkotlinx/serialization/internal/SerializationConstructorMarker;";
constexpr std::string_view kCompanionSuffix = "$Companion;";
constexpr std::string_view kWriteSelfPrefix = "write$Self$";
constexpr std::string_view kInitLambdaPrefix = "_init_$_anonymous_";
constexpr std::string_view kChildSerializersLambdaPrefix =
    "_childSerializers$_anonymous_";

// The line number that means none, and the one that means the constructor of
// the companion object wasn't found.
constexpr int kNoLine = -1;
constexpr int kNoConstructor = -2;

bool StartsWith(std::string_view text, std::string_view prefix) {
  return text.substr(0, prefix.size()) == prefix;
}

bool EndsWith(std::string_view text, std::string_view suffix) {
  return text.size() >= suffix.size() &&
         text.substr(text.size() - suffix.size()) == suffix;
}

// Returns true if `opcode` is a move with which the agent copies a parameter
// back to its register when it grows the frame of a method.
bool IsAgentCopy(dex::Opcode opcode) {
  return opcode == dex::OP_MOVE_16 || opcode == dex::OP_MOVE_WIDE_16 ||
         opcode == dex::OP_MOVE_OBJECT_16;
}

// Returns the line number that `instr` sets, or kNoLine if it isn't the
// annotation of a line number.
int LineOf(const lir::Instruction* instr) {
  auto* annotation = dynamic_cast<const lir::DbgInfoAnnotation*>(instr);
  if (annotation == nullptr ||
      annotation->dbg_opcode != dex::DBG_ADVANCE_LINE ||
      annotation->operands.empty()) {
    return kNoLine;
  }
  auto* line = dynamic_cast<const lir::LineNumber*>(annotation->operands[0]);
  return line == nullptr ? kNoLine : line->line;
}

// The code of a method, after the copies with which the agent starts it when
// it grows its frame.
struct MethodCode {
  std::vector<const lir::Bytecode*> bytecodes;
  // The line number right before the first bytecode, or kNoLine.
  int first_line = kNoLine;
};

// Reads the bytecodes of `code_ir` and the line number right before the first
// one. Slicer first puts the annotation of the start line of the debug
// information, which isn't a line number of the method.
MethodCode ReadCode(const lir::CodeIr& code_ir) {
  MethodCode code;
  bool skipped_start_line = false;
  for (auto* instr : code_ir.instructions) {
    if (auto* bytecode = dynamic_cast<const lir::Bytecode*>(instr)) {
      if (code.bytecodes.empty() && IsAgentCopy(bytecode->opcode)) {
        continue;
      }
      code.bytecodes.push_back(bytecode);
      continue;
    }
    int line = LineOf(instr);
    if (line == kNoLine) {
      continue;
    }
    if (!skipped_start_line) {
      skipped_start_line = true;
    } else if (code.bytecodes.empty()) {
      code.first_line = line;
    }
  }
  return code;
}

// Returns the first line number of `code_ir`, or kNoLine.
int FirstLine(const lir::CodeIr& code_ir) {
  bool skipped_start_line = false;
  for (auto* instr : code_ir.instructions) {
    int line = LineOf(instr);
    if (line == kNoLine) {
      continue;
    }
    if (skipped_start_line) {
      return line;
    }
    skipped_start_line = true;
  }
  return kNoLine;
}

// Returns the first line number of the constructor without parameters of the
// class of `ir_method`, if the class is a companion object. Returns kNoLine if
// that constructor has no line numbers, and kNoConstructor if there's no such
// constructor or the class isn't a companion object.
int CompanionConstructorLine(const FilterContext& context,
                             const ir::EncodedMethod* ir_method,
                             const lir::CodeIr& code_ir) {
  const ir::Type* type = ir_method->decl->parent;
  if (!EndsWith(context.class_name, kCompanionSuffix) || type == nullptr ||
      type->class_def == nullptr) {
    return kNoConstructor;
  }
  for (ir::EncodedMethod* method : type->class_def->direct_methods) {
    const ir::MethodDecl* decl = method->decl;
    if (method->code != nullptr && decl != nullptr && decl->name != nullptr &&
        decl->prototype != nullptr &&
        strcmp(decl->name->c_str(), kConstructorName) == 0 &&
        decl->prototype->Signature() == kNoParameters) {
      lir::CodeIr constructor(method, code_ir.dex_ir);
      return FirstLine(constructor);
    }
  }
  return kNoConstructor;
}

// Returns the register of operand `index` of `bytecode`, or -1.
int Register(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return -1;
  }
  auto* reg = dynamic_cast<const lir::VReg*>(bytecode->operands[index]);
  return reg == nullptr ? -1 : static_cast<int>(reg->reg);
}

// Returns true if `bytecode` reads the static field named INSTANCE.
bool ReadsInstance(const lir::Bytecode* bytecode) {
  if (bytecode->opcode != dex::OP_SGET_OBJECT ||
      bytecode->operands.size() < 2) {
    return false;
  }
  auto* field = dynamic_cast<const lir::Field*>(bytecode->operands[1]);
  const ir::FieldDecl* decl = field == nullptr ? nullptr : field->ir_field;
  return decl != nullptr && decl->name != nullptr &&
         strcmp(decl->name->c_str(), kInstanceName) == 0;
}

// Returns true if `bytecode` casts register `reg` to KSerializer.
bool CastsToKSerializer(const lir::Bytecode* bytecode, int reg) {
  if (bytecode->opcode != dex::OP_CHECK_CAST || bytecode->operands.size() < 2 ||
      Register(bytecode, 0) != reg) {
    return false;
  }
  auto* type = dynamic_cast<const lir::Type*>(bytecode->operands[1]);
  const ir::Type* ir_type = type == nullptr ? nullptr : type->ir_type;
  return ir_type != nullptr && ir_type->descriptor != nullptr &&
         strcmp(ir_type->descriptor->c_str(), kKSerializer) == 0;
}

// Returns true if `bytecode` returns register `reg`.
bool ReturnsRegister(const lir::Bytecode* bytecode, int reg) {
  return bytecode->opcode == dex::OP_RETURN_OBJECT &&
         Register(bytecode, 0) == reg;
}

// Returns true if `code` returns the INSTANCE of the generated serializer, as
// serializer() of the companion object of a serializable class does. Its line
// must be the first line of the constructor of the companion object, unless
// that constructor has no line numbers.
bool ReturnsSerializerInstance(const MethodCode& code, int constructor_line) {
  if (constructor_line == kNoConstructor || code.first_line == kNoLine ||
      code.bytecodes.size() < 3 || !ReadsInstance(code.bytecodes[0])) {
    return false;
  }
  int reg = Register(code.bytecodes[0], 0);
  return CastsToKSerializer(code.bytecodes[1], reg) &&
         ReturnsRegister(code.bytecodes[2], reg) &&
         (constructor_line == kNoLine || constructor_line == code.first_line);
}

// Returns true if `code` returns what get$cachedSerializer() of `class_name`
// returns, as serializer() of a serializable object, or of the companion object
// of a serializable sealed, abstract or enum class, does.
bool ReturnsCachedSerializer(const MethodCode& code,
                             const std::string& class_name) {
  if (code.bytecodes.size() < 3) {
    return false;
  }
  const lir::Bytecode* call = code.bytecodes[0];
  if (call->opcode != dex::OP_INVOKE_DIRECT || call->operands.size() < 2) {
    return false;
  }
  auto* method = dynamic_cast<const lir::Method*>(call->operands[1]);
  const ir::MethodDecl* decl = method == nullptr ? nullptr : method->ir_method;
  if (decl == nullptr || decl->parent == nullptr ||
      decl->parent->descriptor == nullptr || decl->name == nullptr ||
      decl->prototype == nullptr ||
      class_name != decl->parent->descriptor->c_str() ||
      strcmp(decl->name->c_str(), kCachedSerializerName) != 0 ||
      decl->prototype->Signature() != kSerializerSignature) {
    return false;
  }
  const lir::Bytecode* result = code.bytecodes[1];
  int reg = Register(result, 0);
  return result->opcode == dex::OP_MOVE_RESULT_OBJECT &&
         ReturnsRegister(code.bytecodes[2], reg);
}

// Returns true if the last of at least two parameters of `ir_method` is the
// marker of the constructor for deserialization.
bool TakesConstructorMarker(const ir::EncodedMethod* ir_method) {
  const ir::TypeList* params = ir_method->decl->prototype->param_types;
  if (params == nullptr || params->types.size() < 2) {
    return false;
  }
  const ir::Type* last = params->types.back();
  return last != nullptr && last->descriptor != nullptr &&
         strcmp(last->descriptor->c_str(), kConstructorMarker) == 0;
}

}  // namespace

void KotlinSerializableFilter::Filter(const FilterContext& context,
                                      ir::EncodedMethod* ir_method,
                                      lir::CodeIr& code_ir,
                                      FilterOutput& output) {
  std::string_view name = ir_method->decl->name->c_str();
  const bool synthetic = (ir_method->access_flags & dex::kAccSynthetic) != 0;
  if (name != kSerializerName && !synthetic) {
    return;
  }
  const std::string signature = ir_method->decl->prototype->Signature();
  bool generated = false;
  if (name == kSerializerName && signature == kSerializerSignature) {
    const MethodCode code = ReadCode(code_ir);
    const int constructor_line =
        CompanionConstructorLine(context, ir_method, code_ir);
    generated = ReturnsSerializerInstance(code, constructor_line) ||
                ReturnsCachedSerializer(code, context.class_name);
  } else if (synthetic) {
    generated = StartsWith(name, kWriteSelfPrefix) ||
                (signature == kSerializerSignature &&
                 (name == kCachedSerializerName ||
                  StartsWith(name, kInitLambdaPrefix) ||
                  StartsWith(name, kChildSerializersLambdaPrefix))) ||
                TakesConstructorMarker(ir_method);
  }
  if (!generated) {
    return;
  }
  lir::Instruction* last = nullptr;
  for (auto* instr : code_ir.instructions) {
    last = instr;
  }
  if (last != nullptr) {
    output.Ignore(*code_ir.instructions.begin(), last);
  }
}

REGISTER_KOTLIN_FILTER(KotlinSerializableFilter)

}  // namespace coverage
