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

#ifndef COVERAGE_AGENT_KOTLIN_SERIALIZABLE_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_SERIALIZABLE_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the methods that the Kotlin serialization compiler plugin generates:
 * the serializer() that returns the serializer of a class, from its companion
 * object, or of an object, and synthetic methods such as write$Self$<module>,
 * the constructor for deserialization and the lambdas that create serializers.
 */
class KotlinSerializableFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_SERIALIZABLE_FILTER_H_
