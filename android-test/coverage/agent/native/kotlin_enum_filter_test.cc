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

#include "tools/base/android-test/coverage/agent/native/kotlin_enum_filter.h"

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

constexpr char kCompanion[] = "LFoo$Companion;";
constexpr char kEnumEntries[] = "Lkotlin/enums/EnumEntries;";
constexpr char kGetEntriesSignature[] = "()Lkotlin/enums/EnumEntries;";
constexpr dex::u4 kPublicStatic = dex::kAccPublic | dex::kAccStatic;

// Unit tests for KotlinEnumFilter. The class under test is kTestClassName, a
// Kotlin enum unless a test says otherwise.
class KotlinEnumFilterTest : public FilterTestBase {
 protected:
  KotlinEnumFilterTest() {
    context_.super_class_name = "Ljava/lang/Enum;";
    context_.class_annotations = {kKotlinMetadataDescriptor};
    context_.source_file = "Foo.kt";
  }

  // Appends the code of getEntries() in the shape that some Kotlin compilers
  // give it: the read of the static field $ENTRIES before the first line, then
  // the return of the entries on line 3, the closing brace of the enum. Returns
  // the return.
  static lir::Instruction* AddGetEntriesCode(TestMethod& m) {
    m.AddLine(3);  // The start line.
    m.Add(dex::OP_SGET_OBJECT,
          {m.Reg(0), m.FieldRef(kTestClassName, "$ENTRIES", kEnumEntries)});
    m.AddLine(3);
    return m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  }

  // Appends the code of the static method that @JvmStatic gives Foo for the
  // method `name` with the JNI signature `signature` of the companion object,
  // on line 5: the call to that method on the companion object, with the
  // parameters from v1 on, and the return of its result.
  static void AddCallToCompanion(TestMethod& m, const char* name,
                                 const char* signature,
                                 const std::vector<dex::u4>& regs) {
    m.AddLine(5);  // The start line.
    m.Add(dex::OP_SGET_OBJECT,
          {m.Reg(0), m.FieldRef(kTestClassName, "Companion", kCompanion)});
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs(regs), m.MethodRef(kCompanion, name, signature)});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    m.AddLine(5);
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  }

  KotlinEnumFilter filter_;
};

// enum class Foo {
//   BAR
// }
TEST_F(KotlinEnumFilterTest, ShouldFilterGetEntries) {
  TestMethod m("getEntries", kGetEntriesSignature);
  m.ir_method()->access_flags = kPublicStatic;
  AddGetEntriesCode(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{*m.code_ir().instructions.begin(), m.Last()}});
}

// class Foo(val entries: EnumEntries<Bar>)
// Only the method of enums is filtered.
TEST_F(KotlinEnumFilterTest, ShouldNotFilterGetEntriesOfClassThatIsNotEnum) {
  context_.super_class_name = "Ljava/lang/Object;";
  TestMethod m("getEntries", kGetEntriesSignature);
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccFinal;
  m.AddLine(1);  // The start line.
  m.AddLine(1);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1),
         m.FieldRef(kTestClassName, "entries", kEnumEntries)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// enum class Foo {
//   BAR;
//
//   companion object {
//     @JvmStatic val all: EnumEntries<Foo> get() = entries
//   }
// }
// @JvmStatic gives Foo a static method that calls the one of the companion
// object.
TEST_F(KotlinEnumFilterTest, ShouldNotFilterOtherMethodThatReturnsEntries) {
  TestMethod m("getAll", kGetEntriesSignature);
  m.ir_method()->access_flags = kPublicStatic | dex::kAccFinal;
  AddCallToCompanion(m, "getAll", kGetEntriesSignature, {0});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// enum class Foo {
//   BAR;
//
//   companion object {
//     @JvmStatic fun getEntries(count: Int) = entries.take(count)
//   }
// }
TEST_F(KotlinEnumFilterTest, ShouldNotFilterOverloadOfGetEntries) {
  TestMethod m("getEntries", "(I)Ljava/util/List;");
  m.ir_method()->access_flags = kPublicStatic | dex::kAccFinal;
  AddCallToCompanion(m, "getEntries", "(I)Ljava/util/List;", {0, 1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The Kotlin compiler of instrumenter_test gives getEntries() no lines, so
// KotlinGeneratedFilter ignores it there. These tests run the registered
// filters that apply to the class instead, to check the set that this filter
// is in. KotlinGeneratedFilter only ignores the read before the first line.
TEST_F(KotlinEnumFilterTest, ShouldRunOnKotlinClasses) {
  TestMethod m("getEntries", kGetEntriesSignature);
  m.ir_method()->access_flags = kPublicStatic;
  lir::Instruction* entries_return = AddGetEntriesCode(m);

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(entries_return));
}

// A Java enum with the same method.
TEST_F(KotlinEnumFilterTest, ShouldNotRunOnOtherClasses) {
  context_.class_annotations.clear();
  context_.source_file = "Foo.java";
  TestMethod m("getEntries", kGetEntriesSignature);
  m.ir_method()->access_flags = kPublicStatic;
  lir::Instruction* entries_return = AddGetEntriesCode(m);

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_FALSE(result.IsIgnored(entries_return));
}

}  // namespace
}  // namespace coverage
