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

#include "tools/base/android-test/coverage/agent/native/synthetic_method_filter.h"

#include <string_view>

#include "slicer/code_ir.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

// The prefix of the methods into which javac compiles the bodies of lambdas.
constexpr std::string_view kLambdaPrefix = "lambda$";
// The prefix of the methods into which Scala compiles the bodies of anonymous
// functions. Dex files don't keep the attributes that mark Scala classes, but
// no other compiler names methods like this.
constexpr std::string_view kScalaAnonymousFunctionPrefix = "$anonfun$";
// The suffix of the companion class to which D8 moves the code of an interface
// when it desugars its default methods.
constexpr std::string_view kCompanionClassSuffix = "$-CC;";

bool StartsWith(std::string_view text, std::string_view prefix) {
  return text.substr(0, prefix.size()) == prefix;
}

bool EndsWith(std::string_view text, std::string_view suffix) {
  return text.size() >= suffix.size() &&
         text.substr(text.size() - suffix.size()) == suffix;
}

}  // namespace

void SyntheticMethodFilter::Filter(const FilterContext& context,
                                   ir::EncodedMethod* ir_method,
                                   lir::CodeIr& code_ir, FilterOutput& output) {
  // The synthetic methods of a companion class can hold code of the source: D8
  // renames the bodies of lambdas that it moves there, and the companion
  // classes of Kotlin interfaces have no kotlin.Metadata.
  if ((ir_method->access_flags & dex::kAccSynthetic) == 0 ||
      EndsWith(context.class_name, kCompanionClassSuffix)) {
    return;
  }
  std::string_view name = ir_method->decl->name->c_str();
  if (StartsWith(name, kLambdaPrefix) ||
      StartsWith(name, kScalaAnonymousFunctionPrefix)) {
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

REGISTER_NON_KOTLIN_FILTER(SyntheticMethodFilter)

}  // namespace coverage
