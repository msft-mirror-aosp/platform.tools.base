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

#ifndef COVERAGE_AGENT_STRING_SWITCH_JAVAC_FILTER_H_
#define COVERAGE_AGENT_STRING_SWITCH_JAVAC_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the code that javac generates for a `switch` on a String: a switch
 * on the hash code of the String, whose cases compare the String with the
 * strings that have that hash code and load the index of the string that
 * matches, or -1, followed by a second switch on that index, which goes to
 * the code of the source. The first switch, the comparisons, the loads of the
 * index and the gotos between them are filtered. The second switch keeps its
 * branches, and the call to hashCode() and the constants that D8 loads before
 * the first switch stay, since they run whenever the switch does. The filter
 * only runs on the classes that javac compiles, not on Kotlin ones.
 */
class StringSwitchJavacFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_STRING_SWITCH_JAVAC_FILTER_H_
