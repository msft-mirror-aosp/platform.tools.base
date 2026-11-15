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

#include "tools/base/android-test/coverage/agent/native/exhaustive_switch_filter.h"

#include <gtest/gtest.h>

#include <cstring>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

namespace coverage {
namespace {

constexpr char kMatchException[] = "Ljava/lang/MatchException;";
constexpr char kRuntimeException[] = "Ljava/lang/RuntimeException;";
constexpr char kIncompatibleClassChangeError[] =
    "Ljava/lang/IncompatibleClassChangeError;";
constexpr char kMessageAndCause[] =
    "(Ljava/lang/String;Ljava/lang/Throwable;)V";
constexpr char kColor[] = "Lcom/example/Color;";
// The signature of static int f(Color c).
constexpr char kSignature[] = "(Lcom/example/Color;)I";
// The line of the switch.
constexpr int kSwitchLine = 7;

// Unit tests for ExhaustiveSwitchFilter, on the code that javac and D8
// generate for a switch expression that covers every constant of
//   enum Color { RED, GREEN }
// such as
//   return switch (c) {
//     case RED -> 1;
//     case GREEN -> 2;
//   };
// with the enum in v2.
class ExhaustiveSwitchFilterTest : public FilterTestBase {
 protected:
  // Appends the switch on the ordinal of the enum in v2, on the line of the
  // switch, with the payload at `cases`.
  static lir::Instruction* AddSwitch(
      TestMethod& m, lir::Label* cases,
      dex::Opcode opcode = dex::OP_PACKED_SWITCH) {
    m.AddLine(kSwitchLine);  // The start line.
    m.AddLine(kSwitchLine);
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({2}), m.MethodRef(kColor, "ordinal", "()I")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    return m.Add(opcode, {m.Reg(0), m.Target(cases)});
  }

  // Appends the code that throws `exception` from v0, with two nulls in v1 for
  // MatchException and RuntimeException.
  static Range AddThrow(TestMethod& m, const char* exception) {
    lir::Instruction* created =
        m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(exception)});
    if (strcmp(exception, kIncompatibleClassChangeError) == 0) {
      m.Add(dex::OP_INVOKE_DIRECT,
            {m.Regs({0}), m.MethodRef(exception, "<init>", "()V")});
    } else {
      m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
      m.Add(dex::OP_INVOKE_DIRECT,
            {m.Regs({0, 1, 1}),
             m.MethodRef(exception, "<init>", kMessageAndCause)});
    }
    return {created, m.Add(dex::OP_THROW, {m.Reg(0)})};
  }

  // Appends `return <value>` on line `line`, with the value in v0, and
  // returns its first bytecode.
  static lir::Instruction* AddReturn(TestMethod& m, int line, dex::u4 value) {
    m.AddLine(line);
    lir::Instruction* load =
        m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(value)});
    m.Add(dex::OP_RETURN, {m.Reg(0)});
    return load;
  }

  void Filter(TestMethod& m) {
    ExhaustiveSwitchFilter().Filter(context_, m.ir_method(), m.code_ir(),
                                    output_);
  }
};

TEST_F(ExhaustiveSwitchFilterTest,
       ShouldFilterDefaultThatThrowsRuntimeException) {
  // D8 replaces MatchException with RuntimeException.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  Range thrown = AddThrow(m, kRuntimeException);
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {thrown});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest,
       ShouldFilterDefaultThatThrowsMatchException) {
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  Range thrown = AddThrow(m, kMatchException);
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {thrown});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest,
       ShouldFilterDefaultThatThrowsIncompatibleClassChangeError) {
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  Range thrown = AddThrow(m, kIncompatibleClassChangeError);
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {thrown});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterDefaultWithNullLoadedEarlier) {
  // D8 loads null into v1 before the switch, because the method uses it
  // elsewhere, or before the new-instance.
  for (bool before_new : {false, true}) {
    SCOPED_TRACE(before_new ? "before the new-instance" : "earlier");
    TestMethod m("f", kSignature);
    lir::Label* cases = m.NewLabel();
    lir::Label* red = m.NewLabel();
    lir::Label* green = m.NewLabel();
    m.AddLine(kSwitchLine);  // The start line.
    m.AddLine(kSwitchLine);
    m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({2}), m.MethodRef(kColor, "ordinal", "()I")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    lir::Instruction* switch_bytecode =
        m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
    lir::Instruction* first = nullptr;
    if (before_new) {
      first = m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
    }
    lir::Instruction* created =
        m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({0, 1, 1}),
           m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
    lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(0)});
    m.Place(green);
    lir::Instruction* code_of_green = AddReturn(m, 9, 2);
    m.Place(red);
    lir::Instruction* code_of_red = AddReturn(m, 8, 1);
    m.Place(cases);
    m.AddPackedSwitchPayload(0, {red, green});
    output_ = Output();

    Filter(m);

    ExpectIgnored(m, {{before_new ? first : created, thrown}});
    ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
  }
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterDefaultThatStartsWithMoves) {
  // D8 can start the default code with moves, right after the switch or at
  // the target of the goto after it.
  for (bool through_goto : {false, true}) {
    SCOPED_TRACE(through_goto ? "through a goto" : "after the switch");
    TestMethod m("f", kSignature);
    lir::Label* cases = m.NewLabel();
    lir::Label* red = m.NewLabel();
    lir::Label* green = m.NewLabel();
    lir::Label* otherwise = m.NewLabel();
    lir::Instruction* switch_bytecode = AddSwitch(m, cases);
    lir::Instruction* jump = nullptr;
    lir::Instruction* code_of_red = nullptr;
    lir::Instruction* code_of_green = nullptr;
    if (through_goto) {
      jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
      m.Place(green);
      code_of_green = AddReturn(m, 9, 2);
      m.Place(red);
      code_of_red = AddReturn(m, 8, 1);
      m.Place(otherwise);
      m.AddLine(kSwitchLine);
    }
    lir::Instruction* move = m.Add(dex::OP_MOVE_OBJECT, {m.Reg(3), m.Reg(2)});
    m.Add(dex::OP_MOVE, {m.Reg(4), m.Reg(0)});
    Range thrown = AddThrow(m, kRuntimeException);
    if (!through_goto) {
      m.Place(green);
      code_of_green = AddReturn(m, 9, 2);
      m.Place(red);
      code_of_red = AddReturn(m, 8, 1);
    }
    m.Place(cases);
    m.AddPackedSwitchPayload(0, {red, green});
    output_ = Output();

    Filter(m);

    if (through_goto) {
      ExpectIgnored(m, {{jump, jump}, {move, thrown.to}});
    } else {
      ExpectIgnored(m, {{move, thrown.to}});
    }
    ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
  }
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterDefaultReachedThroughGoto) {
  // The default code is at the end of the method, on the line of the switch.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(otherwise);
  m.AddLine(kSwitchLine);
  Range thrown = AddThrow(m, kRuntimeException);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {{jump, jump}, thrown});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterDefaultThatInheritsSwitchLine) {
  // The default code at the end of the method has no line of its own, and
  // follows the return on the line of the switch that the cases go to.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* join = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(green);
  m.AddLine(9);
  lir::Instruction* code_of_green =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(2)});
  m.Add(dex::OP_GOTO, {m.Target(join)});
  m.Place(red);
  m.AddLine(8);
  lir::Instruction* code_of_red =
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Place(join);
  m.AddLine(kSwitchLine);
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  m.Place(otherwise);
  Range thrown = AddThrow(m, kRuntimeException);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {{jump, jump}, thrown});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterDefaultWithRangeCall) {
  // With the exception in v16, D8 passes two registers that it loads with
  // null, since a range can't repeat a register.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  lir::Instruction* created =
      m.Add(dex::OP_NEW_INSTANCE, {m.Reg(16), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_CONST_16, {m.Reg(17), m.Literal(0)});
  m.Add(dex::OP_CONST_16, {m.Reg(18), m.Literal(0)});
  m.Add(dex::OP_INVOKE_DIRECT_RANGE,
        {m.RegRange(16, 3),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(16)});
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {{created, thrown}});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterRangeCallWithNullsLoadedFirst) {
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  lir::Instruction* first = m.Add(dex::OP_CONST_16, {m.Reg(17), m.Literal(0)});
  m.Add(dex::OP_CONST_16, {m.Reg(18), m.Literal(0)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(16), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_INVOKE_DIRECT_RANGE,
        {m.RegRange(16, 3),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(16)});
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {{first, thrown}});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest,
       ShouldFilterIncompatibleClassChangeErrorWithRangeCall) {
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  lir::Instruction* created =
      m.Add(dex::OP_NEW_INSTANCE,
            {m.Reg(16), m.TypeRef(kIncompatibleClassChangeError)});
  m.Add(dex::OP_INVOKE_DIRECT_RANGE,
        {m.RegRange(16, 1),
         m.MethodRef(kIncompatibleClassChangeError, "<init>", "()V")});
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(16)});
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {{created, thrown}});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterDefaultOfSparseSwitch) {
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode =
      AddSwitch(m, cases, dex::OP_SPARSE_SWITCH);
  Range thrown = AddThrow(m, kRuntimeException);
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddSparseSwitchPayload({{0, red}, {5, green}});

  Filter(m);

  ExpectIgnored(m, {thrown});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldCountCasesThatShareCodeOnce) {
  // case RED, BLUE -> 1;
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  Range thrown = AddThrow(m, kRuntimeException);
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green, red});

  Filter(m);

  ExpectIgnored(m, {thrown});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldNotAddBranchForCasesOfDefaultCode) {
  // The cases of ordinals 1 and 3 go to the default code, the first to the
  // goto after the switch and the second to the code that throws.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* after_switch = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  m.Place(after_switch);
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(otherwise);
  m.AddLine(kSwitchLine);
  Range thrown = AddThrow(m, kRuntimeException);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, after_switch, green, otherwise});

  Filter(m);

  ExpectIgnored(m, {{jump, jump}, thrown});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterGotoOfLargeMethod) {
  for (dex::Opcode opcode : {dex::OP_GOTO_16, dex::OP_GOTO_32}) {
    SCOPED_TRACE(opcode == dex::OP_GOTO_16 ? "goto/16" : "goto/32");
    TestMethod m("f", kSignature);
    lir::Label* cases = m.NewLabel();
    lir::Label* red = m.NewLabel();
    lir::Label* green = m.NewLabel();
    lir::Label* otherwise = m.NewLabel();
    lir::Instruction* switch_bytecode = AddSwitch(m, cases);
    lir::Instruction* jump = m.Add(opcode, {m.Target(otherwise)});
    m.Place(green);
    lir::Instruction* code_of_green = AddReturn(m, 9, 2);
    m.Place(red);
    lir::Instruction* code_of_red = AddReturn(m, 8, 1);
    m.Place(otherwise);
    m.AddLine(kSwitchLine);
    Range thrown = AddThrow(m, kRuntimeException);
    m.Place(cases);
    m.AddPackedSwitchPayload(0, {red, green});
    output_ = Output();

    Filter(m);

    ExpectIgnored(m, {{jump, jump}, thrown});
    ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
  }
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterDefaultWithMoveOfOtherValue) {
  // In a try block whose finally keeps longs in all the low registers, D8
  // splits the default code with a goto and moves a long out of v1 before it
  // loads null there.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* rest = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  lir::Instruction* created =
      m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
  lir::Instruction* to_rest = m.Add(dex::OP_GOTO, {m.Target(rest)});
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(rest);
  m.AddLine(kSwitchLine);
  lir::Instruction* move =
      m.Add(dex::OP_MOVE_WIDE_FROM16, {m.code_ir().Alloc<lir::VRegPair>(17),
                                       m.code_ir().Alloc<lir::VRegPair>(1)});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 1, 1}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(0)});
  m.AddTryBlockEnd(begin, handler);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_THROW, {m.Reg(0)});
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {{created, to_rest}, {move, thrown}});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldNotFilterDefaultWithMoveOverNull) {
  // A move overwrites the null in v1.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  AddSwitch(m, cases);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(2)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 1, 1}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(0)});
  m.Place(green);
  AddReturn(m, 9, 2);
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldNotFilterDefaultWithWideMoveOverNull) {
  // The second register of a move-wide into v1 is v2, which holds the null.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  AddSwitch(m, cases);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(0)});
  m.Add(dex::OP_MOVE_WIDE, {m.code_ir().Alloc<lir::VRegPair>(1),
                            m.code_ir().Alloc<lir::VRegPair>(4)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 2, 2}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(0)});
  m.Place(green);
  AddReturn(m, 9, 2);
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(ExhaustiveSwitchFilterTest,
       ShouldNotFilterDefaultWithMoveOverException) {
  // A move overwrites the exception in v0.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  AddSwitch(m, cases);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(2)});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 1, 1}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(0)});
  m.Place(green);
  AddReturn(m, 9, 2);
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldFilterDefaultSplitByGotos) {
  // Inside a try block, D8 can put gotos between the instructions that throw.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* call = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  lir::Instruction* created =
      m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  lir::Instruction* to_call = m.Add(dex::OP_GOTO, {m.Target(call)});
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(call);
  m.AddLine(kSwitchLine);
  lir::Instruction* constructor =
      m.Add(dex::OP_INVOKE_DIRECT,
            {m.Regs({0, 1, 1}),
             m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(red);
  lir::Instruction* code_of_red = AddReturn(m, 8, 1);
  m.Place(rethrow);
  m.AddLine(kSwitchLine);
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(0)});
  m.AddTryBlockEnd(begin, handler, "Ljava/lang/IllegalStateException;");
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  AddReturn(m, 11, 0);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(
      m, {{created, to_call}, {constructor, to_throw}, {thrown, thrown}});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldReplaceBranchesWithCodeOfCases) {
  // The code of RED starts a try block, which comes between its label and its
  // first bytecode.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  Range thrown = AddThrow(m, kRuntimeException);
  m.Place(green);
  lir::Instruction* code_of_green = AddReturn(m, 9, 2);
  m.Place(red);
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  m.AddLine(8);
  lir::Instruction* code_of_red = m.Add(
      dex::OP_INVOKE_STATIC, {m.Regs({}), m.MethodRef(kColor, "f", "()V")});
  m.AddTryBlockEnd(begin, handler);
  AddReturn(m, 8, 1);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  AddReturn(m, 8, 0);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  ExpectIgnored(m, {thrown});
  ExpectReplaced(m, {{switch_bytecode, {code_of_red, code_of_green}}});
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldRunOnJavaClasses) {
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  Range thrown = AddThrow(m, kRuntimeException);
  m.Place(green);
  AddReturn(m, 9, 2);
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(thrown.to));
  const std::vector<lir::Instruction*>* targets =
      result.GetReplacedBranches(switch_bytecode);
  ASSERT_NE(targets, nullptr);
  EXPECT_EQ(targets->size(), 2u);
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldRunOnKotlinClasses) {
  context_.class_annotations.insert(kKotlinMetadataDescriptor);
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Instruction* switch_bytecode = AddSwitch(m, cases);
  Range thrown = AddThrow(m, kMatchException);
  m.Place(green);
  AddReturn(m, 9, 2);
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(thrown.to));
  const std::vector<lir::Instruction*>* targets =
      result.GetReplacedBranches(switch_bytecode);
  ASSERT_NE(targets, nullptr);
  EXPECT_EQ(targets->size(), 2u);
}

TEST_F(ExhaustiveSwitchFilterTest,
       ShouldNotFilterSwitchWhoseCasesAllGoToDefault) {
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* after_switch = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddSwitch(m, cases);
  m.Place(after_switch);
  m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(otherwise);
  AddThrow(m, kRuntimeException);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {after_switch, otherwise});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldNotFilterDefaultThatThrowsWithoutNew) {
  // The default code throws an exception that it didn't create.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  AddSwitch(m, cases);
  m.Add(dex::OP_THROW, {m.Reg(2)});
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

// Builds the code of the switch expression on Color, changed as the test says,
// with the default code right after the switch and behind a goto, and expects
// the filter to leave both alone.
class ExhaustiveSwitchFilterNegativeTest : public ExhaustiveSwitchFilterTest {
 protected:
  // How the code differs from javac's and D8's.
  struct Shape {
    // The exception that the default code throws.
    const char* exception = kRuntimeException;
    // The signature of its constructor.
    const char* signature = kMessageAndCause;
    // Whether the default code loads null into a register, and whether it
    // does so before the new-instance rather than after it.
    bool loads_null = true;
    bool null_before_new = false;
    // The register that the default code loads a value into, and the value.
    dex::u4 null_register = 1;
    dex::u4 null_value = 0;
    // The register that the constructor is called on, and the registers of
    // the message and of the cause. An empty `message` calls the constructor
    // without them.
    dex::u4 receiver = 0;
    std::vector<dex::u4> arguments = {1, 1};
    // The opcode and the register that end the default code.
    dex::Opcode end = dex::OP_THROW;
    dex::u4 thrown = 0;
    // The line of the default code, and whether the line starts at the
    // new-instance instead of the start of the default code.
    int line = kSwitchLine;
    bool line_at_new = false;
    // Whether the default code calls a method first.
    bool call_first = false;
  };

  // The parts of the code that the filter changes.
  struct Parts {
    lir::Instruction* switch_bytecode;
    lir::Instruction* jump;
    Range thrown;
    lir::Instruction* code_of_red;
    lir::Instruction* code_of_green;
  };

  // Appends the code laid out as `shape` to `m`, with the default code behind
  // a goto if `through_goto`, and returns its parts.
  static Parts Build(TestMethod& m, const Shape& shape, bool through_goto) {
    Parts parts = {};
    lir::Label* cases = m.NewLabel();
    lir::Label* red = m.NewLabel();
    lir::Label* green = m.NewLabel();
    lir::Label* otherwise = m.NewLabel();
    parts.switch_bytecode = AddSwitch(m, cases);
    if (through_goto) {
      parts.jump = m.Add(dex::OP_GOTO, {m.Target(otherwise)});
      m.Place(green);
      parts.code_of_green = AddReturn(m, 9, 2);
      m.Place(red);
      parts.code_of_red = AddReturn(m, 8, 1);
      m.Place(otherwise);
      if (!shape.line_at_new) {
        m.AddLine(shape.line);
      }
    } else if (shape.line != kSwitchLine && !shape.line_at_new) {
      m.AddLine(shape.line);
    }
    lir::Instruction* first = nullptr;
    if (shape.call_first) {
      first =
          m.Add(dex::OP_INVOKE_STATIC,
                {m.Regs({}), m.MethodRef("Lcom/example/Log;", "log", "()V")});
    }
    lir::Instruction* load = nullptr;
    if (shape.loads_null && shape.null_before_new) {
      load = m.Add(dex::OP_CONST_4,
                   {m.Reg(shape.null_register), m.Literal(shape.null_value)});
    }
    if (shape.line_at_new) {
      m.AddLine(shape.line);
    }
    lir::Instruction* created =
        m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(shape.exception)});
    if (shape.loads_null && !shape.null_before_new) {
      m.Add(dex::OP_CONST_4,
            {m.Reg(shape.null_register), m.Literal(shape.null_value)});
    }
    std::vector<dex::u4> arguments = {shape.receiver};
    arguments.insert(arguments.end(), shape.arguments.begin(),
                     shape.arguments.end());
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs(arguments),
           m.MethodRef(shape.exception, "<init>", shape.signature)});
    lir::Instruction* last = m.Add(shape.end, {m.Reg(shape.thrown)});
    parts.thrown = {first != nullptr ? first : load != nullptr ? load : created,
                    last};
    if (!through_goto) {
      m.Place(green);
      parts.code_of_green = AddReturn(m, 9, 2);
      m.Place(red);
      parts.code_of_red = AddReturn(m, 8, 1);
    }
    m.Place(cases);
    m.AddPackedSwitchPayload(0, {red, green});
    return parts;
  }

  void ExpectNotFiltered(const Shape& shape) {
    for (bool through_goto : {false, true}) {
      SCOPED_TRACE(through_goto ? "through a goto" : "after the switch");
      TestMethod m("f", kSignature);
      Build(m, shape, through_goto);
      output_ = Output();

      Filter(m);

      EXPECT_TRUE(output_.ignored().empty());
    }
  }
};

TEST_F(ExhaustiveSwitchFilterNegativeTest, ShouldFilterUnchangedShape) {
  // The code of these tests, unchanged, is filtered.
  for (bool through_goto : {false, true}) {
    SCOPED_TRACE(through_goto ? "through a goto" : "after the switch");
    TestMethod m("f", kSignature);
    Parts parts = Build(m, Shape(), through_goto);
    output_ = Output();

    Filter(m);

    if (through_goto) {
      ExpectIgnored(m, {{parts.jump, parts.jump}, parts.thrown});
    } else {
      ExpectIgnored(m, {parts.thrown});
    }
    ExpectReplaced(
        m, {{parts.switch_bytecode, {parts.code_of_red, parts.code_of_green}}});
  }
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldFilterUnchangedIncompatibleClassChangeError) {
  // The code of the tests of IncompatibleClassChangeError, unchanged, is
  // filtered.
  Shape shape;
  shape.exception = kIncompatibleClassChangeError;
  shape.signature = "()V";
  shape.loads_null = false;
  shape.arguments = {};
  for (bool through_goto : {false, true}) {
    SCOPED_TRACE(through_goto ? "through a goto" : "after the switch");
    TestMethod m("f", kSignature);
    Parts parts = Build(m, shape, through_goto);
    output_ = Output();

    Filter(m);

    if (through_goto) {
      ExpectIgnored(m, {{parts.jump, parts.jump}, parts.thrown});
    } else {
      ExpectIgnored(m, {parts.thrown});
    }
    ExpectReplaced(
        m, {{parts.switch_bytecode, {parts.code_of_red, parts.code_of_green}}});
  }
}

TEST_F(ExhaustiveSwitchFilterNegativeTest, ShouldNotFilterOtherException) {
  Shape shape;
  shape.exception = "Ljava/lang/IllegalStateException;";
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest, ShouldNotFilterOtherConstructor) {
  // new RuntimeException(message, cause, true, true).
  Shape shape;
  shape.signature = "(Ljava/lang/String;Ljava/lang/Throwable;ZZ)V";
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest, ShouldNotFilterOtherMessage) {
  Shape shape;
  shape.arguments = {2, 1};
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest, ShouldNotFilterOtherCause) {
  Shape shape;
  shape.arguments = {1, 2};
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest, ShouldNotFilterMessageThatIsntNull) {
  Shape shape;
  shape.null_value = 1;
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldNotFilterNullLoadedIntoOtherRegister) {
  // The message and the cause are in v1, which another null doesn't load.
  Shape shape;
  shape.null_register = 3;
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldNotFilterExceptionPassedAsMessageAndCause) {
  // The null goes into the register of the exception.
  Shape shape;
  shape.null_register = 0;
  shape.arguments = {0, 0};
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldNotFilterConstructorCalledOnOtherRegister) {
  Shape shape;
  shape.receiver = 3;
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldNotFilterIncompatibleClassChangeErrorAfterNull) {
  Shape shape;
  shape.exception = kIncompatibleClassChangeError;
  shape.signature = "()V";
  shape.arguments = {};
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldNotFilterIncompatibleClassChangeErrorOfOtherRegister) {
  Shape shape;
  shape.exception = kIncompatibleClassChangeError;
  shape.signature = "()V";
  shape.loads_null = false;
  shape.receiver = 3;
  shape.arguments = {};
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest, ShouldNotFilterReturnedException) {
  Shape shape;
  shape.end = dex::OP_RETURN_OBJECT;
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldNotFilterThrowOfOtherRegister) {
  Shape shape;
  shape.thrown = 1;
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldNotFilterDefaultCodeOnOtherLine) {
  // default -> throw new RuntimeException(null, null) on a line of its own.
  Shape shape;
  shape.line = 10;
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldNotFilterDefaultCodeWhoseLineStartsAfterNull) {
  // The same, when the null that D8 loads first has no line of its own.
  Shape shape;
  shape.null_before_new = true;
  shape.line = 10;
  shape.line_at_new = true;
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterNegativeTest,
       ShouldNotFilterDefaultCodeThatCallsFirst) {
  Shape shape;
  shape.call_first = true;
  ExpectNotFiltered(shape);
}

TEST_F(ExhaustiveSwitchFilterTest,
       ShouldNotFilterDefaultThatInheritsOtherLine) {
  // The default code at the end of the method has no line of its own, and
  // follows code on line 8.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddSwitch(m, cases);
  m.Add(dex::OP_GOTO, {m.Target(otherwise)});
  m.Place(green);
  AddReturn(m, 9, 2);
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(otherwise);
  AddThrow(m, kRuntimeException);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldNotFilterDefaultWithPartOnOtherLine) {
  // Gotos split the default code into three parts, one of which is on line 10.
  for (int part = 0; part < 3; ++part) {
    SCOPED_TRACE(part);
    TestMethod m("f", kSignature);
    lir::Label* cases = m.NewLabel();
    lir::Label* red = m.NewLabel();
    lir::Label* green = m.NewLabel();
    lir::Label* call = m.NewLabel();
    lir::Label* rethrow = m.NewLabel();
    AddSwitch(m, cases);
    m.AddLine(part == 0 ? 10 : kSwitchLine);
    m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
    m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
    m.Add(dex::OP_GOTO, {m.Target(call)});
    m.Place(green);
    AddReturn(m, 9, 2);
    m.Place(call);
    m.AddLine(part == 1 ? 10 : kSwitchLine);
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({0, 1, 1}),
           m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
    m.Add(dex::OP_GOTO, {m.Target(rethrow)});
    m.Place(red);
    AddReturn(m, 8, 1);
    m.Place(rethrow);
    m.AddLine(part == 2 ? 10 : kSwitchLine);
    m.Add(dex::OP_THROW, {m.Reg(0)});
    m.Place(cases);
    m.AddPackedSwitchPayload(0, {red, green});
    output_ = Output();

    Filter(m);

    EXPECT_TRUE(output_.ignored().empty());
  }
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldNotFilterExceptionPassedAsCause) {
  // The null loaded into the register of the exception, after the null of
  // the message, makes it look like a null cause.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  AddSwitch(m, cases);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 1, 0}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(0)});
  m.Place(green);
  AddReturn(m, 9, 2);
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldNotFilterExceptionPassedAsMessage) {
  // The same with the message.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  AddSwitch(m, cases);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 0, 1}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(0)});
  m.Place(green);
  AddReturn(m, 9, 2);
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

TEST_F(ExhaustiveSwitchFilterTest, ShouldNotFilterDefaultThatLoops) {
  // default: for (;;) { a = b; } or for (;;) { a = null; }, right after the
  // switch or behind a goto.
  for (dex::Opcode opcode : {dex::OP_MOVE_OBJECT, dex::OP_CONST_4}) {
    for (bool through_goto : {false, true}) {
      SCOPED_TRACE(opcode == dex::OP_MOVE_OBJECT ? "moves" : "nulls");
      SCOPED_TRACE(through_goto ? "through a goto" : "after the switch");
      TestMethod m("f", kSignature);
      lir::Label* cases = m.NewLabel();
      lir::Label* red = m.NewLabel();
      lir::Label* green = m.NewLabel();
      lir::Label* loop = m.NewLabel();
      AddSwitch(m, cases);
      if (through_goto) {
        m.Add(dex::OP_GOTO, {m.Target(loop)});
        m.Place(green);
        AddReturn(m, 9, 2);
        m.Place(red);
        AddReturn(m, 8, 1);
      }
      m.Place(loop);
      m.AddLine(kSwitchLine);
      if (opcode == dex::OP_MOVE_OBJECT) {
        m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(2)});
      } else {
        m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
      }
      m.Add(dex::OP_GOTO, {m.Target(loop)});
      if (!through_goto) {
        m.Place(green);
        AddReturn(m, 9, 2);
        m.Place(red);
        AddReturn(m, 8, 1);
      }
      m.Place(cases);
      m.AddPackedSwitchPayload(0, {red, green});
      output_ = Output();

      Filter(m);

      EXPECT_TRUE(output_.ignored().empty());
    }
  }
}

TEST_F(ExhaustiveSwitchFilterTest,
       ShouldNotFilterRangeCallWithArgumentThatIsntNull) {
  // The message in v17 isn't loaded with null.
  TestMethod m("f", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  AddSwitch(m, cases);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(16), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_CONST_16, {m.Reg(18), m.Literal(0)});
  m.Add(dex::OP_INVOKE_DIRECT_RANGE,
        {m.RegRange(16, 3),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(16)});
  m.Place(green);
  AddReturn(m, 9, 2);
  m.Place(red);
  AddReturn(m, 8, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {red, green});

  Filter(m);

  EXPECT_TRUE(output_.ignored().empty());
}

}  // namespace
}  // namespace coverage
