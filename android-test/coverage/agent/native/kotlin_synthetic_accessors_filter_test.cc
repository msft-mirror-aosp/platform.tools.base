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

#include "tools/base/android-test/coverage/agent/native/kotlin_synthetic_accessors_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr dex::u4 kAccessorFlags =
    dex::kAccPublic | dex::kAccStatic | dex::kAccFinal | dex::kAccSynthetic;
constexpr char kSetterSignature[] =
    "(Landroidx/compose/runtime/MutableState;I)V";
constexpr char kDefaultArgumentsSignature[] =
    "(LFoo;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;";

// Unit tests for KotlinSyntheticAccessorsFilter. The class under test is
// kTestClassName.
class KotlinSyntheticAccessorsFilterTest : public FilterTestBase {
 protected:
  // Appends the code of an accessor of the setter onCreate$lambda$1, with its
  // parameters in v<first_reg> and the next register.
  static void AddCallToSetter(TestMethod& m, dex::u4 first_reg) {
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({first_reg, first_reg + 1}),
           m.MethodRef(kTestClassName, "onCreate$lambda$1", kSetterSignature)});
    m.Add(dex::OP_RETURN_VOID);
  }

  // Appends the code of the method for the default arguments of
  // `name`(String): String, which uses "World" if the mask says so.
  static void AddCallWithDefaultArguments(TestMethod& m, const char* name) {
    lir::Label* call = m.NewLabel();
    m.Add(dex::OP_AND_INT_LIT8, {m.Reg(2), m.Reg(2), m.Literal(1)});
    m.Add(dex::OP_IF_EQZ, {m.Reg(2), m.Target(call)});
    m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef("World")});
    m.Place(call);
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({0, 1}),
           m.MethodRef(kTestClassName, name,
                       "(Ljava/lang/String;)Ljava/lang/String;")});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  }

  KotlinSyntheticAccessorsFilter filter_;
};

// class Foo {
//   private var count = 0
//   inner class Reader { fun read() = count }
// }
// Reader reads `count` through the accessor access$getCount$p of Foo.
TEST_F(KotlinSyntheticAccessorsFilterTest, ShouldFilterAccessorOfProperty) {
  TestMethod m("access$getCount$p", "(LFoo;)I");
  m.ir_method()->access_flags = kAccessorFlags;
  m.AddLine(1);
  lir::Instruction* first = m.Last();
  m.Add(dex::OP_IGET,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "count", "I")});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// fun onCreate() {
//   var count by mutableStateOf(0)
//   scope.launch { count = 1 }
// }
// The coroutine sets the local property through the accessor of its setter,
// onCreate$lambda$1.
TEST_F(KotlinSyntheticAccessorsFilterTest, ShouldFilterAccessorOfLocalSetter) {
  TestMethod m("access$onCreate$lambda$1", kSetterSignature);
  m.ir_method()->access_flags = kAccessorFlags;
  m.AddLine(1);
  lir::Instruction* first = m.Last();
  AddCallToSetter(m, /*first_reg=*/0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// The frame of the accessor only has its parameters, so the agent grows it and
// first copies them back to their registers, which is ignored too.
TEST_F(KotlinSyntheticAccessorsFilterTest,
       ShouldFilterAccessorWithCopiesOfAgent) {
  TestMethod m("access$onCreate$lambda$1", kSetterSignature);
  m.ir_method()->access_flags = kAccessorFlags;
  lir::Instruction* first = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(0), m.Reg(1)});
  m.Add(dex::OP_MOVE_16, {m.Reg(1), m.Reg(2)});
  m.AddLine(1);
  AddCallToSetter(m, /*first_reg=*/0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// A method that isn't synthetic isn't an accessor, whatever its name.
TEST_F(KotlinSyntheticAccessorsFilterTest,
       ShouldNotFilterMethodThatIsNotSynthetic) {
  TestMethod m("access$onCreate$lambda$1", kSetterSignature);
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccStatic;
  m.AddLine(1);
  AddCallToSetter(m, /*first_reg=*/0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// fun greet(name: String = "World") = "Hello $name"
// The method for the default arguments is synthetic, but isn't an accessor.
TEST_F(KotlinSyntheticAccessorsFilterTest,
       ShouldNotFilterSyntheticMethodThatIsNotAccessor) {
  TestMethod m("greet$default", kDefaultArgumentsSignature);
  m.ir_method()->access_flags =
      dex::kAccPublic | dex::kAccStatic | dex::kAccSynthetic;
  m.AddLine(1);
  AddCallWithDefaultArguments(m, "greet");

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// fun accessibilityLabel(text: String = "World") = text
// The name of the synthetic method starts with access, but not with access$.
TEST_F(KotlinSyntheticAccessorsFilterTest,
       ShouldNotFilterSyntheticMethodWithNameStartingWithAccess) {
  TestMethod m("accessibilityLabel$default", kDefaultArgumentsSignature);
  m.ir_method()->access_flags =
      dex::kAccPublic | dex::kAccStatic | dex::kAccSynthetic;
  m.AddLine(1);
  AddCallWithDefaultArguments(m, "accessibilityLabel");

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
