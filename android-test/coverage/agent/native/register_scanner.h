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

#ifndef COVERAGE_REGISTER_SCANNER_H_
#define COVERAGE_REGISTER_SCANNER_H_

#include <jni.h>
#include <jvmti.h>
#include <memory>
#include "slicer/code_ir.h"
#include "slicer/dex_ir.h"

namespace coverage {

class RegisterScanner {
 public:
  // Scans the method for any completely unused register index < 16.
  // Returns the scratch register index and sets found_unused to true if
  // successful.
  static dex::u4 FindUnusedScratchRegister(ir::EncodedMethod* ir_method,
                                           lir::CodeIr& code_ir,
                                           bool& found_unused);
};

}  // namespace coverage

#endif  // COVERAGE_REGISTER_SCANNER_H_
