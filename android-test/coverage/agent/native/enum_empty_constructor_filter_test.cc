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

#include "tools/base/android-test/coverage/agent/native/enum_empty_constructor_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kEnum[] = "Ljava/lang/Enum;";
constexpr char kConstructorSignature[] = "(Ljava/lang/String;I)V";
constexpr dex::u4 kPrivateConstructor = dex::kAccPrivate | dex::kAccConstructor;

// Unit tests for EnumEmptyConstructorFilter. The class under test is
// kTestClassName, an enum unless a test says otherwise.
class EnumEmptyConstructorFilterTest : public FilterTestBase {
 protected:
  EnumEmptyConstructorFilterTest() { context_.super_class_name = kEnum; }

  // Appends a call to the method `name` with the parameters `signature` of
  // `owner`, by default the constructor of Enum that takes the name and the
  // ordinal, on `this` in v<first>, the name in v<first + 1> and the ordinal
  // in v<first + 2>. Returns the call.
  static lir::Instruction* AddCall(
      TestMethod& m, dex::u4 first, const char* owner = kEnum,
      const char* name = "<init>",
      const char* signature = kConstructorSignature) {
    return m.Add(dex::OP_INVOKE_DIRECT, {m.Regs({first, first + 1, first + 2}),
                                         m.MethodRef(owner, name, signature)});
  }

  // Appends the code of an empty constructor on line 3: the call that
  // AddCall() appends on v0 to v2 with `owner`, `name` and `signature`, then
  // return-void. Returns the first instruction, the line.
  static lir::Instruction* AddEmptyCode(
      TestMethod& m, const char* owner = kEnum, const char* name = "<init>",
      const char* signature = kConstructorSignature) {
    lir::Instruction* line = m.AddLine(3);
    AddCall(m, /*first=*/0, owner, name, signature);
    m.Add(dex::OP_RETURN_VOID);
    return line;
  }

  EnumEmptyConstructorFilter filter_;
};

// enum class Level { LOW, HIGH }
// The constructor of an enum only passes the name and the ordinal of the
// constant to the constructor of Enum, on the line of the enum.
TEST_F(EnumEmptyConstructorFilterTest, ShouldFilterEmptyConstructor) {
  TestMethod m("<init>", kConstructorSignature);
  m.ir_method()->access_flags = kPrivateConstructor;
  lir::Instruction* first = AddEmptyCode(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// enum Level {
//   LOW;
//
//   Level() {
//   }
// }
// An explicit empty constructor has the call on its first line and the return
// on its last one.
TEST_F(EnumEmptyConstructorFilterTest, ShouldFilterExplicitEmptyConstructor) {
  TestMethod m("<init>", kConstructorSignature);
  m.ir_method()->access_flags = kPrivateConstructor;
  lir::Instruction* first = m.AddLine(4);
  AddCall(m, /*first=*/0);
  m.AddLine(5);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// When the agent grows the frame of the constructor to get a free register,
// `this`, the name and the ordinal move to the next registers, and the agent
// copies them back after the call to the constructor of Enum.
TEST_F(EnumEmptyConstructorFilterTest,
       ShouldFilterConstructorWithCopiesOfAgent) {
  TestMethod m("<init>", kConstructorSignature);
  m.ir_method()->access_flags = kPrivateConstructor;
  lir::Instruction* call = AddCall(m, /*first=*/1);
  m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(0), m.Reg(1)});
  m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(1), m.Reg(2)});
  m.Add(dex::OP_MOVE_16, {m.Reg(2), m.Reg(3)});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{call, m.Last()}});
}

// enum class Level(weight: Int) { LOW(1), HIGH(2) }
// A constructor with more parameters isn't the empty one, even if it doesn't
// use them.
TEST_F(EnumEmptyConstructorFilterTest,
       ShouldNotFilterConstructorWithParameters) {
  TestMethod m("<init>", "(Ljava/lang/String;II)V");
  m.ir_method()->access_flags = kPrivateConstructor;
  AddEmptyCode(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// enum Level { LOW, HIGH; Level() { count = 1; } }
TEST_F(EnumEmptyConstructorFilterTest, ShouldNotFilterConstructorWithBody) {
  TestMethod m("<init>", kConstructorSignature);
  m.ir_method()->access_flags = kPrivateConstructor;
  AddCall(m, /*first=*/1);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Add(dex::OP_IPUT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "count", "I")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The constructor throws instead of returning.
TEST_F(EnumEmptyConstructorFilterTest,
       ShouldNotFilterConstructorThatDoesNotReturn) {
  TestMethod m("<init>", kConstructorSignature);
  m.ir_method()->access_flags = kPrivateConstructor;
  AddCall(m, /*first=*/0);
  m.Add(dex::OP_THROW, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Only the constructors of enums are filtered.
TEST_F(EnumEmptyConstructorFilterTest,
       ShouldNotFilterConstructorOfClassThatIsNotEnum) {
  context_.super_class_name = "Ljava/lang/Object;";
  TestMethod m("<init>", kConstructorSignature);
  m.ir_method()->access_flags = kPrivateConstructor;
  AddEmptyCode(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// A method with the same parameters and code isn't a constructor.
TEST_F(EnumEmptyConstructorFilterTest,
       ShouldNotFilterMethodThatIsNotConstructor) {
  TestMethod m("init", kConstructorSignature);
  m.ir_method()->access_flags = dex::kAccPrivate;
  AddEmptyCode(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The call must be to the constructor of Enum.
TEST_F(EnumEmptyConstructorFilterTest,
       ShouldNotFilterCallToConstructorOfOtherClass) {
  TestMethod m("<init>", kConstructorSignature);
  m.ir_method()->access_flags = kPrivateConstructor;
  AddEmptyCode(m, "Lcom/example/Base;");

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The call isn't to a constructor.
TEST_F(EnumEmptyConstructorFilterTest, ShouldNotFilterCallToOtherMethod) {
  TestMethod m("<init>", kConstructorSignature);
  m.ir_method()->access_flags = kPrivateConstructor;
  AddEmptyCode(m, kEnum, "init");

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The call must be to the constructor of Enum that takes the name and the
// ordinal.
TEST_F(EnumEmptyConstructorFilterTest,
       ShouldNotFilterCallToConstructorWithOtherParameters) {
  TestMethod m("<init>", kConstructorSignature);
  m.ir_method()->access_flags = kPrivateConstructor;
  AddEmptyCode(m, kEnum, "<init>", "(Ljava/lang/String;II)V");

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
