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

#ifndef COVERAGE_AGENT_ASSERT_FILTER_H_
#define COVERAGE_AGENT_ASSERT_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the code that javac generates for assert statements, and Kotlin for
 * assert() with -Xassertions=jvm, when D8 passes it through. A class with
 * assert statements gets a static field $assertionsDisabled, which its static
 * initializer sets, and each statement first reads it:
 *     const-class v0, LFoo;
 *     invoke-virtual {v0}, Ljava/lang/Class;->desiredAssertionStatus()Z
 *     move-result v0
 *     xor-int/lit8 v0, v0, 1  (or xor-int/2addr with a register that holds 1)
 *     sput-boolean v0, LFoo;->$assertionsDisabled:Z
 *   ...
 *     sget-boolean v0, LFoo;->$assertionsDisabled:Z
 *     if-nez v0, :after_assert
 * The filter ignores the code that sets the field, and the branch on the field
 * of each statement. D8 can branch either way, and keep the value, or the
 * status that the static initializer negates for it, in a register for later
 * statements. Builds of debuggable apps enable assertions at compile time and
 * others remove them, so D8 only leaves this code when it is told to pass
 * assertions through.
 */
class AssertFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_ASSERT_FILTER_H_
