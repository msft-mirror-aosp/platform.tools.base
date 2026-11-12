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

#include "tools/base/android-test/coverage/agent/native/bridge_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

// Unit tests for BridgeFilter. The class under test is kTestClassName.
class BridgeFilterTest : public FilterTestBase {
 protected:
  // Appends the code of the bridge label(): String, with `this` in v<reg>: the
  // call to the default method of the interface Labeled, which D8 moves to a
  // companion class when it desugars default methods.
  static void AddCallToDefaultLabel(TestMethod& m, dex::u4 reg) {
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({reg}),
           m.MethodRef("Lcom/example/Labeled$-CC;", "$default$label",
                       "(Lcom/example/Labeled;)Ljava/lang/String;")});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  }

  BridgeFilter filter_;
};

// val increment = object : (Int) -> Int {
//   override fun invoke(value: Int): Int = value + 1
// }
// The bridge invoke(Object): Object unboxes the parameter and calls
// invoke(int): Integer.
TEST_F(BridgeFilterTest, ShouldFilterSyntheticBridge) {
  TestMethod m("invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
  m.ir_method()->access_flags =
      dex::kAccPublic | dex::kAccBridge | dex::kAccSynthetic;
  m.AddLine(1);
  lir::Instruction* first = m.Last();
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(2)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef("Ljava/lang/Number;")});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef("Ljava/lang/Number;", "intValue", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1, 0}),
         m.MethodRef(kTestClassName, "invoke", "(I)Ljava/lang/Integer;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// interface Labeled { fun label(): String = "label" }
// class Foo : Labeled
// With default methods in the JVM interface (-jvm-default=enable), Kotlin gives
// Foo a bridge to the default method of Labeled, which isn't synthetic.
TEST_F(BridgeFilterTest, ShouldFilterBridgeThatIsNotSynthetic) {
  TestMethod m("label", "()Ljava/lang/String;");
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccBridge;
  m.AddLine(2);
  lir::Instruction* first = m.Last();
  AddCallToDefaultLabel(m, /*reg=*/1);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// When the agent grows the frame of the bridge, it first copies `this` back to
// its register, which is ignored too.
TEST_F(BridgeFilterTest, ShouldFilterBridgeWithCopyOfAgent) {
  TestMethod m("label", "()Ljava/lang/String;");
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccBridge;
  lir::Instruction* move = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(1), m.Reg(2)});
  AddCallToDefaultLabel(m, /*reg=*/1);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{move, m.Last()}});
}

// override fun invoke(value: Int): Int = value + 1
// The method that the bridge calls has the code of the source.
TEST_F(BridgeFilterTest, ShouldNotFilterMethodThatIsNotBridge) {
  TestMethod m("invoke", "(I)Ljava/lang/Integer;");
  m.ir_method()->access_flags = dex::kAccPublic;
  m.AddLine(2);
  m.Add(dex::OP_ADD_INT_LIT8, {m.Reg(0), m.Reg(2), m.Literal(1)});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0}), m.MethodRef("Ljava/lang/Integer;", "valueOf",
                                  "(I)Ljava/lang/Integer;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// A synthetic method that isn't a bridge, such as the accessor that Kotlin
// generates for a private property, is left to other filters.
TEST_F(BridgeFilterTest, ShouldNotFilterSyntheticMethodThatIsNotBridge) {
  TestMethod m("access$getName$p", "(LFoo;)Ljava/lang/String;");
  m.ir_method()->access_flags =
      dex::kAccPublic | dex::kAccStatic | dex::kAccFinal | dex::kAccSynthetic;
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1),
         m.FieldRef(kTestClassName, "name", "Ljava/lang/String;")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
