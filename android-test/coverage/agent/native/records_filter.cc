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

#include "tools/base/android-test/coverage/agent/native/records_filter.h"

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

constexpr char kRecord[] = "Ljava/lang/Record;";
constexpr char kRecordTag[] = "Lcom/android/tools/r8/RecordTag;";
constexpr char kObject[] = "Ljava/lang/Object;";
constexpr char kToStringSignature[] = "()Ljava/lang/String;";
constexpr char kHashCodeSignature[] = "()I";
constexpr char kEqualsSignature[] = "(Ljava/lang/Object;)Z";
// The signature of the method that D8 generates for toString(), which takes
// the values of the fields, the class of the record and the names of the
// fields.
constexpr char kToStringHelperSignature[] =
    "([Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)"
    "Ljava/lang/String;";
// Returns true if `name` is the name that D8 gives the method it generates for
// hashCode(): m, or m$<n> when it merges that method into a class with another
// method of the same name and signature, as it does in release mode.
bool IsHashCodeHelperName(const char* name) {
  if (strcmp(name, "m") == 0) {
    return true;
  }
  if (strncmp(name, "m$", 2) != 0 || name[2] == '\0') {
    return false;
  }
  for (const char* c = name + 2; *c != '\0'; ++c) {
    if (*c < '0' || *c > '9') {
      return false;
    }
  }
  return true;
}

// Returns true if `opcode` is the move with which the agent copies an object
// parameter back to its register when it grows the frame of a method. The
// parameters of the methods that this filter matches are all objects: `this`,
// and the other object of equals(Object).
bool IsAgentCopy(dex::Opcode opcode) {
  return opcode == dex::OP_MOVE_OBJECT_16;
}

// Returns true if `opcode` reads an instance field: iget, iget-wide,
// iget-object and so on.
bool IsFieldRead(dex::Opcode opcode) {
  return opcode >= dex::OP_IGET && opcode <= dex::OP_IGET_SHORT;
}

// Returns the register of operand `index` of `bytecode`, or -1.
int Register(const lir::Bytecode* bytecode, size_t index) {
  if (index >= bytecode->operands.size()) {
    return -1;
  }
  auto* reg = dynamic_cast<const lir::VReg*>(bytecode->operands[index]);
  return reg == nullptr ? -1 : static_cast<int>(reg->reg);
}

// Returns the register of operand `index` of `bytecode`, or the first one if
// it is a pair of registers, or -1.
int FirstRegister(const lir::Bytecode* bytecode, size_t index) {
  if (index < bytecode->operands.size()) {
    if (auto* pair =
            dynamic_cast<const lir::VRegPair*>(bytecode->operands[index])) {
      return static_cast<int>(pair->base_reg);
    }
  }
  return Register(bytecode, index);
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

// Returns the method that `bytecode` invokes, or nullptr.
const ir::MethodDecl* InvokedMethod(const lir::Bytecode* bytecode) {
  for (auto* operand : bytecode->operands) {
    if (auto* method = dynamic_cast<const lir::Method*>(operand)) {
      return method->ir_method;
    }
  }
  return nullptr;
}

// Returns the field that `bytecode` reads or writes, or nullptr.
const ir::FieldDecl* AccessedField(const lir::Bytecode* bytecode) {
  for (auto* operand : bytecode->operands) {
    if (auto* field = dynamic_cast<const lir::Field*>(operand)) {
      return field->ir_field;
    }
  }
  return nullptr;
}

// Returns true if `method` has the signature `signature`.
bool HasSignature(const ir::MethodDecl* method, const std::string& signature) {
  return method != nullptr && method->prototype != nullptr &&
         method->prototype->Signature() == signature;
}

// Returns true if `method` is the method `name` with the signature
// `signature`.
bool IsMethod(const ir::MethodDecl* method, const char* name,
              const std::string& signature) {
  return HasSignature(method, signature) && method->name != nullptr &&
         strcmp(method->name->c_str(), name) == 0;
}

// Returns true if the class `owner`, a descriptor, declares `method`.
bool IsDeclaredIn(const ir::MethodDecl* method, const std::string& owner) {
  return method->parent != nullptr && method->parent->descriptor != nullptr &&
         owner == method->parent->descriptor->c_str();
}

// Returns true if operand `index` of `bytecode` is the type `descriptor`.
bool IsTypeOperand(const lir::Bytecode* bytecode, size_t index,
                   const std::string& descriptor) {
  if (index >= bytecode->operands.size()) {
    return false;
  }
  auto* type = dynamic_cast<const lir::Type*>(bytecode->operands[index]);
  return type != nullptr && type->ir_type != nullptr &&
         type->ir_type->descriptor != nullptr &&
         descriptor == type->ir_type->descriptor->c_str();
}

// Returns true if `bytecode` returns the value in register `reg` with
// `opcode`.
bool Returns(const lir::Bytecode* bytecode, dex::Opcode opcode, int reg) {
  return bytecode->opcode == opcode && Register(bytecode, 0) == reg;
}

// The code of a method of a record, after the copies with which the agent
// starts it when it grows its frame.
struct MethodCode {
  std::vector<const lir::Bytecode*> bytecodes;
  // The register from which the code reads `this`.
  int self = -1;
};

// Reads the code of `ir_method`. `this` is its first parameter, which arrives
// in the first of the last ins_count registers of the frame. When the agent
// grows the frame, it first copies each parameter back to the register before
// it, where the code reads it, starting with `this`.
MethodCode ReadCode(const ir::EncodedMethod* ir_method,
                    const lir::CodeIr& code_ir) {
  MethodCode code;
  code.self = ir_method->code->registers - ir_method->code->ins_count;
  bool copied = false;
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<const lir::Bytecode*>(instr);
    if (bytecode == nullptr) {
      continue;
    }
    if (code.bytecodes.empty() && IsAgentCopy(bytecode->opcode)) {
      if (!copied) {
        code.self = Register(bytecode, 0);
        copied = true;
      }
      continue;
    }
    code.bytecodes.push_back(bytecode);
  }
  return code;
}

// Matches toString() of the record `record`:
//     invoke-direct {this}, <record>->$record$getFieldsAsObjects()
//     move-result-object vA
//     const-class vB, <record>
//     const-string vC, "<names of the fields>"    (or const-string/jumbo)
//     invoke-static {vA, vB, vC}, <helper>(...)Ljava/lang/String;
//     move-result-object vD
//     return-object vD
// Whatever the record, D8 only uses registers v0 to v3 for this code, so the
// invokes never need their /range forms.
bool IsToString(const MethodCode& code, const std::string& record) {
  const std::vector<const lir::Bytecode*>& b = code.bytecodes;
  if (b.size() != 7) {
    return false;
  }
  const ir::MethodDecl* values = InvokedMethod(b[0]);
  return b[0]->opcode == dex::OP_INVOKE_DIRECT &&
         IsMethod(values, "$record$getFieldsAsObjects",
                  "()[Ljava/lang/Object;") &&
         IsDeclaredIn(values, record) &&
         Arguments(b[0]) == std::vector<int>{code.self} &&
         b[1]->opcode == dex::OP_MOVE_RESULT_OBJECT &&
         b[2]->opcode == dex::OP_CONST_CLASS &&
         IsTypeOperand(b[2], 1, record) &&
         (b[3]->opcode == dex::OP_CONST_STRING ||
          b[3]->opcode == dex::OP_CONST_STRING_JUMBO) &&
         b[4]->opcode == dex::OP_INVOKE_STATIC &&
         HasSignature(InvokedMethod(b[4]), kToStringHelperSignature) &&
         Arguments(b[4]) == std::vector<int>{Register(b[1], 0),
                                             Register(b[2], 0),
                                             Register(b[3], 0)} &&
         b[5]->opcode == dex::OP_MOVE_RESULT_OBJECT &&
         Returns(b[6], dex::OP_RETURN_OBJECT, Register(b[5], 0));
}

// Matches equals(Object) of the record `record`, with the other object in the
// register after `this`:
//     invoke-direct {this, vO}, <record>->$record$equals(Ljava/lang/Object;)Z
//     move-result vR
//     return vR
// D8 only uses registers v0 to v2 for this code, so the invoke never needs its
// /range form.
bool IsEquals(const MethodCode& code, const std::string& record) {
  const std::vector<const lir::Bytecode*>& b = code.bytecodes;
  if (b.size() != 3) {
    return false;
  }
  const ir::MethodDecl* equals = InvokedMethod(b[0]);
  return b[0]->opcode == dex::OP_INVOKE_DIRECT &&
         IsMethod(equals, "$record$equals", kEqualsSignature) &&
         IsDeclaredIn(equals, record) &&
         Arguments(b[0]) == std::vector<int>{code.self, code.self + 1} &&
         b[1]->opcode == dex::OP_MOVE_RESULT &&
         Returns(b[2], dex::OP_RETURN, Register(b[1], 0));
}

// Returns true if `bytecode` loads 0 with const/4.
bool LoadsZero(const lir::Bytecode* bytecode) {
  if (bytecode->opcode != dex::OP_CONST_4 || bytecode->operands.size() < 2) {
    return false;
  }
  auto* value = dynamic_cast<const lir::Const32*>(bytecode->operands[1]);
  return value != nullptr && value->u.u4_value == 0;
}

// Returns the type of the parameter through which the method that D8
// generates for hashCode() takes a field of type `field`: it takes references
// as Object, and byte, char and short as int.
std::string HelperParameter(const char* field) {
  switch (field[0]) {
    case 'L':
    case '[':
      return kObject;
    case 'B':
    case 'C':
    case 'S':
      return "I";
    default:
      return field;
  }
}

// Returns the field that `bytecode` reads from the object in register `self`,
// or nullptr, and appends the registers of the value to `registers`.
const ir::FieldDecl* FieldRead(const lir::Bytecode* bytecode, int self,
                               std::vector<int>* registers) {
  if (!IsFieldRead(bytecode->opcode) || Register(bytecode, 1) != self) {
    return nullptr;
  }
  const ir::FieldDecl* field = AccessedField(bytecode);
  if (field == nullptr || field->type == nullptr ||
      field->type->descriptor == nullptr) {
    return nullptr;
  }
  const int value = FirstRegister(bytecode, 0);
  registers->push_back(value);
  if (bytecode->opcode == dex::OP_IGET_WIDE) {
    registers->push_back(value + 1);
  }
  return field;
}

// Matches hashCode() of the record `record`, whose class is `record_class`,
// or nullptr if unknown. D8 reads the fields, the primitive ones first, and
// passes them to a static method m (or m$<n>) that it generates in another
// class, which other records can share:
//     iget vA, this, <record>-><field>    (iget-wide, iget-object..., one for
//     ...                                  each field)
//     invoke-static {vA, ...}, <helper>->m(<parameters>)I    (or /range)
//     move-result vR
//     return vR
// When the values take 16 registers or more, D8 also moves registers around,
// which isn't matched. In a record without fields, the code is:
//     const/4 vR, #0
//     return vR
bool IsHashCode(const MethodCode& code, const std::string& record,
                const ir::Class* record_class) {
  const std::vector<const lir::Bytecode*>& b = code.bytecodes;
  if (b.size() == 2) {
    return record_class != nullptr && record_class->instance_fields.empty() &&
           LoadsZero(b[0]) && Returns(b[1], dex::OP_RETURN, Register(b[0], 0));
  }
  if (b.size() < 4) {
    return false;
  }
  const size_t reads = b.size() - 3;
  std::vector<int> registers;
  std::string signature = "(";
  for (size_t i = 0; i < reads; ++i) {
    const ir::FieldDecl* field = FieldRead(b[i], code.self, &registers);
    if (field == nullptr) {
      return false;
    }
    signature += HelperParameter(field->type->descriptor->c_str());
  }
  signature += ")I";
  const lir::Bytecode* call = b[reads];
  const ir::MethodDecl* helper = InvokedMethod(call);
  return (call->opcode == dex::OP_INVOKE_STATIC ||
          call->opcode == dex::OP_INVOKE_STATIC_RANGE) &&
         HasSignature(helper, signature) && helper->name != nullptr &&
         IsHashCodeHelperName(helper->name->c_str()) &&
         !IsDeclaredIn(helper, record) && Arguments(call) == registers &&
         b[reads + 1]->opcode == dex::OP_MOVE_RESULT &&
         Returns(b[reads + 2], dex::OP_RETURN, Register(b[reads + 1], 0));
}

// Matches an accessor `name`, which returns the field `name`:
//     iget vA, this, <record>-><name>    (iget-wide, iget-object...)
//     return vA                          (return-wide, return-object)
// Without parameters, the only object from which the field can be read is
// `this`.
bool IsFieldAccessor(const MethodCode& code, std::string_view name) {
  const std::vector<const lir::Bytecode*>& b = code.bytecodes;
  if (b.size() != 2 || !IsFieldRead(b[0]->opcode) ||
      (b[1]->opcode != dex::OP_RETURN && b[1]->opcode != dex::OP_RETURN_WIDE &&
       b[1]->opcode != dex::OP_RETURN_OBJECT) ||
      FirstRegister(b[1], 0) != FirstRegister(b[0], 0)) {
    return false;
  }
  const ir::FieldDecl* field = AccessedField(b[0]);
  return field != nullptr && field->name != nullptr &&
         name == field->name->c_str();
}

}  // namespace

void RecordsFilter::Filter(const FilterContext& context,
                           ir::EncodedMethod* ir_method, lir::CodeIr& code_ir,
                           FilterOutput& output) {
  if (context.super_class_name != kRecord &&
      context.super_class_name != kRecordTag) {
    return;
  }
  const MethodCode code = ReadCode(ir_method, code_ir);
  const std::string& record = context.class_name;
  std::string_view name = ir_method->decl->name->c_str();
  const std::string signature = ir_method->decl->prototype->Signature();
  bool generated = false;
  if (name == "toString") {
    generated = signature == kToStringSignature && IsToString(code, record);
  } else if (name == "hashCode") {
    // javac generates hashCode() as final, unlike the ones written in the
    // source, which can call methods that D8 generates too.
    generated = signature == kHashCodeSignature &&
                (ir_method->access_flags & dex::kAccFinal) != 0 &&
                IsHashCode(code, record, ir_method->decl->parent->class_def);
  } else if (name == "equals") {
    generated = signature == kEqualsSignature && IsEquals(code, record);
  } else {
    // An accessor has no parameters.
    generated = signature.rfind("()", 0) == 0 && IsFieldAccessor(code, name);
  }
  if (!generated) {
    return;
  }
  lir::Instruction* last = nullptr;
  for (auto* instr : code_ir.instructions) {
    last = instr;
  }
  output.Ignore(*code_ir.instructions.begin(), last);
}

REGISTER_FILTER(RecordsFilter)

}  // namespace coverage
