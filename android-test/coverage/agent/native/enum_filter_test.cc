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

#include "tools/base/android-test/coverage/agent/native/enum_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kEnum[] = "Ljava/lang/Enum;";
constexpr dex::u4 kPublicStatic = dex::kAccPublic | dex::kAccStatic;

// Unit tests for EnumFilter. The class under test is kTestClassName, an enum
// unless a test says otherwise.
class EnumFilterTest : public FilterTestBase {
 protected:
  EnumFilterTest() { context_.super_class_name = kEnum; }

  // Appends the code of values() on `line`: the copy of the array of the
  // constants, which the static field $VALUES holds. Returns the first
  // instruction, the line.
  static lir::Instruction* AddCopyOfConstants(TestMethod& m, int line) {
    lir::Instruction* first = m.AddLine(line);
    m.Add(dex::OP_SGET_OBJECT,
          {m.Reg(0), m.FieldRef(kTestClassName, "$VALUES", "[LFoo;")});
    m.Add(
        dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef("[LFoo;", "clone", "()Ljava/lang/Object;")});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef("[LFoo;")});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
    return first;
  }

  // Appends the code of valueOf(String) on `line`, with the name in v1: the
  // lookup of the constant with that name by Enum. Returns the first
  // instruction, the line.
  static lir::Instruction* AddLookupOfConstant(TestMethod& m, int line) {
    lir::Instruction* first = m.AddLine(line);
    m.Add(dex::OP_CONST_CLASS, {m.Reg(0), m.TypeRef(kTestClassName)});
    m.Add(
        dex::OP_INVOKE_STATIC,
        {m.Regs({0, 1}),
         m.MethodRef(kEnum, "valueOf",
                     "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;")});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef(kTestClassName)});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
    return first;
  }

  EnumFilter filter_;
};

// enum Foo { BAR }
// javac gives the enum values(), which copies the array of the constants, on
// the line of the enum.
TEST_F(EnumFilterTest, ShouldFilterValues) {
  TestMethod m("values", "()[LFoo;");
  m.ir_method()->access_flags = kPublicStatic;
  lir::Instruction* first = AddCopyOfConstants(m, /*line=*/1);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// enum Foo { BAR }
// javac also gives the enum valueOf(String), which looks up the constant with
// a name, on the line of the enum.
TEST_F(EnumFilterTest, ShouldFilterValueOf) {
  TestMethod m("valueOf", "(Ljava/lang/String;)LFoo;");
  m.ir_method()->access_flags = kPublicStatic;
  lir::Instruction* first = AddLookupOfConstant(m, /*line=*/1);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// enum Foo {
//   BAR;
//   static Foo[] values(int count) { return Arrays.copyOf(values(), count); }
// }
TEST_F(EnumFilterTest, ShouldNotFilterOverloadOfValues) {
  TestMethod m("values", "(I)[LFoo;");
  m.ir_method()->access_flags = kPublicStatic;
  m.AddLine(3);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "values", "()[LFoo;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0, 1}),
         m.MethodRef("Ljava/util/Arrays;", "copyOf",
                     "([Ljava/lang/Object;I)[Ljava/lang/Object;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef("[LFoo;")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// enum Foo {
//   BAR;
//   static Foo[] all() { return values(); }
// }
TEST_F(EnumFilterTest, ShouldNotFilterOtherMethodThatReturnsConstants) {
  TestMethod m("all", "()[LFoo;");
  m.ir_method()->access_flags = kPublicStatic;
  m.AddLine(3);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "values", "()[LFoo;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Protocol buffers give their enums a valueOf(int), which calls forNumber(int)
// to look up the constant with a number.
TEST_F(EnumFilterTest, ShouldNotFilterValueOfNumber) {
  TestMethod m("valueOf", "(I)LFoo;");
  m.ir_method()->access_flags = kPublicStatic;
  m.AddLine(1);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({1}), m.MethodRef(kTestClassName, "forNumber", "(I)LFoo;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// enum Foo {
//   BAR;
//   static Foo fromName(String name) { return Enum.valueOf(Foo.class, name); }
// }
TEST_F(EnumFilterTest, ShouldNotFilterOtherMethodThatTakesName) {
  TestMethod m("fromName", "(Ljava/lang/String;)LFoo;");
  m.ir_method()->access_flags = kPublicStatic;
  AddLookupOfConstant(m, /*line=*/3);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// class Foo { static Foo[] values() { return new Foo[0]; } }
// Only the methods of enums are filtered.
TEST_F(EnumFilterTest, ShouldNotFilterValuesOfOtherClass) {
  context_.super_class_name = "Ljava/lang/Object;";
  TestMethod m("values", "()[LFoo;");
  m.ir_method()->access_flags = kPublicStatic;
  m.AddLine(1);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_NEW_ARRAY, {m.Reg(0), m.Reg(0), m.TypeRef("[LFoo;")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
