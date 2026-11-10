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

#include "tools/base/android-test/coverage/agent/native/kotlin_default_arguments_filter.h"

#include <string>

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr dex::u4 kStaticSynthetic = dex::kAccStatic | dex::kAccSynthetic;
constexpr char kError[] = "Ljava/lang/UnsupportedOperationException;";

// Unit tests for KotlinDefaultArgumentsFilter.
class KotlinDefaultArgumentsFilterTest : public FilterTestBase {
 protected:
  // Gives `m` the access flags `access`, and `registers` registers whose last
  // `ins` hold the parameters.
  static void SetFrame(TestMethod& m, dex::u4 access, dex::u2 registers,
                       dex::u2 ins) {
    m.ir_method()->access_flags = access;
    m.ir_method()->code->registers = registers;
    m.ir_method()->code->ins_count = ins;
  }

  // Appends the code of greet$default for
  //   fun greet(name: String = "World") = "Hello $name"
  // with the parameters in v0 to v2, and returns the jump that checks whether
  // to use the default value.
  static lir::Instruction* AddGreet(TestMethod& m) {
    lir::Label* call = m.NewLabel();
    m.Add(dex::OP_AND_INT_LIT8, {m.Reg(1), m.Reg(1), m.Literal(1)});
    m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(call)});
    lir::Instruction* check = m.Last();
    m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("World")});
    m.Place(call);
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({0}), m.MethodRef(kTestClassName, "greet",
                                    "(Ljava/lang/String;)Ljava/lang/String;")});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
    return check;
  }

  KotlinDefaultArgumentsFilter filter_;
};

TEST_F(KotlinDefaultArgumentsFilterTest, ShouldFilterDefaultArgumentsMethod) {
  TestMethod m("greet$default",
               "(Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;");
  SetFrame(m, kStaticSynthetic, /*registers=*/3, /*ins=*/3);
  lir::Instruction* check = AddGreet(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{check, check}});
}

TEST_F(KotlinDefaultArgumentsFilterTest, ShouldNotFilterMethodWithoutSuffix) {
  TestMethod m("greet",
               "(Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;");
  SetFrame(m, kStaticSynthetic, /*registers=*/3, /*ins=*/3);
  AddGreet(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinDefaultArgumentsFilterTest, ShouldNotFilterMethodNotSynthetic) {
  TestMethod m("greet$default",
               "(Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;");
  SetFrame(m, dex::kAccStatic, /*registers=*/3, /*ins=*/3);
  AddGreet(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// open fun foo(x: Int = 1) = x
// D8 moves the code that rejects super calls to the end.
TEST_F(KotlinDefaultArgumentsFilterTest, ShouldFilterOpenFunction) {
  TestMethod m("foo$default", "(LFoo;IILjava/lang/Object;)I");
  SetFrame(m, kStaticSynthetic, /*registers=*/4, /*ins=*/4);
  lir::Label* error = m.NewLabel();
  lir::Label* call = m.NewLabel();
  m.Add(dex::OP_IF_NEZ, {m.Reg(3), m.Target(error)});
  lir::Instruction* super_calls_check = m.Last();
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(2), m.Reg(2), m.Literal(1)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(2), m.Target(call)});
  lir::Instruction* check = m.Last();
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  m.Place(call);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0, 1}), m.MethodRef(kTestClassName, "foo", "(I)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  m.Place(error);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kError)});
  lir::Instruction* error_from = m.Last();
  m.Add(dex::OP_CONST_STRING,
        {m.Reg(1), m.StringRef("Super calls with default arguments not "
                               "supported in this target, function: foo")});
  m.Add(
      dex::OP_INVOKE_DIRECT,
      {m.Regs({0, 1}), m.MethodRef(kError, "<init>", "(Ljava/lang/String;)V")});
  m.Add(dex::OP_THROW, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{super_calls_check, super_calls_check},
                    Range{error_from, m.Last()}, Range{check, check}});
}

// class Point(val x: Int = 0, val y: Int = 0)
// D8 loads the default value 0 between the first check and its jump.
TEST_F(KotlinDefaultArgumentsFilterTest, ShouldFilterConstructor) {
  TestMethod m("<init>",
               "(IIILkotlin/jvm/internal/DefaultConstructorMarker;)V");
  SetFrame(m, dex::kAccSynthetic, /*registers=*/6, /*ins=*/5);
  lir::Label* second = m.NewLabel();
  lir::Label* call = m.NewLabel();
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(5), m.Reg(4), m.Literal(1)});
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(5), m.Target(second)});
  lir::Instruction* check_x = m.Last();
  m.Add(dex::OP_MOVE, {m.Reg(2), m.Reg(0)});
  m.Place(second);
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(4), m.Reg(4), m.Literal(2)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(4), m.Target(call)});
  lir::Instruction* check_y = m.Last();
  m.Add(dex::OP_MOVE, {m.Reg(3), m.Reg(0)});
  m.Place(call);
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 2, 3}), m.MethodRef(kTestClassName, "<init>", "(II)V")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{check_x, check_x}, Range{check_y, check_y}});
}

// data class C(val x: Long = 42)
TEST_F(KotlinDefaultArgumentsFilterTest, ShouldFilterParametersInTwoRegisters) {
  TestMethod m("<init>", "(JILkotlin/jvm/internal/DefaultConstructorMarker;)V");
  SetFrame(m, dex::kAccSynthetic, /*registers=*/5, /*ins=*/5);
  lir::Label* call = m.NewLabel();
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(3), m.Reg(3), m.Literal(1)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(3), m.Target(call)});
  lir::Instruction* check = m.Last();
  m.Add(dex::OP_CONST_WIDE_16, {m.code_ir().Alloc<lir::VRegPair>(1),
                                m.code_ir().Alloc<lir::Const64>(42)});
  m.Place(call);
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 1, 2}), m.MethodRef(kTestClassName, "<init>", "(J)V")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{check, check}});
}

// class C(p1: Int, ..., p31: Int, p32: Int = 42, p33: Int = 42)
// The 33rd parameter has a bit in a second mask. D8 copies the first mask to
// another register, and loads its last bit into a register.
TEST_F(KotlinDefaultArgumentsFilterTest,
       ShouldFilterMethodsWithMoreThan32Parameters) {
  const std::string ints(33, 'I');
  TestMethod m("<init>", ("(" + ints +
                          "IILkotlin/jvm/internal/DefaultConstructorMarker;)V")
                             .c_str());
  SetFrame(m, dex::kAccSynthetic, /*registers=*/39, /*ins=*/37);
  lir::Label* second = m.NewLabel();
  lir::Label* call = m.NewLabel();
  m.Add(dex::OP_MOVE_FROM16, {m.Reg(0), m.Reg(36)});
  m.Add(dex::OP_CONST_HIGH16, {m.Reg(1), m.Literal(1u << 31)});
  m.Add(dex::OP_AND_INT, {m.Reg(1), m.Reg(0), m.Reg(1)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(second)});
  lir::Instruction* check_32 = m.Last();
  m.Add(dex::OP_CONST_16, {m.Reg(34), m.Literal(42)});
  m.Place(second);
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(1), m.Reg(37), m.Literal(1)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(call)});
  lir::Instruction* check_33 = m.Last();
  m.Add(dex::OP_CONST_16, {m.Reg(35), m.Literal(42)});
  m.Place(call);
  m.Add(dex::OP_INVOKE_DIRECT_RANGE,
        {m.RegRange(2, 34),
         m.MethodRef(kTestClassName, "<init>", ("(" + ints + ")V").c_str())});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{check_32, check_32}, Range{check_33, check_33}});
}

// class C(p1: Int = 42, p2: Int, ..., p224: Int, p225: Int = 42)
// The constructor has 8 masks.
TEST_F(KotlinDefaultArgumentsFilterTest,
       ShouldFilterMethodsWithMoreThan224Parameters) {
  const std::string ints(225, 'I');
  TestMethod m(
      "<init>",
      ("(" + ints + "IIIIIIIILkotlin/jvm/internal/DefaultConstructorMarker;)V")
          .c_str());
  SetFrame(m, dex::kAccSynthetic, /*registers=*/236, /*ins=*/235);
  lir::Label* second = m.NewLabel();
  lir::Label* call = m.NewLabel();
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(0), m.Reg(227), m.Literal(1)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(second)});
  lir::Instruction* check_1 = m.Last();
  m.Add(dex::OP_CONST_16, {m.Reg(2), m.Literal(42)});
  m.Place(second);
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(0), m.Reg(234), m.Literal(1)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(call)});
  lir::Instruction* check_225 = m.Last();
  m.Add(dex::OP_CONST_16, {m.Reg(226), m.Literal(42)});
  m.Place(call);
  m.Add(dex::OP_INVOKE_DIRECT_RANGE,
        {m.RegRange(1, 226),
         m.MethodRef(kTestClassName, "<init>", ("(" + ints + ")V").c_str())});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{check_1, check_1}, Range{check_225, check_225}});
}

TEST_F(KotlinDefaultArgumentsFilterTest, NumberOfMaskParameters) {
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(3), 1u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(34), 1u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(36), 2u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(67), 2u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(69), 3u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(100), 3u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(102), 4u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(133), 4u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(135), 5u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(166), 5u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(168), 6u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(199), 6u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(201), 7u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(232), 7u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(234), 8u);
  EXPECT_EQ(KotlinDefaultArgumentsFilter::NumberOfMaskParameters(255), 8u);
}

// fun choose(flag: Boolean, value: Int = if (flag) 1 else 2) = value
// D8 loads the bit into a register, which it also uses for the default value.
// The branch on `flag` is written by the user, so it is kept.
TEST_F(KotlinDefaultArgumentsFilterTest, ShouldFilterBitInRegister) {
  TestMethod m("choose$default", "(ZIILjava/lang/Object;)I");
  SetFrame(m, kStaticSynthetic, /*registers=*/4, /*ins=*/4);
  lir::Label* otherwise = m.NewLabel();
  lir::Label* call = m.NewLabel();
  m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(2)});
  m.Add(dex::OP_AND_INT_2ADDR, {m.Reg(2), m.Reg(3)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(2), m.Target(call)});
  lir::Instruction* check = m.Last();
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(otherwise)});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  m.Add(dex::OP_GOTO, {m.Target(call)});
  m.Place(otherwise);
  m.Add(dex::OP_MOVE, {m.Reg(1), m.Reg(3)});
  m.Place(call);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0, 1}), m.MethodRef(kTestClassName, "choose", "(ZI)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{check, check}});
}

// fun f(a: Int, b: Boolean = ready() && (a and 1) == 0) = b
// D8 reuses the register of the check of the mask for the result of ready(),
// and the default value checks a bit of `a`. Both branches are kept.
TEST_F(KotlinDefaultArgumentsFilterTest,
       ShouldNotFilterBranchesOfDefaultValue) {
  TestMethod m("f$default", "(IZILjava/lang/Object;)Z");
  SetFrame(m, kStaticSynthetic, /*registers=*/5, /*ins=*/4);
  lir::Label* no = m.NewLabel();
  lir::Label* call = m.NewLabel();
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(0), m.Reg(3), m.Literal(2)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(call)});
  lir::Instruction* check = m.Last();
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "ready", "()Z")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(no)});
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(0), m.Reg(1), m.Literal(1)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(no)});
  m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(1)});
  m.Add(dex::OP_GOTO, {m.Target(call)});
  m.Place(no);
  m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(0)});
  m.Place(call);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({1, 2}), m.MethodRef(kTestClassName, "f", "(IZ)Z")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{check, check}});
}

// A jump on the last parameter to code that throws another error is kept.
TEST_F(KotlinDefaultArgumentsFilterTest, ShouldNotFilterOtherErrors) {
  TestMethod m("foo$default", "(LFoo;IILjava/lang/Object;)I");
  SetFrame(m, kStaticSynthetic, /*registers=*/4, /*ins=*/4);
  lir::Label* error = m.NewLabel();
  m.Add(dex::OP_IF_NEZ, {m.Reg(3), m.Target(error)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0, 1}), m.MethodRef(kTestClassName, "foo", "(I)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  m.Place(error);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kError)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef("Not implemented")});
  m.Add(
      dex::OP_INVOKE_DIRECT,
      {m.Regs({0, 1}), m.MethodRef(kError, "<init>", "(Ljava/lang/String;)V")});
  m.Add(dex::OP_THROW, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
