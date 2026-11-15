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

#include "tools/base/android-test/coverage/agent/native/string_switch_filter.h"

#include <gtest/gtest.h>

#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

namespace coverage {
namespace {

constexpr char kString[] = "Ljava/lang/String;";
constexpr char kEquals[] = "(Ljava/lang/Object;)Z";
// The signature of fun f(s: String): Int.
constexpr char kSignature[] = "(Ljava/lang/String;)I";
// The hash code of "a", which "b" follows. "Aa" and "BB" both have kHashOfAa.
constexpr dex::s4 kHashOfA = 97;
constexpr dex::s4 kHashOfAa = 2112;

// Unit tests for StringSwitchFilter, on the code that the Kotlin compiler and
// D8 generate for a `when` on the String in v1, such as
//   fun f(s: String): Int = when (s) {
//       "a" -> 1
//       "b" -> 2
//       else -> 3
//   }
// The Kotlin compiler only uses a switch for a `when` with more branches, but
// two are enough for the filter.
class StringSwitchFilterTest : public FilterTestBase {
 protected:
  StringSwitchFilterTest() {
    context_.class_annotations = {kKotlinMetadataDescriptor};
  }

  // Appends the call to hashCode() on the String in v1, with its result in v0.
  static Range AddHashCode(TestMethod& m) {
    lir::Instruction* call =
        m.Add(dex::OP_INVOKE_VIRTUAL,
              {m.Regs({1}), m.MethodRef(kString, "hashCode", "()I")});
    return {call, m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)})};
  }

  // Appends the comparison of the String in v1 with `value`, which jumps to
  // `target` with `jump`, an if-eqz or an if-nez.
  static Range AddComparison(TestMethod& m, const char* value, dex::Opcode jump,
                             lir::Label* target) {
    lir::Instruction* load =
        m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef(value)});
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({1, 0}), m.MethodRef(kString, "equals", kEquals)});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    return {load, m.Add(jump, {m.Reg(0), m.Target(target)})};
  }

  // Appends `return <value>`, with the value in v0, and returns the const.
  static lir::Instruction* AddReturn(TestMethod& m, dex::u4 value) {
    lir::Instruction* load =
        m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(value)});
    m.Add(dex::OP_RETURN, {m.Reg(0)});
    return load;
  }

  void Filter(TestMethod& m) {
    StringSwitchFilter().Filter(context_, m.ir_method(), m.code_ir(), output_);
  }
};

// The code that the Kotlin compiler and D8 generate.

TEST_F(StringSwitchFilterTest, ShouldFilterComparisonsOfWhen) {
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  m.AddLine(3);  // The start line.
  m.AddLine(3);
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_EQZ, otherwise);
  m.AddLine(5);
  lir::Instruction* case_b = AddReturn(m, 2);
  m.Place(hash_of_a);
  m.AddLine(3);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  m.AddLine(4);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(otherwise);
  m.AddLine(6);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});

  Filter(m);

  ExpectIgnored(m, {hash_code, {jump, jump}, compare_a, compare_b});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a, case_b}}});
}

TEST_F(StringSwitchFilterTest,
       ShouldFilterComparisonsOfStringsWithSameHashCode) {
  // "Aa" jumps to its code with an if-nez, and "BB" goes on with its code.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* same_hash = m.NewLabel();
  lir::Label* code_of_aa = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_SPARSE_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(same_hash);
  Range compare_aa = AddComparison(m, "Aa", dex::OP_IF_NEZ, code_of_aa);
  Range compare_bb = AddComparison(m, "BB", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_bb = AddReturn(m, 2);
  m.Place(code_of_aa);
  lir::Instruction* case_aa = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddSparseSwitchPayload({{kHashOfAa, same_hash}});

  Filter(m);

  ExpectIgnored(m, {hash_code, {jump, jump}, compare_aa, compare_bb});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_aa, case_bb}}});
}

TEST_F(StringSwitchFilterTest,
       ShouldFilterComparisonThatJumpsToNextComparison) {
  // The comparison with "BB" goes on with its code, and jumps to the
  // comparison with "Aa" when it fails.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* same_hash = m.NewLabel();
  lir::Label* next = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_SPARSE_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(same_hash);
  Range compare_bb = AddComparison(m, "BB", dex::OP_IF_EQZ, next);
  lir::Instruction* case_bb = AddReturn(m, 2);
  m.Place(next);
  Range compare_aa = AddComparison(m, "Aa", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_aa = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddSparseSwitchPayload({{kHashOfAa, same_hash}});

  Filter(m);

  ExpectIgnored(m, {hash_code, {jump, jump}, compare_bb, compare_aa});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_bb, case_aa}}});
}

TEST_F(StringSwitchFilterTest, ShouldFilterGotoToDefaultCodeAfterComparison) {
  // The comparison jumps to its code with an if-nez, followed by a goto to the
  // default code.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_NEZ, code_of_a);
  lir::Instruction* to_default = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(code_of_a);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  ExpectIgnored(m,
                {hash_code, {jump, jump}, compare_a, {to_default, to_default}});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a}}});
}

TEST_F(StringSwitchFilterTest, ShouldFilterComparisonFollowedByDefaultCode) {
  // The comparison jumps to its code with an if-nez, and the default code
  // follows it.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_NEZ, code_of_a);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(code_of_a);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  ExpectIgnored(m, {hash_code, {jump, jump}, compare_a});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a}}});
}

TEST_F(StringSwitchFilterTest, ShouldFilterJumpsThroughGotoAfterSwitch) {
  // The comparisons jump to the goto after the switch, which goes to the
  // default code.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* after_switch = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Place(after_switch);
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_NEZ, code_of_a);
  lir::Instruction* to_default = m.Add(dex::OP_GOTO, {m.Target(after_switch)});
  m.Place(code_of_a);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_EQZ, after_switch);
  lir::Instruction* case_b = AddReturn(m, 2);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});

  Filter(m);

  ExpectIgnored(m, {hash_code,
                    {jump, jump},
                    compare_a,
                    compare_b,
                    {to_default, to_default}});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a, case_b}}});
}

TEST_F(StringSwitchFilterTest, ShouldFilterPackedSwitchWithHoles) {
  // The hash codes of "a" and "c" aren't next to each other, and the
  // packed-switch goes to the default code for the hash code in between, at
  // the goto after the switch.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_c = m.NewLabel();
  lir::Label* after_switch = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Place(after_switch);
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(hash_of_c);
  Range compare_c = AddComparison(m, "c", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_c = AddReturn(m, 2);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, after_switch, hash_of_c});

  Filter(m);

  ExpectIgnored(m, {hash_code, {jump, jump}, compare_a, compare_c});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a, case_c}}});
}

TEST_F(StringSwitchFilterTest, ShouldFilterComparisonsWhenDefaultCodeFollows) {
  // The default code follows the switch, and the comparisons follow it.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  ExpectIgnored(m, {hash_code, compare_a});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a}}});
}

TEST_F(StringSwitchFilterTest, ShouldFilterComparisonWithStringLoadedEarlier) {
  // D8 loads "a" into v2 before the switch, because the method uses it twice.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  m.Add(dex::OP_CONST_STRING, {m.Reg(2), m.StringRef("a")});
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  lir::Instruction* call =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({1, 2}), m.MethodRef(kString, "equals", kEquals)});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  lir::Instruction* check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(otherwise)});
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  // The load of the string before the switch isn't ignored, like the
  // constants of the cases.
  ExpectIgnored(m, {hash_code, {jump, jump}, {call, check}});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a}}});
}

TEST_F(StringSwitchFilterTest, ShouldFilterSwitchWithClassLoadedBeforeIt) {
  // D8 loads a class that the cases use into v2 before the switch.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  m.Add(dex::OP_CONST_CLASS, {m.Reg(2), m.TypeRef("LBar;")});
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  ExpectIgnored(m, {hash_code, {jump, jump}, compare_a});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a}}});
}

TEST_F(StringSwitchFilterTest, ShouldFilterComparisonsOfStringInHighRegister) {
  // With the String in v16, D8 calls hashCode() and equals() with
  // invoke-virtual/range.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Instruction* call =
      m.Add(dex::OP_INVOKE_VIRTUAL_RANGE,
            {m.RegRange(16, 1), m.MethodRef(kString, "hashCode", "()I")});
  lir::Instruction* result = m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  lir::Instruction* load =
      m.Add(dex::OP_CONST_STRING, {m.Reg(17), m.StringRef("a")});
  m.Add(dex::OP_INVOKE_VIRTUAL_RANGE,
        {m.RegRange(16, 2), m.MethodRef(kString, "equals", kEquals)});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  lir::Instruction* check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(otherwise)});
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  ExpectIgnored(m, {{call, result}, {jump, jump}, {load, check}});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a}}});
}

TEST_F(StringSwitchFilterTest, ShouldNotDuplicateBranchOfSeveralStrings) {
  // when (s) { "a", "b" -> 1; else -> 3 }: both comparisons jump to the same
  // code, which is one branch of the switch.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* shared = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_NEZ, shared);
  lir::Instruction* goto_a = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_NEZ, shared);
  lir::Instruction* goto_b = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(shared);
  lir::Instruction* case_ab = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});

  Filter(m);

  ExpectIgnored(m, {hash_code,
                    {jump, jump},
                    compare_a,
                    compare_b,
                    {goto_a, goto_a},
                    {goto_b, goto_b}});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_ab}}});
}

TEST_F(StringSwitchFilterTest, ShouldFilterEachWhenOfMethod) {
  // Two `when`s, the second after the first.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Label* hashes_2 = m.NewLabel();
  lir::Label* hash_of_a_2 = m.NewLabel();
  lir::Label* otherwise_2 = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_a = m.Add(
      dex::OP_INVOKE_STATIC, {m.Regs({}), m.MethodRef("LFoo;", "a", "()V")});
  m.Place(otherwise);
  Range hash_code_2 = AddHashCode(m);
  lir::Instruction* switch_bytecode_2 =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes_2)});
  lir::Instruction* jump_2 = m.Add(dex::OP_GOTO, {m.Target(otherwise_2)});
  m.Place(hash_of_a_2);
  Range compare_a_2 = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise_2);
  lir::Instruction* case_a_2 = AddReturn(m, 1);
  m.Place(otherwise_2);
  lir::Instruction* default_code_2 = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(hashes_2);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a_2});

  Filter(m);

  ExpectIgnored(m, {hash_code,
                    {jump, jump},
                    compare_a,
                    hash_code_2,
                    {jump_2, jump_2},
                    compare_a_2});
  // The code of "a" in the first `when` goes on with the second `when`, the
  // default code of the first, which only the goto after the first switch
  // runs for alone.
  ExpectReplaced(m, {{switch_bytecode, {jump, case_a}},
                     {switch_bytecode_2, {default_code_2, case_a_2}}});
}

// The check of a nullable subject.

TEST_F(StringSwitchFilterTest, ShouldFilterNullCheckOfNullableSubject) {
  // fun f(s: String?) checks for null before the call to hashCode(), and goes
  // to the default code.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  m.AddLine(13);  // The start line.
  m.AddLine(13);
  lir::Instruction* null_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(otherwise)});
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  // The null check gets the branches of the switch. It goes to the default
  // code, which already is a branch.
  ExpectIgnored(
      m,
      {hash_code, {switch_bytecode, switch_bytecode}, {jump, jump}, compare_a});
  ExpectReplaced(m, {{null_check, {default_code, case_a}}});
}

TEST_F(StringSwitchFilterTest, ShouldAddBranchForNull) {
  // when (s) { "a" -> 1; null -> 2; else -> 3 }
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* code_of_null = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Instruction* null_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(code_of_null)});
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(code_of_null);
  lir::Instruction* case_null = AddReturn(m, 2);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  ExpectIgnored(
      m,
      {hash_code, {switch_bytecode, switch_bytecode}, {jump, jump}, compare_a});
  ExpectReplaced(m, {{null_check, {default_code, case_a, case_null}}});
}

TEST_F(StringSwitchFilterTest, ShouldNotDuplicateBranchOfNullAndString) {
  // when (s) { "a", null -> 1; else -> 3 }: the null check jumps to the code
  // of "a".
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Instruction* null_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(code_of_a)});
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_NEZ, code_of_a);
  lir::Instruction* to_default = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(code_of_a);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  ExpectIgnored(m, {hash_code,
                    {switch_bytecode, switch_bytecode},
                    {jump, jump},
                    compare_a,
                    {to_default, to_default}});
  ExpectReplaced(m, {{null_check, {default_code, case_a}}});
}

TEST_F(StringSwitchFilterTest, ShouldMapNullThroughGotoToDefaultBranch) {
  // The null check jumps to the goto after the switch.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* after_switch = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Instruction* null_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(after_switch)});
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Place(after_switch);
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  lir::Instruction* case_a = AddReturn(m, 1);
  m.Place(otherwise);
  lir::Instruction* default_code = AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  ExpectIgnored(
      m,
      {hash_code, {switch_bytecode, switch_bytecode}, {jump, jump}, compare_a});
  ExpectReplaced(m, {{null_check, {default_code, case_a}}});
}

// The default code of a `when` statement without `else` is where its branches
// meet.

// Builds `when (s) { "a" -> a(); "b" -> b() }`, a `when` statement without
// `else` whose branches meet after it, as the test says, and expects the goto
// after the switch to be the default branch.
class StringSwitchFilterStatementTest : public StringSwitchFilterTest {
 protected:
  // How the code of "b" goes on with the code after the `when`.
  enum class Join {
    // Jumps to it.
    kJump,
    // Jumps to the goto after the switch.
    kJumpToGotoAfterSwitch,
    // Falls through to it.
    kFallThrough,
    // Returns, and another switch jumps to the code after the `when`.
    kOtherSwitch,
  };

  void ExpectGotoAfterSwitchIsDefaultBranch(Join join) {
    TestMethod m("f", "(Ljava/lang/String;)V");
    lir::Label* hashes = m.NewLabel();
    lir::Label* hash_of_a = m.NewLabel();
    lir::Label* hash_of_b = m.NewLabel();
    lir::Label* after_switch = m.NewLabel();
    lir::Label* after = m.NewLabel();
    lir::Label* others = m.NewLabel();
    Range hash_code = AddHashCode(m);
    lir::Instruction* switch_bytecode =
        m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
    m.Place(after_switch);
    lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(after)});
    m.Place(hash_of_a);
    Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, after);
    lir::Instruction* case_a = m.Add(
        dex::OP_INVOKE_STATIC, {m.Regs({}), m.MethodRef("LFoo;", "a", "()V")});
    m.Add(dex::OP_RETURN_VOID);
    m.Place(hash_of_b);
    Range compare_b = AddComparison(m, "b", dex::OP_IF_EQZ, after);
    lir::Instruction* case_b = m.Add(
        dex::OP_INVOKE_STATIC, {m.Regs({}), m.MethodRef("LFoo;", "b", "()V")});
    switch (join) {
      case Join::kJump:
        m.Add(dex::OP_GOTO, {m.Target(after)});
        break;
      case Join::kJumpToGotoAfterSwitch:
        m.Add(dex::OP_GOTO, {m.Target(after_switch)});
        break;
      case Join::kFallThrough:
        break;
      case Join::kOtherSwitch:
        m.Add(dex::OP_RETURN_VOID);
        m.Add(dex::OP_PACKED_SWITCH, {m.Reg(5), m.Target(others)});
        m.Add(dex::OP_RETURN_VOID);
        break;
    }
    m.Place(after);
    m.Add(dex::OP_RETURN_VOID);
    m.Place(hashes);
    m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
    m.Place(others);
    m.AddPackedSwitchPayload(0, {after});

    Filter(m);

    ExpectIgnored(m, {hash_code, {jump, jump}, compare_a, compare_b});
    ExpectReplaced(m, {{switch_bytecode, {jump, case_a, case_b}}});
  }
};

TEST_F(StringSwitchFilterStatementTest, ShouldUseGotoWhenBranchJumpsToJoin) {
  ExpectGotoAfterSwitchIsDefaultBranch(Join::kJump);
}

TEST_F(StringSwitchFilterStatementTest,
       ShouldUseGotoWhenBranchJumpsToGotoAfterSwitch) {
  ExpectGotoAfterSwitchIsDefaultBranch(Join::kJumpToGotoAfterSwitch);
}

TEST_F(StringSwitchFilterStatementTest, ShouldUseGotoWhenBranchFallsThrough) {
  ExpectGotoAfterSwitchIsDefaultBranch(Join::kFallThrough);
}

TEST_F(StringSwitchFilterStatementTest, ShouldUseGotoWhenOtherSwitchJumps) {
  ExpectGotoAfterSwitchIsDefaultBranch(Join::kOtherSwitch);
}

TEST_F(StringSwitchFilterTest, ShouldUseSharedDefaultCodeWithoutGoto) {
  // Like a `when` statement without `else`, but the code after it follows the
  // switch, so no code runs for the default branch alone.
  TestMethod m("f", "(Ljava/lang/String;)V");
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* after = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Place(after);
  lir::Instruction* default_code = m.Add(dex::OP_RETURN_VOID);
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, after);
  lir::Instruction* case_a = m.Add(
      dex::OP_INVOKE_STATIC, {m.Regs({}), m.MethodRef("LFoo;", "a", "()V")});
  m.Add(dex::OP_GOTO, {m.Target(after)});
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  ExpectIgnored(m, {hash_code, compare_a});
  ExpectReplaced(m, {{switch_bytecode, {default_code, case_a}}});
}

// Builds `when (s) { "a" -> 1; else -> 3 }` with a check of v<reg> before the
// call to hashCode(), changed as the test says, and expects the filter to keep
// that check.
class StringSwitchFilterNullCheckTest : public StringSwitchFilterTest {
 protected:
  struct Shape {
    dex::Opcode check = dex::OP_IF_EQZ;
    dex::u4 reg = 1;
    // Whether the call to hashCode() starts another line.
    bool other_line = false;
    // Whether the check has no line of its own, so that the call to
    // hashCode() starts the first line, with the line of the check.
    bool before_first_line = false;
    // Whether other code can jump to the call to hashCode().
    bool jump_target = false;
  };

  void ExpectCheckKept(const Shape& shape) {
    TestMethod m("f", kSignature);
    lir::Label* hashes = m.NewLabel();
    lir::Label* hash_of_a = m.NewLabel();
    lir::Label* start = m.NewLabel();
    lir::Label* otherwise = m.NewLabel();
    m.AddLine(2);  // The start line.
    if (!shape.before_first_line) {
      m.AddLine(2);
    }
    m.Add(shape.check, {m.Reg(shape.reg), m.Target(otherwise)});
    if (shape.other_line) {
      m.AddLine(3);
    }
    if (shape.before_first_line) {
      m.AddLine(2);
    }
    if (shape.jump_target) {
      m.Place(start);
    }
    Range hash_code = AddHashCode(m);
    lir::Instruction* switch_bytecode =
        m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
    lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
    m.Place(hash_of_a);
    Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
    m.AddLine(4);
    lir::Instruction* case_a = AddReturn(m, 1);
    m.Place(otherwise);
    m.AddLine(5);
    AddReturn(m, 3);
    if (shape.jump_target) {
      m.Add(dex::OP_GOTO, {m.Target(start)});
    }
    m.Place(hashes);
    m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

    Filter(m);

    // The check goes to the default code too, so only the goto after the
    // switch runs for the default branch alone.
    ExpectIgnored(m, {hash_code, {jump, jump}, compare_a});
    ExpectReplaced(m, {{switch_bytecode, {jump, case_a}}});
  }
};

TEST_F(StringSwitchFilterNullCheckTest, ShouldNotIgnoreCheckOnOtherLine) {
  // if (s != null) on a line of its own, before the `when`.
  Shape shape;
  shape.other_line = true;
  ExpectCheckKept(shape);
}

TEST_F(StringSwitchFilterNullCheckTest, ShouldNotIgnoreCheckBeforeFirstLine) {
  // In release mode, D8 gives no line to the check at the start of a method.
  // The code of a Kotlin class before its first line isn't reported, so the
  // switch keeps the branches.
  Shape shape;
  shape.before_first_line = true;
  ExpectCheckKept(shape);
}

TEST_F(StringSwitchFilterNullCheckTest, ShouldNotIgnoreCheckOfOtherRegister) {
  Shape shape;
  shape.reg = 2;
  ExpectCheckKept(shape);
}

TEST_F(StringSwitchFilterNullCheckTest, ShouldNotIgnoreOtherJump) {
  Shape shape;
  shape.check = dex::OP_IF_NEZ;
  ExpectCheckKept(shape);
}

TEST_F(StringSwitchFilterNullCheckTest, ShouldNotIgnoreCheckBeforeJumpTarget) {
  Shape shape;
  shape.jump_target = true;
  ExpectCheckKept(shape);
}

TEST_F(StringSwitchFilterNullCheckTest, ShouldNotIgnoreCheckInJavaClass) {
  // A Java switch on a String throws for null, and has no such check.
  context_.class_annotations.clear();
  ExpectCheckKept(Shape());
}

// The code that javac generates, which this filter leaves alone, and code
// that only looks like it.

// How StringSwitchFilterJavacTest::AddSwitch() lays out the switch on the
// String in v1: javac's code, unless a test changes one of the fields.
struct JavacShape {
  // The line of the load of the index of "a".
  int load_line = 5;
  // Whether "b" moves its index from v3, where D8 loaded it before the
  // switch, instead of loading it.
  bool b_moves_index = true;
  // The value that the default code loads, and the register that it loads it
  // into. With -2, it moves -1 from v4 instead, where D8 loaded it.
  dex::s4 none = -1;
  dex::u4 none_register = 0;
  // The register that the second switch reads.
  dex::u4 second_register = 0;
  // Whether "b" goes to a third switch instead of the second.
  bool b_goes_to_third = false;
  // Whether the comparisons jump to the goto after the first switch, which
  // goes to the default code.
  bool through_goto = false;
  // Whether the loads go to a return of the index instead of a second switch.
  bool returns = false;
};

class StringSwitchFilterJavacTest : public StringSwitchFilterTest {
 protected:
  // The parts of the code that the filter would ignore, if it filtered it.
  struct Parts {
    Range hash_code;
    lir::Instruction* switch_bytecode;
    lir::Instruction* jump;
    Range compare_a;
    Range compare_b;
    lir::Instruction* load_a;
    lir::Instruction* load_b;
    lir::Instruction* load_none;
  };

  // Appends javac's code for
  //   switch (s) { case "a": return 1; case "b": return 2; default: return 3; }
  // laid out as `shape`, with the index in v0, and returns its parts.
  static Parts AddSwitch(TestMethod& m, const JavacShape& shape) {
    Parts parts;
    lir::Label* hashes = m.NewLabel();
    lir::Label* indices = m.NewLabel();
    lir::Label* hash_of_a = m.NewLabel();
    lir::Label* hash_of_b = m.NewLabel();
    lir::Label* after_switch = m.NewLabel();
    lir::Label* no_match = m.NewLabel();
    lir::Label* second = m.NewLabel();
    lir::Label* third = m.NewLabel();
    lir::Label* code_of_a = m.NewLabel();
    lir::Label* code_of_b = m.NewLabel();
    m.AddLine(5);  // The start line.
    m.AddLine(5);
    parts.hash_code = AddHashCode(m);
    m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(1)});
    m.Add(dex::OP_CONST_4, {m.Reg(4), m.Literal(static_cast<dex::u4>(-1))});
    parts.switch_bytecode =
        m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
    m.Place(after_switch);
    parts.jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
    lir::Label* failed = shape.through_goto ? after_switch : no_match;
    m.Place(hash_of_a);
    parts.compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, failed);
    if (shape.load_line != 5) {
      m.AddLine(shape.load_line);
    }
    parts.load_a = m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    m.Add(dex::OP_GOTO, {m.Target(second)});
    m.AddLine(5);
    m.Place(hash_of_b);
    parts.compare_b = AddComparison(m, "b", dex::OP_IF_EQZ, failed);
    parts.load_b = shape.b_moves_index
                       ? m.Add(dex::OP_MOVE, {m.Reg(0), m.Reg(3)})
                       : m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
    m.Add(dex::OP_GOTO, {m.Target(shape.b_goes_to_third ? third : second)});
    m.Place(no_match);
    parts.load_none =
        shape.none == -2
            ? m.Add(dex::OP_MOVE, {m.Reg(shape.none_register), m.Reg(4)})
            : m.Add(dex::OP_CONST_4,
                    {m.Reg(shape.none_register),
                     m.Literal(static_cast<dex::u4>(shape.none))});
    m.Place(second);
    if (shape.returns) {
      m.Add(dex::OP_RETURN, {m.Reg(0)});
    } else {
      m.Add(dex::OP_PACKED_SWITCH,
            {m.Reg(shape.second_register), m.Target(indices)});
    }
    m.AddLine(11);
    AddReturn(m, 3);
    m.Place(third);
    m.AddLine(5);
    m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
    m.AddLine(11);
    AddReturn(m, 3);
    m.Place(code_of_b);
    m.AddLine(9);
    AddReturn(m, 2);
    m.Place(code_of_a);
    m.AddLine(7);
    AddReturn(m, 1);
    m.Place(hashes);
    m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
    m.Place(indices);
    m.AddPackedSwitchPayload(0, {code_of_a, code_of_b});
    return parts;
  }

  // Expects the filter to leave javac's code laid out as `shape` alone in a
  // Java class.
  void ExpectNotFiltered(const JavacShape& shape) {
    context_.class_annotations.clear();
    TestMethod m("f", kSignature);
    AddSwitch(m, shape);

    Filter(m);

    EXPECT_TRUE(output_.ignored().empty());
  }

  // Expects the filter to filter code laid out as `shape` in a class of
  // `language`, which isn't javac's code there, like the code of a `when`.
  enum class Language { kJava, kKotlin };
  void ExpectFiltered(const JavacShape& shape,
                      Language language = Language::kJava) {
    if (language == Language::kJava) {
      context_.class_annotations.clear();
    }
    TestMethod m("f", kSignature);
    Parts parts = AddSwitch(m, shape);

    Filter(m);

    ExpectIgnored(m, {parts.hash_code,
                      {parts.jump, parts.jump},
                      parts.compare_a,
                      parts.compare_b});
    ExpectReplaced(m, {{parts.switch_bytecode,
                        {parts.load_none, parts.load_a, parts.load_b}}});
  }
};

TEST_F(StringSwitchFilterJavacTest, ShouldNotFilterCodeThatJavacGenerates) {
  // "a" loads its index, and "b" moves it from a register.
  ExpectNotFiltered(JavacShape());
}

TEST_F(StringSwitchFilterJavacTest,
       ShouldNotFilterJavacCodeWhoseComparisonsJumpToGoto) {
  JavacShape shape;
  shape.through_goto = true;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterJavacTest, ShouldNotFilterJavacCodeThatMovesMinusOne) {
  JavacShape shape;
  shape.b_moves_index = false;
  shape.none = -2;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterJavacTest, ShouldNotFilterJavacCodeWithLeftOutLoad) {
  // v2 already holds 1, the index of "b", so the comparison with "b" jumps
  // right to the class that D8 loads before the second switch, on line 6 in
  // release mode. StringSwitchJavacFilter filters this code.
  context_.class_annotations.clear();
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* hoisted = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  m.AddLine(5);  // The start line.
  m.AddLine(5);
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(1)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(0)});
  m.Add(dex::OP_GOTO, {m.Target(hoisted)});
  m.Place(hash_of_b);
  AddComparison(m, "b", dex::OP_IF_NEZ, hoisted);
  m.Place(no_match);
  m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(hoisted);
  m.AddLine(6);
  m.Add(dex::OP_CONST_CLASS, {m.Reg(3), m.TypeRef(kString)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(2), m.Target(indices)});
  m.AddLine(9);
  AddReturn(m, 2);
  m.Place(code_of_a);
  m.AddLine(7);
  AddReturn(m, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(StringSwitchFilterJavacTest,
       ShouldFilterSwitchWhoseCaseLoadsOnOtherLine) {
  // Like the code that ECJ generates, whose cases are on their own lines.
  JavacShape shape;
  shape.load_line = 6;
  ExpectFiltered(shape);
}

// Shapes like javac's that StringSwitchJavacFilter doesn't match, all with
// cases that start on the line of the switch.
std::vector<JavacShape> ShapesOnLineOfSwitch() {
  JavacShape other_default;
  other_default.none = 2;
  JavacShape other_register;
  other_register.none_register = 1;
  JavacShape other_switch_register;
  other_switch_register.second_register = 1;
  JavacShape returns;
  returns.returns = true;
  JavacShape third_switch;
  third_switch.b_goes_to_third = true;
  return {other_default, other_register, other_switch_register, returns,
          third_switch};
}

TEST_F(StringSwitchFilterJavacTest, ShouldNotFilterCodeOnLineOfSwitch) {
  // In a Java class, such cases are javac's loads of the index in shapes that
  // StringSwitchJavacFilter doesn't match. Filtering the first switch here
  // would add its branches to those of the second switch.
  for (const JavacShape& shape : ShapesOnLineOfSwitch()) {
    output_ = Output();
    ExpectNotFiltered(shape);
  }
}

TEST_F(StringSwitchFilterJavacTest, ShouldFilterCodeOnLineOfSwitchInKotlin) {
  // In a Kotlin class, the same code is the one of a `when` on one line.
  for (const JavacShape& shape : ShapesOnLineOfSwitch()) {
    output_ = Output();
    ExpectFiltered(shape, Language::kKotlin);
  }
}

TEST_F(StringSwitchFilterJavacTest, ShouldFilterJavacShapeInKotlinClass) {
  // The Kotlin compiler never generates javac's code, so code that looks like
  // it is the code of a `when` on one line.
  ExpectFiltered(JavacShape(), Language::kKotlin);
}

// The set of the filter.

TEST_F(StringSwitchFilterTest, ShouldRunOnJavaClasses) {
  // ECJ generates this code for a switch on a String in Java classes.
  context_.class_annotations.clear();
  context_.source_file = "Foo.java";
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  m.AddLine(3);  // The start line.
  m.AddLine(3);
  Range hash_code = AddHashCode(m);
  lir::Instruction* switch_bytecode =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  m.AddLine(4);
  AddReturn(m, 1);
  m.Place(otherwise);
  m.AddLine(5);
  AddReturn(m, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(hash_code.from));
  const std::vector<lir::Instruction*>* targets =
      result.GetReplacedBranches(switch_bytecode);
  ASSERT_NE(targets, nullptr);
  EXPECT_EQ(targets->size(), 2u);
}

// Code that only looks like the code of a `when`.

// Builds `when (s) { "a" -> 1; else -> 3 }`, changed as the test says, and
// expects the filter to leave it alone.
class StringSwitchFilterNegativeTest : public StringSwitchFilterTest {
 protected:
  struct Shape {
    // The method that the code switches on the result of, and whether the
    // code loads a constant into v3 instead of moving that result there.
    const char* hash_owner = kString;
    const char* hash_name = "hashCode";
    bool discards_hash_code = false;
    // What the code loads between the call to hashCode(), whose result is in
    // v3, and the switch: nothing, a constant into v3, a wide constant into
    // v2 and v3, or a string into the String in v1.
    enum class Load { kNone, kHashCode, kWideOverHashCode, kSubject };
    Load load = Load::kNone;
    // The register of the switch, whether it has cases, and whether its case
    // goes to the default code.
    dex::u4 switch_register = 3;
    bool has_cases = true;
    bool case_goes_to_default = false;
    // Whether the case of the hash code compares the String, and how.
    bool compares = true;
    dex::u4 compared = 1;
    dex::u4 loaded = 0;
    const char* method = "equals";
    const char* signature = kEquals;
    // Whether the comparison loads a constant into v0 instead of moving the
    // result of equals() there.
    bool discards_result = false;
    dex::Opcode jump = dex::OP_IF_EQZ;
    dex::u4 checked = 0;
    // Whether the comparison jumps back to itself when it fails.
    bool loops = false;
    // Whether an if-nez comparison is followed by other code.
    bool other_code = false;
    // Whether the goto after the switch jumps to itself.
    bool goto_loops = false;
  };

  // The switch, the default code and the code of "a".
  struct Parts {
    lir::Instruction* switch_bytecode;
    lir::Instruction* default_code;
    lir::Instruction* case_a;
  };

  // Appends the code laid out as `shape` to `m`, and returns its parts.
  static Parts Build(TestMethod& m, const Shape& shape) {
    Parts parts = {nullptr, nullptr, nullptr};
    lir::Label* hashes = m.NewLabel();
    lir::Label* hash_of_a = m.NewLabel();
    lir::Label* code_of_a = m.NewLabel();
    lir::Label* after_switch = m.NewLabel();
    lir::Label* otherwise = m.NewLabel();
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({1}), m.MethodRef(shape.hash_owner, shape.hash_name, "()I")});
    if (shape.discards_hash_code) {
      m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(1)});
    } else {
      m.Add(dex::OP_MOVE_RESULT, {m.Reg(3)});
    }
    switch (shape.load) {
      case Shape::Load::kNone:
        break;
      case Shape::Load::kHashCode:
        m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(1)});
        break;
      case Shape::Load::kWideOverHashCode:
        m.Add(dex::OP_CONST_WIDE_16,
              {m.code_ir().Alloc<lir::VRegPair>(2), m.Literal(1)});
        break;
      case Shape::Load::kSubject:
        m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef("a")});
        break;
    }
    parts.switch_bytecode =
        m.Add(dex::OP_PACKED_SWITCH,
              {m.Reg(shape.switch_register), m.Target(hashes)});
    m.Place(after_switch);
    m.Add(dex::OP_GOTO,
          {m.Target(shape.goto_loops ? after_switch : otherwise)});
    m.Place(hash_of_a);
    if (shape.compares) {
      m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("a")});
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({shape.compared, shape.loaded}),
             m.MethodRef(kString, shape.method, shape.signature)});
      if (shape.discards_result) {
        m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
      } else {
        m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
      }
      m.Add(shape.jump,
            {m.Reg(shape.checked),
             m.Target(shape.loops ? hash_of_a
                                  : shape.jump == dex::OP_IF_NEZ ? code_of_a
                                                                 : otherwise)});
      if (shape.other_code) {
        m.Add(dex::OP_INVOKE_STATIC,
              {m.Regs({}), m.MethodRef("LFoo;", "log", "()V")});
      }
    }
    m.Place(code_of_a);
    parts.case_a = AddReturn(m, 1);
    m.Place(otherwise);
    parts.default_code = AddReturn(m, 3);
    m.Place(hashes);
    if (shape.has_cases) {
      m.AddPackedSwitchPayload(
          kHashOfA, {shape.case_goes_to_default ? otherwise : hash_of_a});
    } else {
      m.AddPackedSwitchPayload(kHashOfA, {});
    }

    return parts;
  }

  void ExpectNotFiltered(const Shape& shape) {
    TestMethod m("f", kSignature);
    Build(m, shape);

    Filter(m);

    EXPECT_TRUE(output_.ignored().empty());
  }
};

TEST_F(StringSwitchFilterNegativeTest, ShouldFilterUnchangedShape) {
  // The code of these tests, unchanged, is filtered.
  TestMethod m("f", kSignature);
  Parts parts = Build(m, Shape());

  Filter(m);

  EXPECT_FALSE(output_.ignored().empty());
  ExpectReplaced(m,
                 {{parts.switch_bytecode, {parts.default_code, parts.case_a}}});
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterHashCodeOfOtherClass) {
  Shape shape;
  shape.hash_owner = "Ljava/lang/Object;";
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterSwitchOnOtherMethod) {
  // A switch on length() instead of hashCode().
  Shape shape;
  shape.hash_name = "length";
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterDiscardedHashCode) {
  // The switch is on a constant, not on the result of hashCode().
  Shape shape;
  shape.discards_hash_code = true;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterOverwrittenHashCode) {
  Shape shape;
  shape.load = Shape::Load::kHashCode;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest,
       ShouldNotFilterHashCodeOverwrittenByWideConstant) {
  Shape shape;
  shape.load = Shape::Load::kWideOverHashCode;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterOverwrittenSubject) {
  Shape shape;
  shape.load = Shape::Load::kSubject;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterSwitchOnOtherRegister) {
  Shape shape;
  shape.switch_register = 2;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterSwitchWithoutCases) {
  Shape shape;
  shape.has_cases = false;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest,
       ShouldNotFilterSwitchWhoseCaseGoesToDefaultCode) {
  // A switch on s.hashCode() in the source, whose only case goes to the
  // default code, compares no string.
  Shape shape;
  shape.case_goes_to_default = true;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterCaseWithoutComparison) {
  Shape shape;
  shape.compares = false;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterComparisonOfOtherString) {
  Shape shape;
  shape.compared = 2;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest,
       ShouldNotFilterComparisonWithOtherLoadedString) {
  // The comparison loads "a" into v0 but compares with v2.
  Shape shape;
  shape.loaded = 2;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterOtherComparison) {
  Shape shape;
  shape.method = "equalsIgnoreCase";
  shape.signature = "(Ljava/lang/String;)Z";
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterDiscardedComparison) {
  // The jump checks a constant, not the result of equals().
  Shape shape;
  shape.discards_result = true;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterOtherJump) {
  Shape shape;
  shape.jump = dex::OP_IF_LEZ;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterJumpOnOtherResult) {
  Shape shape;
  shape.checked = 2;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterComparisonThatLoops) {
  Shape shape;
  shape.loops = true;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest,
       ShouldNotFilterComparisonFollowedByOtherCode) {
  Shape shape;
  shape.jump = dex::OP_IF_NEZ;
  shape.other_code = true;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterNegativeTest, ShouldNotFilterGotoThatLoops) {
  Shape shape;
  shape.goto_loops = true;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchFilterTest, ShouldNotFilterHashCodeWithoutSwitch) {
  TestMethod m("f", kSignature);
  AddHashCode(m);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

}  // namespace
}  // namespace coverage
