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

#include "tools/base/android-test/coverage/agent/native/enum_filter.h"

#include <string>
#include <string_view>

#include "slicer/code_ir.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

constexpr char kEnum[] = "Ljava/lang/Enum;";

// Returns true if `method` is values() or valueOf(String) of the enum
// `class_name`, a class descriptor. An enum can't declare other methods with
// their signatures, so their code isn't checked.
bool IsGeneratedMethod(const std::string& class_name,
                       const ir::MethodDecl* method) {
  std::string_view name = method->name->c_str();
  if (name == "values") {
    return method->prototype->Signature() == "()[" + class_name;
  }
  if (name == "valueOf") {
    return method->prototype->Signature() ==
           "(Ljava/lang/String;)" + class_name;
  }
  return false;
}

}  // namespace

void EnumFilter::Filter(const FilterContext& context,
                        ir::EncodedMethod* ir_method, lir::CodeIr& code_ir,
                        FilterOutput& output) {
  if (context.super_class_name != kEnum ||
      !IsGeneratedMethod(context.class_name, ir_method->decl)) {
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

REGISTER_FILTER(EnumFilter)

}  // namespace coverage
