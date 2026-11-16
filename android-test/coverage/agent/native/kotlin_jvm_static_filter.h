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

#ifndef COVERAGE_AGENT_KOTLIN_JVM_STATIC_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_JVM_STATIC_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the static methods that the Kotlin compiler generates in a class for
 * the functions of its companion object annotated with @JvmStatic. Each one
 * loads the companion object from the field Companion of the class and calls
 * the function. KotlinGeneratedFilter ignores them when they have no line
 * numbers, but Kotlin 2.0 and later give them one: the last line of the
 * declaration, on their return.
 *
 * As in the build-time report, a method is filtered only if it has no
 * parameters and the load comes first, before any line. The build-time report
 * keeps the methods with parameters, because in the class files the local
 * variables of the parameters start before the load. Named companion objects
 * are in other fields, so their methods are kept too.
 */
class KotlinJvmStaticFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_JVM_STATIC_FILTER_H_
