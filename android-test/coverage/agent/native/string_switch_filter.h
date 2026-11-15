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

#ifndef COVERAGE_AGENT_STRING_SWITCH_FILTER_H_
#define COVERAGE_AGENT_STRING_SWITCH_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the code that ECJ generates for a `switch` on a String, and that the
 * Kotlin compiler generates for a `when` on a String with enough branches: a
 * switch on the hash code of the String, whose cases compare the String with
 * the strings that have that hash code. The call to hashCode(), the
 * comparisons and the gotos to the default code are filtered, and the switch
 * gets one branch to each branch of the source and one to the default code.
 * For a nullable subject, the Kotlin compiler checks for null before the call
 * to hashCode(); that check is filtered too, and its target is a branch of the
 * switch. In other classes than Kotlin ones, the code that javac generates,
 * which goes on with a second switch on the index of the string that matches,
 * is left alone. So is a switch whose cases all start on its line, as javac's
 * loads of the index do unless D8 leaves one out.
 */
class StringSwitchFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_STRING_SWITCH_FILTER_H_
