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

#ifndef COVERAGE_AGENT_PRIVATE_EMPTY_NO_ARG_CONSTRUCTOR_FILTER_H_
#define COVERAGE_AGENT_PRIVATE_EMPTY_NO_ARG_CONSTRUCTOR_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the private constructors without parameters that only call the
 * constructor without parameters of the super class, such as those of Kotlin
 * objects and of classes that can't be instantiated.
 */
class PrivateEmptyNoArgConstructorFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_PRIVATE_EMPTY_NO_ARG_CONSTRUCTOR_FILTER_H_
