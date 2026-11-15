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

#include "tools/base/android-test/coverage/agent/native/string_switch_javac_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

namespace coverage {
namespace {

constexpr char kString[] = "Ljava/lang/String;";
constexpr char kEquals[] = "(Ljava/lang/Object;)Z";
// The signature of static int f(String s).
constexpr char kSignature[] = "(Ljava/lang/String;)I";
// The hash code of "a", which "b" follows. "Aa" and "BB" both have kHashOfAa.
constexpr dex::s4 kHashOfA = 97;
constexpr dex::s4 kHashOfAa = 2112;
// The line of the switch.
constexpr int kSwitchLine = 5;

// Unit tests for StringSwitchJavacFilter, on the code that javac and D8
// generate for a switch on the String in v2, such as
//   switch (s) {
//     case "a":
//       return 1;
//     case "b":
//       return 2;
//     default:
//       return 3;
//   }
// The index of the string that matches is in v0.
class StringSwitchJavacFilterTest : public FilterTestBase {
 protected:
  // Appends the call to hashCode() on the String in v2 on the line of the
  // switch, with its result in v0.
  static Range AddHashCode(TestMethod& m) {
    m.AddLine(kSwitchLine);  // The start line.
    m.AddLine(kSwitchLine);
    lir::Instruction* call =
        m.Add(dex::OP_INVOKE_VIRTUAL,
              {m.Regs({2}), m.MethodRef(kString, "hashCode", "()I")});
    return {call, m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)})};
  }

  // Appends the comparison of the String in v2 with `value`, which jumps to
  // `target` with `jump`, an if-eqz or an if-nez.
  static Range AddComparison(TestMethod& m, const char* value, dex::Opcode jump,
                             lir::Label* target) {
    lir::Instruction* load =
        m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef(value)});
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({2, 0}), m.MethodRef(kString, "equals", kEquals)});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    return {load, m.Add(jump, {m.Reg(0), m.Target(target)})};
  }

  // Appends the load of `index` into v0, followed by a goto to `second`.
  static Range AddLoad(TestMethod& m, dex::s4 index, lir::Label* second) {
    lir::Instruction* load = m.Add(
        dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(index))});
    return {load, m.Add(dex::OP_GOTO, {m.Target(second)})};
  }

  // Appends `return <value>` on line `line`, with the value in v0.
  static void AddReturn(TestMethod& m, int line, dex::u4 value) {
    m.AddLine(line);
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(value)});
    m.Add(dex::OP_RETURN, {m.Reg(0)});
  }

  void Filter(TestMethod& m) {
    StringSwitchJavacFilter().Filter(context_, m.ir_method(), m.code_ir(),
                                     output_);
  }
};

TEST_F(StringSwitchJavacFilterTest, ShouldFilterFirstSwitch) {
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* code_of_b = m.NewLabel();
  AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_EQZ, no_match);
  Range load_b = AddLoad(m, 1, second);
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  Range load_a = AddLoad(m, 0, second);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_b);
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a, code_of_b});

  Filter(m);

  // The call to hashCode() stays, and the second switch keeps its branches.
  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    compare_b,
                    load_a,
                    load_b,
                    {load_none, load_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterStringsWithSameHashCode) {
  // The comparison with "BB" jumps to the comparison with "Aa" when it fails.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* same_hash = m.NewLabel();
  lir::Label* next = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_aa = m.NewLabel();
  lir::Label* code_of_bb = m.NewLabel();
  AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_SPARSE_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(same_hash);
  Range compare_bb = AddComparison(m, "BB", dex::OP_IF_EQZ, next);
  Range load_bb = AddLoad(m, 1, second);
  m.Place(next);
  Range compare_aa = AddComparison(m, "Aa", dex::OP_IF_EQZ, no_match);
  Range load_aa = AddLoad(m, 0, second);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_bb);
  AddReturn(m, 9, 2);
  m.Place(code_of_aa);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddSparseSwitchPayload({{kHashOfAa, same_hash}});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_aa, code_of_bb});

  Filter(m);

  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_bb,
                    compare_aa,
                    load_bb,
                    load_aa,
                    {load_none, load_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterLoadsOfConstantsLoadedEarlier) {
  // D8 loads 1 into v1 before the first switch, because the method uses it
  // twice, and -1 into v3. The cases then move them into v0.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* code_of_b = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(static_cast<dex::u4>(-1))});
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  Range load_a = AddLoad(m, 0, second);
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_EQZ, no_match);
  lir::Instruction* move_b = m.Add(dex::OP_MOVE, {m.Reg(0), m.Reg(1)});
  lir::Instruction* goto_b = m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(no_match);
  lir::Instruction* move_none = m.Add(dex::OP_MOVE, {m.Reg(0), m.Reg(3)});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_b);
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  m.AddLine(7);
  m.Add(dex::OP_RETURN, {m.Reg(1)});
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a, code_of_b});

  Filter(m);

  // The constants loaded before the first switch stay.
  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    compare_b,
                    load_a,
                    {move_b, goto_b},
                    {move_none, move_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterLoadRightBeforeSecondSwitch) {
  // The load of "a" goes on with the second switch, and the default code
  // jumps to it.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Place(no_match);
  Range load_none = AddLoad(m, -1, second);
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  lir::Instruction* load_a = m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  Filter(m);

  ExpectIgnored(
      m,
      {{first_switch, first_switch}, compare_a, {load_a, load_a}, load_none});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterJumpsThroughGotoAfterSwitch) {
  // The comparison with "a" jumps to the goto after the first switch when it
  // fails. The one with "b" jumps to its load when it succeeds, and goes on
  // with a goto to the goto after the first switch.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* load_of_b = m.NewLabel();
  lir::Label* after_switch = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* code_of_b = m.NewLabel();
  AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Place(after_switch);
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, after_switch);
  Range load_a = AddLoad(m, 0, second);
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_NEZ, load_of_b);
  lir::Instruction* to_default = m.Add(dex::OP_GOTO, {m.Target(after_switch)});
  m.Place(load_of_b);
  Range load_b = AddLoad(m, 1, second);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_b);
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a, code_of_b});

  Filter(m);

  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    compare_b,
                    {to_default, to_default},
                    load_a,
                    load_b,
                    {load_none, load_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterPackedSwitchWithHoles) {
  // The hash codes of "a" and "c" aren't next to each other, and the
  // packed-switch goes to the default code for the hash code in between.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_c = m.NewLabel();
  lir::Label* after_switch = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* code_of_c = m.NewLabel();
  AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Place(after_switch);
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  Range load_a = AddLoad(m, 0, second);
  m.Place(hash_of_c);
  Range compare_c = AddComparison(m, "c", dex::OP_IF_EQZ, no_match);
  Range load_c = AddLoad(m, 1, second);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_c);
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, after_switch, hash_of_c});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a, code_of_c});

  Filter(m);

  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    compare_c,
                    load_a,
                    load_c,
                    {load_none, load_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterLoadsOfLargeSwitch) {
  // The index of the ninth string of a switch doesn't fit in a const/4, and
  // the gotos of a large switch are goto/16s.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO_16, {m.Target(no_match)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  lir::Instruction* load_a = m.Add(dex::OP_CONST_16, {m.Reg(0), m.Literal(8)});
  lir::Instruction* goto_a = m.Add(dex::OP_GOTO_16, {m.Target(second)});
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  m.Add(dex::OP_SPARSE_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddSparseSwitchPayload({{8, code_of_a}});

  Filter(m);

  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    {load_a, goto_a},
                    {load_none, load_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterEachSwitchOfMethod) {
  // Two switches on the String, the second of which follows the second switch
  // of the first, on line 8.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* hashes_2 = m.NewLabel();
  lir::Label* indices_2 = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* no_match_2 = m.NewLabel();
  lir::Label* second_2 = m.NewLabel();
  lir::Label* code_of_b = m.NewLabel();
  AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  Range load_a = AddLoad(m, 0, second);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  m.AddLine(8);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kString, "hashCode", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  lir::Instruction* first_switch_2 =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes_2)});
  lir::Instruction* jump_2 = m.Add(dex::OP_GOTO, {m.Target(no_match_2)});
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_EQZ, no_match_2);
  Range load_b = AddLoad(m, 0, second_2);
  m.Place(no_match_2);
  lir::Instruction* load_none_2 =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second_2);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices_2)});
  AddReturn(m, 12, 3);
  m.Place(code_of_b);
  AddReturn(m, 10, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});
  m.Place(hashes_2);
  m.AddPackedSwitchPayload(kHashOfA + 1, {hash_of_b});
  m.Place(indices_2);
  m.AddPackedSwitchPayload(0, {code_of_b});

  Filter(m);

  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    load_a,
                    {load_none, load_none},
                    {first_switch_2, first_switch_2},
                    {jump_2, jump_2},
                    compare_b,
                    load_b,
                    {load_none_2, load_none_2}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterLoadsBeforeHoistedConstant) {
  // D8 loads a constant that the code after the second switch uses right
  // before it, where the loads go, as in retrofit2.Platform.<clinit>.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* hoisted = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* code_of_b = m.NewLabel();
  AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  Range load_a = AddLoad(m, 0, hoisted);
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_EQZ, no_match);
  Range load_b = AddLoad(m, 1, hoisted);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(hoisted);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_b);
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a, code_of_b});

  Filter(m);

  // The constant before the second switch stays, like the second switch.
  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    compare_b,
                    load_a,
                    load_b,
                    {load_none, load_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterLoadsBeforeHoistedClass) {
  // D8 loads a class that the cases of the source use right before the second
  // switch.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* hoisted = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  Range load_a = AddLoad(m, 0, hoisted);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(hoisted);
  m.Add(dex::OP_CONST_CLASS, {m.Reg(1), m.TypeRef(kString)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  Filter(m);

  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    load_a,
                    {load_none, load_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterSwitchWithLeftOutLoad) {
  // v1 already holds 1, the index of "b", so D8 leaves out its load, and the
  // comparison with "b" jumps right to the second switch, as in
  // SVGAndroidRenderer.checkGenericFont of androidsvg.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* code_of_b = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  lir::Instruction* load_a = m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  lir::Instruction* goto_a = m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_NEZ, second);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_b);
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a, code_of_b});

  Filter(m);

  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    compare_b,
                    {load_a, goto_a},
                    {load_none, load_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldFilterSwitchWithLeftOutDefaultLoad) {
  // D8 loads -1 into v1 before the first switch, so the default code goes
  // right to the second switch, after a class that D8 loads there. In release
  // mode, D8 gives that load, and so the second switch, the line of other
  // code.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* hoisted = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* code_of_b = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(static_cast<dex::u4>(-1))});
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(hoisted)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, hoisted);
  lir::Instruction* load_a = m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  lir::Instruction* goto_a = m.Add(dex::OP_GOTO, {m.Target(hoisted)});
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_EQZ, hoisted);
  lir::Instruction* load_b = m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  m.Place(hoisted);
  m.AddLine(11);
  m.Add(dex::OP_CONST_CLASS, {m.Reg(3), m.TypeRef(kString)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_b);
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a, code_of_b});

  Filter(m);

  // The load of -1 before the first switch stays, like the call to
  // hashCode().
  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    compare_b,
                    {load_a, goto_a},
                    {load_b, load_b}});
}

TEST_F(StringSwitchJavacFilterTest,
       ShouldFilterLeftOutLoadBeforeClassOnOtherLine) {
  // v1 already holds 1, the index of "b", so the comparison with "b" jumps
  // right to the class that D8 loads before the second switch, on line 6 in
  // release mode.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* hoisted = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  Range compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  lir::Instruction* load_a = m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  lir::Instruction* goto_a = m.Add(dex::OP_GOTO, {m.Target(hoisted)});
  m.Place(hash_of_b);
  Range compare_b = AddComparison(m, "b", dex::OP_IF_NEZ, hoisted);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(hoisted);
  m.AddLine(6);
  m.Add(dex::OP_CONST_CLASS, {m.Reg(3), m.TypeRef(kString)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  Filter(m);

  ExpectIgnored(m, {{first_switch, first_switch},
                    {jump, jump},
                    compare_a,
                    compare_b,
                    {load_a, goto_a},
                    {load_none, load_none}});
}

TEST_F(StringSwitchJavacFilterTest, ShouldNotFilterSwitchWhereNothingLoads) {
  // The comparisons and the default code all go right to another switch.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(static_cast<dex::u4>(-1))});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_NEZ, second);
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(StringSwitchJavacFilterTest, ShouldNotFilterSwitchWithoutCases) {
  // No hash code goes to a comparison, so nothing loads an index, and the
  // default code goes right to a switch on v1, which holds -1.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(static_cast<dex::u4>(-1))});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(hash_of_a);
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(StringSwitchJavacFilterTest, ShouldNotFilterLeftOutLoadOfHashCode) {
  // The comparison with "b" goes right to a switch on the hash code, which
  // can't hold the index of "b".
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  AddLoad(m, 0, second);
  m.Place(hash_of_b);
  AddComparison(m, "b", dex::OP_IF_NEZ, second);
  m.Place(no_match);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(StringSwitchJavacFilterTest,
       ShouldNotFilterLeftOutDefaultLoadWithoutMinusOne) {
  // The default code goes right to the second switch, but nothing loads -1
  // into its register before the first switch.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(2)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, second);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(StringSwitchJavacFilterTest,
       ShouldNotFilterLoadOnOtherLineRightBeforeSecondSwitch) {
  // The load of the index of "a" is on line 6, so it is neither one of
  // javac's loads nor a left out one.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* code_of_b = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_b);
  AddComparison(m, "b", dex::OP_IF_EQZ, no_match);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(no_match);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(static_cast<dex::u4>(-1))});
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  m.AddLine(6);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Place(second);
  m.AddLine(kSwitchLine);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_b);
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a, code_of_b});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(StringSwitchJavacFilterTest, ShouldNotFilterLeftOutCaseAndDefault) {
  // v1 holds -1 for the default code, so the comparison with "b", which goes
  // right to the second switch, would run its default case too.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* hash_of_b = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  lir::Label* code_of_b = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(static_cast<dex::u4>(-1))});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, second);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(hash_of_b);
  AddComparison(m, "b", dex::OP_IF_NEZ, second);
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_b);
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a, hash_of_b});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a, code_of_b});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(StringSwitchJavacFilterTest,
       ShouldNotFilterLeftOutDefaultLoadBeforeOtherSwitch) {
  // The default code goes right to another switch on v1 than the load of "a".
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* third = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(static_cast<dex::u4>(-1))});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(third)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, third);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(third);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(indices)});
  AddReturn(m, 11, 3);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(StringSwitchJavacFilterTest, ShouldRunOnJavaClasses) {
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  Range hash_code = AddHashCode(m);
  lir::Instruction* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  AddLoad(m, 0, second);
  m.Place(no_match);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  lir::Instruction* second_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(first_switch));
  EXPECT_FALSE(result.IsIgnored(second_switch));
  // The filter of the code that ECJ and Kotlin generate, which would ignore
  // the call to hashCode(), leaves it alone.
  EXPECT_FALSE(result.IsIgnored(hash_code.from));
}

TEST_F(StringSwitchJavacFilterTest, ShouldNotRunOnKotlinClasses) {
  context_.class_annotations.insert(kKotlinMetadataDescriptor);
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* indices = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* code_of_a = m.NewLabel();
  Range hash_code = AddHashCode(m);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(no_match)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
  Range load_a = AddLoad(m, 0, second);
  m.Place(no_match);
  lir::Instruction* load_none =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Place(second);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
  AddReturn(m, 9, 2);
  m.Place(code_of_a);
  AddReturn(m, 7, 1);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
  m.Place(indices);
  m.AddPackedSwitchPayload(0, {code_of_a});

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  // Only the filter of the code that the Kotlin compiler generates runs. It
  // ignores the call to hashCode(), but not the loads of the index.
  EXPECT_TRUE(result.IsIgnored(hash_code.from));
  EXPECT_FALSE(result.IsIgnored(load_a.from));
  EXPECT_FALSE(result.IsIgnored(load_none));
}

// Builds javac's code for `switch (s) { case "a": return 1; default: return
// 2; }`, changed as the test says, and expects the filter to leave it alone.
class StringSwitchJavacFilterNegativeTest : public StringSwitchJavacFilterTest {
 protected:
  // How the code differs from javac's.
  struct Shape {
    // The line of the load of the index of "a".
    int load_line = kSwitchLine;
    // The value that the default code loads, and the register of that load.
    dex::s4 none = -1;
    dex::u4 none_register = 0;
    // The register of the second switch.
    dex::u4 second_register = 0;
    // Whether the load of "a" goes to a third switch instead of the second.
    bool other_switch = false;
    // Whether the case of "a" adds 1 to v0 instead of loading its index.
    bool add_instead_of_load = false;
    // Whether the loads go to a return of v0 instead of the second switch.
    bool returns = false;
  };

  // What the filter ignores in javac's code.
  struct Parts {
    lir::Instruction* first_switch;
    lir::Instruction* jump;
    Range compare_a;
    Range load_a;
    lir::Instruction* load_none;
  };

  // Appends the code laid out as `shape` to `m`, and returns its parts.
  static Parts Build(TestMethod& m, const Shape& shape) {
    Parts parts = {};
    lir::Label* hashes = m.NewLabel();
    lir::Label* indices = m.NewLabel();
    lir::Label* hash_of_a = m.NewLabel();
    lir::Label* no_match = m.NewLabel();
    lir::Label* second = m.NewLabel();
    lir::Label* third = m.NewLabel();
    lir::Label* code_of_a = m.NewLabel();
    AddHashCode(m);
    parts.first_switch =
        m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
    parts.jump = m.Add(dex::OP_GOTO, {m.Target(no_match)});
    m.Place(hash_of_a);
    parts.compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, no_match);
    if (shape.load_line != kSwitchLine) {
      m.AddLine(shape.load_line);
    }
    if (shape.add_instead_of_load) {
      lir::Instruction* add =
          m.Add(dex::OP_ADD_INT_LIT8, {m.Reg(0), m.Reg(0), m.Literal(1)});
      parts.load_a = {add, m.Add(dex::OP_GOTO, {m.Target(second)})};
    } else {
      parts.load_a = AddLoad(m, 0, shape.other_switch ? third : second);
    }
    m.Place(no_match);
    m.AddLine(kSwitchLine);
    parts.load_none =
        m.Add(dex::OP_CONST_4, {m.Reg(shape.none_register),
                                m.Literal(static_cast<dex::u4>(shape.none))});
    m.Place(second);
    if (shape.returns) {
      m.Add(dex::OP_RETURN, {m.Reg(0)});
    } else {
      m.Add(dex::OP_PACKED_SWITCH,
            {m.Reg(shape.second_register), m.Target(indices)});
      AddReturn(m, 9, 2);
    }
    m.Place(third);
    m.AddLine(kSwitchLine);
    m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(indices)});
    AddReturn(m, 9, 2);
    m.Place(code_of_a);
    AddReturn(m, 7, 1);
    m.Place(hashes);
    m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
    m.Place(indices);
    m.AddPackedSwitchPayload(0, {code_of_a});
    return parts;
  }

  void ExpectNotFiltered(const Shape& shape) {
    TestMethod m("f", kSignature);
    Build(m, shape);

    Filter(m);

    EXPECT_TRUE(output_.ignored().empty());
  }
};

TEST_F(StringSwitchJavacFilterNegativeTest, ShouldFilterUnchangedShape) {
  // The code of these tests, unchanged, is filtered.
  TestMethod m("f", kSignature);
  Parts parts = Build(m, Shape());

  Filter(m);

  ExpectIgnored(m, {{parts.first_switch, parts.first_switch},
                    {parts.jump, parts.jump},
                    parts.compare_a,
                    parts.load_a,
                    {parts.load_none, parts.load_none}});
}

TEST_F(StringSwitchJavacFilterNegativeTest, ShouldNotFilterLoadOnOtherLine) {
  // Like a `when` whose result another `when` then checks.
  Shape shape;
  shape.load_line = 6;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchJavacFilterNegativeTest,
       ShouldNotFilterDefaultThatLoadsOtherValue) {
  Shape shape;
  shape.none = 2;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchJavacFilterNegativeTest,
       ShouldNotFilterLoadsIntoOtherRegisters) {
  Shape shape;
  shape.none_register = 1;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchJavacFilterNegativeTest,
       ShouldNotFilterSecondSwitchOnOtherRegister) {
  Shape shape;
  shape.second_register = 1;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchJavacFilterNegativeTest,
       ShouldNotFilterLoadsThatGoToOtherSwitches) {
  Shape shape;
  shape.other_switch = true;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchJavacFilterNegativeTest,
       ShouldNotFilterCaseThatDoesntLoadIndex) {
  Shape shape;
  shape.add_instead_of_load = true;
  ExpectNotFiltered(shape);
}

TEST_F(StringSwitchJavacFilterNegativeTest,
       ShouldNotFilterLoadsThatGoToReturn) {
  // Like a `when` on one line whose value the method returns.
  Shape shape;
  shape.returns = true;
  ExpectNotFiltered(shape);
}

// Builds javac's code for `switch (s) { case "a": return 1; default: return
// 2; }`, whose default code goes right to the second switch on v1, into which
// D8 loads -1 before the code that the test says.
class StringSwitchJavacFilterMinusOneTest : public StringSwitchJavacFilterTest {
 protected:
  enum class Before {
    // Code that branches, whose paths meet again before the switch.
    kJoin,
    // The same, but one of the paths loads 2 into v1.
    kJoinWithOtherValue,
    // The start of a loop, which the code of "a" goes back to.
    kLoop,
    // The same, with a sparse-switch as the second switch.
    kSparseLoop,
    // A try block, whose handler starts with the switch.
    kTryBlock,
    // Nothing, but the comparison with "a" writes its result into v1.
    kComparisonIntoIndex,
    // Nothing, and the load of -1 is missing too.
    kNoLoad,
    // A branch to the switch, and code that loads 2 into v1 and returns.
    kReturn,
    // A loop that runs before the switch, without writing v1.
    kLoopBefore,
    // A move into v1 from another register.
    kMoveIntoIndex,
    // A try block, whose handler of Exception starts with the switch.
    kTypedTryBlock,
    // A store of v1 into a field, which only reads it.
    kStore,
  };

  // What the filter ignores when it filters the code.
  struct Parts {
    lir::Instruction* first_switch;
    lir::Instruction* jump;
    Range compare_a;
    Range load_a;
  };

  static Parts Build(TestMethod& m, Before before) {
    Parts parts = {};
    lir::Label* loop = m.NewLabel();
    lir::Label* start = m.NewLabel();
    lir::Label* hashes = m.NewLabel();
    lir::Label* indices = m.NewLabel();
    lir::Label* hash_of_a = m.NewLabel();
    lir::Label* second = m.NewLabel();
    lir::Label* code_of_a = m.NewLabel();
    m.AddLine(kSwitchLine);  // The start line.
    m.AddLine(kSwitchLine);
    if (before != Before::kNoLoad) {
      m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(static_cast<dex::u4>(-1))});
    }
    switch (before) {
      case Before::kJoin:
      case Before::kJoinWithOtherValue:
        m.Add(dex::OP_IF_EQZ, {m.Reg(3), m.Target(start)});
        m.Add(dex::OP_CONST_4,
              {m.Reg(before == Before::kJoin ? 3 : 1), m.Literal(2)});
        break;
      case Before::kTryBlock:
      case Before::kTypedTryBlock: {
        lir::TryBlockBegin* begin = m.AddTryBlockBegin();
        m.Add(dex::OP_INVOKE_STATIC,
              {m.Regs({}), m.MethodRef("LFoo;", "run", "()V")});
        m.AddTryBlockEnd(begin, start,
                         before == Before::kTypedTryBlock
                             ? "Ljava/lang/Exception;"
                             : nullptr);
        m.Add(dex::OP_RETURN, {m.Reg(1)});
        break;
      }
      case Before::kMoveIntoIndex:
        m.Add(dex::OP_MOVE, {m.Reg(1), m.Reg(3)});
        break;
      case Before::kStore:
        m.Add(dex::OP_SPUT, {m.Reg(1), m.FieldRef("LFoo;", "index", "I")});
        break;
      case Before::kReturn:
        m.Add(dex::OP_IF_EQZ, {m.Reg(3), m.Target(start)});
        m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(2)});
        m.Add(dex::OP_RETURN, {m.Reg(1)});
        break;
      case Before::kLoopBefore:
        m.Place(loop);
        m.Add(dex::OP_IF_EQZ, {m.Reg(3), m.Target(start)});
        m.Add(dex::OP_ADD_INT_LIT8, {m.Reg(3), m.Reg(3), m.Literal(1)});
        m.Add(dex::OP_GOTO, {m.Target(loop)});
        break;
      case Before::kLoop:
      case Before::kSparseLoop:
      case Before::kComparisonIntoIndex:
      case Before::kNoLoad:
        break;
    }
    m.Place(start);
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({2}), m.MethodRef(kString, "hashCode", "()I")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    parts.first_switch =
        m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
    parts.jump = m.Add(dex::OP_GOTO, {m.Target(second)});
    m.Place(hash_of_a);
    if (before == Before::kComparisonIntoIndex) {
      lir::Instruction* load =
          m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("a")});
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({2, 0}), m.MethodRef(kString, "equals", kEquals)});
      m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
      parts.compare_a = {load,
                         m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(second)})};
    } else {
      parts.compare_a = AddComparison(m, "a", dex::OP_IF_EQZ, second);
    }
    lir::Instruction* load = m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
    parts.load_a = {load, m.Add(dex::OP_GOTO, {m.Target(second)})};
    m.Place(second);
    m.Add(before == Before::kSparseLoop ? dex::OP_SPARSE_SWITCH
                                        : dex::OP_PACKED_SWITCH,
          {m.Reg(1), m.Target(indices)});
    AddReturn(m, 9, 2);
    m.Place(code_of_a);
    if (before == Before::kLoop || before == Before::kSparseLoop) {
      m.AddLine(7);
      m.Add(dex::OP_GOTO, {m.Target(start)});
    } else {
      AddReturn(m, 7, 1);
    }
    m.Place(hashes);
    m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});
    m.Place(indices);
    if (before == Before::kSparseLoop) {
      m.AddSparseSwitchPayload({{0, code_of_a}});
    } else {
      m.AddPackedSwitchPayload(0, {code_of_a});
    }
    return parts;
  }

  void ExpectNotFiltered(Before before) {
    TestMethod m("f", kSignature);
    Build(m, before);

    Filter(m);

    EXPECT_TRUE(output_.ignored().empty());
  }
};

TEST_F(StringSwitchJavacFilterMinusOneTest,
       ShouldFilterSwitchWithMinusOneLoadedBeforeBranches) {
  // D8 can load -1 once for several switches of a method.
  TestMethod m("f", kSignature);
  Parts parts = Build(m, Before::kJoin);

  Filter(m);

  ExpectIgnored(m, {{parts.first_switch, parts.first_switch},
                    {parts.jump, parts.jump},
                    parts.compare_a,
                    parts.load_a});
}

TEST_F(StringSwitchJavacFilterMinusOneTest,
       ShouldNotFilterSwitchWithOtherValueOnOnePath) {
  ExpectNotFiltered(Before::kJoinWithOtherValue);
}

TEST_F(StringSwitchJavacFilterMinusOneTest,
       ShouldNotFilterSwitchWithIndexFromLoop) {
  // The code of "a" goes back to the switch with 0 in v1.
  ExpectNotFiltered(Before::kLoop);
}

TEST_F(StringSwitchJavacFilterMinusOneTest,
       ShouldNotFilterSwitchWithIndexFromLoopOfSparseSwitch) {
  ExpectNotFiltered(Before::kSparseLoop);
}

TEST_F(StringSwitchJavacFilterMinusOneTest, ShouldNotFilterSwitchInHandler) {
  ExpectNotFiltered(Before::kTryBlock);
}

TEST_F(StringSwitchJavacFilterMinusOneTest,
       ShouldNotFilterSwitchWithComparisonIntoIndex) {
  // The default code goes on with the result of the comparison in v1.
  ExpectNotFiltered(Before::kComparisonIntoIndex);
}

TEST_F(StringSwitchJavacFilterMinusOneTest, ShouldFilterSwitchAfterReturn) {
  // The code that loads 2 into v1 returns instead of going on to the switch.
  TestMethod m("f", kSignature);
  Parts parts = Build(m, Before::kReturn);

  Filter(m);

  ExpectIgnored(m, {{parts.first_switch, parts.first_switch},
                    {parts.jump, parts.jump},
                    parts.compare_a,
                    parts.load_a});
}

TEST_F(StringSwitchJavacFilterMinusOneTest,
       ShouldNotFilterSwitchWithoutMinusOne) {
  // The default code goes on with the value of v1 at the start of the method.
  ExpectNotFiltered(Before::kNoLoad);
}

TEST_F(StringSwitchJavacFilterMinusOneTest, ShouldFilterSwitchAfterStore) {
  TestMethod m("f", kSignature);
  Parts parts = Build(m, Before::kStore);

  Filter(m);

  ExpectIgnored(m, {{parts.first_switch, parts.first_switch},
                    {parts.jump, parts.jump},
                    parts.compare_a,
                    parts.load_a});
}

TEST_F(StringSwitchJavacFilterMinusOneTest,
       ShouldNotFilterSwitchWithIndexMovedIntoIt) {
  ExpectNotFiltered(Before::kMoveIntoIndex);
}

TEST_F(StringSwitchJavacFilterMinusOneTest,
       ShouldNotFilterSwitchInHandlerOfException) {
  ExpectNotFiltered(Before::kTypedTryBlock);
}

TEST_F(StringSwitchJavacFilterMinusOneTest, ShouldFilterSwitchAfterLoop) {
  TestMethod m("f", kSignature);
  Parts parts = Build(m, Before::kLoopBefore);

  Filter(m);

  ExpectIgnored(m, {{parts.first_switch, parts.first_switch},
                    {parts.jump, parts.jump},
                    parts.compare_a,
                    parts.load_a});
}

TEST_F(StringSwitchJavacFilterTest, ShouldNotFilterWhenOnString) {
  // The code that Kotlin generates goes on with the code of each branch.
  TestMethod m("f", kSignature);
  lir::Label* hashes = m.NewLabel();
  lir::Label* hash_of_a = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddHashCode(m);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(hashes)});
  m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(hash_of_a);
  AddComparison(m, "a", dex::OP_IF_EQZ, otherwise);
  AddReturn(m, 6, 1);
  m.Place(otherwise);
  AddReturn(m, 7, 3);
  m.Place(hashes);
  m.AddPackedSwitchPayload(kHashOfA, {hash_of_a});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

}  // namespace
}  // namespace coverage
