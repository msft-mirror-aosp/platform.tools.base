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

/*
 * Exposes a slicer bytecode transformation (`ArrayParamsEntryHook`) that
 * instruments a method's entry point to package all parameters into an object
 * array (`Object[]`) before invoking a JNI hook. Primitive arguments are boxed
 * automatically (e.g. `int` to `java.lang.Integer`).
 */

#ifndef ARRAY_PARAMS_ENTRY_HOOK_H
#define ARRAY_PARAMS_ENTRY_HOOK_H

#include "slicer/dex_ir.h"
#include "slicer/dex_ir_builder.h"
#include "slicer/instrumentation.h"
#include "tools/base/transport/native/utils/log.h"

namespace art_tooling {

class ArrayParamsEntryHook : public slicer::Transformation {
 public:
  explicit ArrayParamsEntryHook(const ir::MethodId& hook_method_id)
      : hook_method_id_(hook_method_id) {
    // hook method signature is generated automatically
    SLICER_CHECK(hook_method_id_.signature == nullptr);
  }

  virtual bool Apply(lir::CodeIr* code_ir) override;

 private:
  ir::MethodId hook_method_id_;

  bool InjectArrayParamsHook(lir::CodeIr* code_ir, lir::Bytecode* bytecode);
};

}  // namespace art_tooling

#endif  // ARRAY_PARAMS_ENTRY_HOOK_H
