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

#ifndef COVERAGE_AGENT_KOTLIN_INLINE_FILTER_H_
#define COVERAGE_AGENT_KOTLIN_INLINE_FILTER_H_

#include <cstdint>
#include <optional>
#include <string_view>

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the code that the Kotlin compiler inlines from inline functions. The
 * compiler puts the inlined code on line numbers after the end of the source
 * file, and maps them back to the inline functions in the SMAP of the class.
 */
class KotlinInlineFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;

  // Returns the first line number of the inlined code of the class
  // `class_name` (e.g. "com/example/FooKt"), compiled from `source_file`,
  // according to its SMAP: the smallest line at which a mapping starts, other
  // than the mapping of the class's own lines. Returns nothing if `smap` is
  // empty or malformed, or if the class has no inlined code.
  static std::optional<int32_t> FirstInlinedLine(std::string_view class_name,
                                                 std::string_view source_file,
                                                 std::string_view smap);
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_KOTLIN_INLINE_FILTER_H_
