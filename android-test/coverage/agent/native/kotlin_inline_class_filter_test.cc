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

#include "tools/base/android-test/coverage/agent/native/kotlin_inline_class_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kString[] = "Ljava/lang/String;";

// Unit tests for KotlinInlineClassFilter. The class under test is
// kTestClassName, a value class unless a test says otherwise.
class KotlinInlineClassFilterTest : public FilterTestBase {
 protected:
  KotlinInlineClassFilterTest() {
    context_.class_annotations = {kKotlinMetadataDescriptor,
                                  "Lkotlin/jvm/JvmInline;"};
  }

  // Appends the read of the value of the class from `this` in v1 into v0.
  static void AddReadOfValue(TestMethod& m) {
    m.Add(dex::OP_IGET_OBJECT,
          {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "value", kString)});
  }

  KotlinInlineClassFilter filter_;
};

// @JvmInline value class Name(val value: String)
// Kotlin reads the value directly, so only Java code calls its getter.
TEST_F(KotlinInlineClassFilterTest, ShouldFilterGetterOfValue) {
  TestMethod m("getValue", "()Ljava/lang/String;");
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccFinal;
  lir::Instruction* first = m.AddLine(3);
  AddReadOfValue(m);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// @JvmInline value class Name(val value: String) {
//   override fun toString() = "name: $value"
// }
// The instance method calls its static version, which Kotlin code calls.
TEST_F(KotlinInlineClassFilterTest, ShouldFilterMethodThatCallsStaticVersion) {
  TestMethod m("toString", "()Ljava/lang/String;");
  m.ir_method()->access_flags = dex::kAccPublic;
  lir::Instruction* first = m.AddLine(4);
  AddReadOfValue(m);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0}), m.MethodRef(kTestClassName, "toString-impl",
                                  "(Ljava/lang/String;)Ljava/lang/String;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// @JvmInline value class Name(val value: String) {
//   val length get() = value.length
// }
// The static methods hold the code that Kotlin calls.
TEST_F(KotlinInlineClassFilterTest, ShouldNotFilterStaticMethod) {
  TestMethod m("getLength-impl", "(Ljava/lang/String;)I");
  m.ir_method()->access_flags =
      dex::kAccPublic | dex::kAccStatic | dex::kAccFinal;
  m.AddLine(4);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1}), m.MethodRef(kString, "length", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// class Name(val value: String)
// Only the methods of value classes are filtered.
TEST_F(KotlinInlineClassFilterTest, ShouldNotFilterGetterOfOtherClass) {
  context_.class_annotations = {kKotlinMetadataDescriptor};
  TestMethod m("getValue", "()Ljava/lang/String;");
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccFinal;
  m.AddLine(3);
  AddReadOfValue(m);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
