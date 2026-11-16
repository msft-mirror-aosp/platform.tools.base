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

#include "tools/base/android-test/coverage/agent/native/kotlin_jvm_static_filter.h"

#include <string>
#include <string_view>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr std::string_view kJvmStatic = "Lkotlin/jvm/JvmStatic;";
constexpr std::string_view kCompanion = "Companion";

// Returns true if `ir_method` takes parameters.
bool HasParameters(const ir::EncodedMethod* ir_method) {
  const ir::TypeList* parameters = ir_method->decl->prototype->param_types;
  return parameters != nullptr && !parameters->types.empty();
}

// Returns true if `instr` is the annotation of a line number, including the
// annotation of the start line of the debug information, which slicer adds
// first and which isn't a line of the method.
bool IsLine(const lir::Instruction* instr) {
  auto* annotation = dynamic_cast<const lir::DbgInfoAnnotation*>(instr);
  return annotation != nullptr &&
         annotation->dbg_opcode == dex::DBG_ADVANCE_LINE;
}

// Returns the first bytecode of `code_ir` if no line of the method comes
// before it, or nullptr.
const lir::Bytecode* FirstBytecodeBeforeAnyLine(const lir::CodeIr& code_ir) {
  int lines = 0;
  for (auto* instr : code_ir.instructions) {
    if (IsLine(instr) && ++lines > 1) {
      return nullptr;
    }
    if (auto* bytecode = dynamic_cast<const lir::Bytecode*>(instr)) {
      return bytecode;
    }
  }
  return nullptr;
}

// Returns true if `bytecode` loads the companion object of the class
// `class_name`, a descriptor, from its field Companion.
bool LoadsCompanion(const lir::Bytecode* bytecode,
                    std::string_view class_name) {
  if (bytecode == nullptr || bytecode->opcode != dex::OP_SGET_OBJECT) {
    return false;
  }
  for (auto* operand : bytecode->operands) {
    if (auto* field = dynamic_cast<const lir::Field*>(operand)) {
      const ir::FieldDecl* decl = field->ir_field;
      return decl != nullptr && decl->name != nullptr &&
             decl->parent != nullptr && decl->parent->descriptor != nullptr &&
             std::string_view(decl->name->c_str()) == kCompanion &&
             std::string_view(decl->parent->descriptor->c_str()) == class_name;
    }
  }
  return false;
}

// Returns true if `ir_method` has an annotation of the type `descriptor` that
// is visible at runtime.
bool HasRuntimeAnnotation(const ir::EncodedMethod* ir_method,
                          std::string_view descriptor) {
  const ir::Class* ir_class = ir_method->decl->parent->class_def;
  if (ir_class == nullptr || ir_class->annotations == nullptr) {
    return false;
  }
  for (const ir::MethodAnnotation* method_annotation :
       ir_class->annotations->method_annotations) {
    if (method_annotation->method_decl != ir_method->decl ||
        method_annotation->annotations == nullptr) {
      continue;
    }
    for (const ir::Annotation* annotation :
         method_annotation->annotations->annotations) {
      if (annotation->visibility == dex::kVisibilityRuntime &&
          annotation->type != nullptr &&
          annotation->type->descriptor != nullptr &&
          std::string_view(annotation->type->descriptor->c_str()) ==
              descriptor) {
        return true;
      }
    }
  }
  return false;
}

}  // namespace

void KotlinJvmStaticFilter::Filter(const FilterContext& context,
                                   ir::EncodedMethod* ir_method,
                                   lir::CodeIr& code_ir, FilterOutput& output) {
  // The annotations are checked last: they are the slowest to look up.
  if ((ir_method->access_flags & dex::kAccStatic) == 0 ||
      HasParameters(ir_method) ||
      !LoadsCompanion(FirstBytecodeBeforeAnyLine(code_ir),
                      context.class_name) ||
      !HasRuntimeAnnotation(ir_method, kJvmStatic)) {
    return;
  }
  lir::Instruction* last = nullptr;
  for (auto* instr : code_ir.instructions) {
    last = instr;
  }
  output.Ignore(*code_ir.instructions.begin(), last);
}

REGISTER_KOTLIN_FILTER(KotlinJvmStaticFilter)

}  // namespace coverage
