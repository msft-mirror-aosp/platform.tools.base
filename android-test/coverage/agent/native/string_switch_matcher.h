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

#ifndef COVERAGE_AGENT_STRING_SWITCH_MATCHER_H_
#define COVERAGE_AGENT_STRING_SWITCH_MATCHER_H_

#include <optional>
#include <vector>

#include "slicer/code_ir.h"

namespace coverage {

// The code that compilers generate for a switch on a String, as D8 lays it
// out: a switch on the hash code of the String, whose cases compare the String
// with each string of the source that has that hash code.
//     invoke-virtual {vS}, Ljava/lang/String;->hashCode()I
//     move-result vH
//     packed-switch vH, :hashes            (or sparse-switch)
//     goto :default                        (or the default code)
//   :hash
//     const-string vT, "<string>"
//     invoke-virtual {vS, vT}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
//     move-result vR
//     if-eqz vR, :default                  (or the next comparison)
//     <the code of the case>
// A comparison can also jump to the code of its case with an if-nez, and go
// on with the next comparison, the default code or a goto to it. Jumps to the
// default code can go through the goto after the switch, as can the values
// between the hash codes of a packed-switch. D8 can load the string earlier,
// and load constants or copy registers before the switch. For a nullable
// subject, the Kotlin compiler checks the String for null first:
//     if-eqz vS, :null                     (or the default code)
//     invoke-virtual {vS}, Ljava/lang/String;->hashCode()I
struct StringSwitch {
  // The instructions from `from` to `to`, both inclusive, in code order.
  struct Range {
    lir::Bytecode* from;
    lir::Bytecode* to;
  };

  // The register of the String.
  int subject = -1;
  // The call to hashCode() and its move-result.
  Range hash_code = {nullptr, nullptr};
  // The switch on the hash code.
  lir::Bytecode* switch_bytecode = nullptr;
  // The goto after the switch to the default code, or nullptr if the default
  // code follows the switch.
  lir::Bytecode* default_goto = nullptr;
  // The first bytecode of the default code, which runs when no string matches.
  lir::Bytecode* default_code = nullptr;
  // The comparisons, each up to its if-eqz or if-nez.
  std::vector<Range> comparisons;
  // The gotos to the default code after the comparisons.
  std::vector<lir::Bytecode*> gotos;
  // The first bytecode of the code of each case, without duplicates, in the
  // order of the comparisons.
  std::vector<lir::Bytecode*> cases;
  // Whether other code goes to the default code too, as the branches of a
  // `when` statement without `else` do when they meet after it. Jumps and
  // falls through the comparisons, the switch and `null_check` don't count.
  bool default_code_shared = false;
  // The if-eqz that checks the String for null right before the call to
  // hashCode(), on the same line, or nullptr. Code that isn't Kotlin's check
  // of a nullable subject can look the same.
  lir::Bytecode* null_check = nullptr;
  // The first bytecode of the code that `null_check` goes to, or nullptr if
  // there is no check or if it goes to the default code.
  lir::Bytecode* null_code = nullptr;
};

// Matches the switch on a String whose hash code `hash_code` computes.
// Returns nothing if `hash_code` doesn't start one.
std::optional<StringSwitch> MatchStringSwitch(lir::Bytecode* hash_code);

// What javac generates on top of the code above: each case loads the index of
// its string into vC and goes to a second switch on vC, which jumps to the
// code of the source, and the default code loads -1. The loads are on the
// line of the first switch.
//   :case
//     const/4 vC, #0                       (or a move from a register)
//     goto :second
//   :default
//     const/4 vC, #-1                      (or a move from a register)
//   :second
//     packed-switch vC, :indices           (or sparse-switch)
// D8 can hoist constants for the code after the second switch right before
// it, and leave out a load when vC already holds the index, such as -1 loaded
// before the first switch: that case or the default code then goes on with
// the second switch directly.
struct JavacStringSwitch {
  // The load of each case and of the default code, each with its goto to the
  // second switch unless it is right before it, without the left out ones.
  std::vector<StringSwitch::Range> loads;
  // The second switch.
  lir::Bytecode* second_switch = nullptr;
};

// Returns true if the code of each case of `string_switch` starts on the line
// of its switch, as the loads of the indices that javac adds do, and the code
// of the cases of ECJ usually doesn't.
bool CasesStartOnLineOfSwitch(const StringSwitch& string_switch);

// Matches the code that javac adds to `string_switch`. Returns nothing if the
// switch goes on with other code.
std::optional<JavacStringSwitch> MatchJavacStringSwitch(
    const StringSwitch& string_switch);

}  // namespace coverage

#endif  // COVERAGE_AGENT_STRING_SWITCH_MATCHER_H_
