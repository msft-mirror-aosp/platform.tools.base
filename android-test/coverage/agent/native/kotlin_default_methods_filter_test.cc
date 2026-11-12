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

#include "tools/base/android-test/coverage/agent/native/kotlin_default_methods_filter.h"

#include <gtest/gtest.h>

#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kDefaultImpls[] = "Lcom/example/Labeled$DefaultImpls;";
constexpr char kCompanion[] = "Lcom/example/Labeled$-CC;";
constexpr char kLabel[] = "()Ljava/lang/String;";
constexpr char kLabelOf[] = "(Lcom/example/Labeled;)Ljava/lang/String;";

// Unit tests for KotlinDefaultMethodsFilter. The class under test is
// kTestClassName, unless a test names a DefaultImpls class in context_.
class KotlinDefaultMethodsFilterTest : public FilterTestBase {
 protected:
  // Gives `m` a frame of `registers` registers, the last `ins` of which hold
  // its parameters.
  static void SetFrame(TestMethod& m, dex::u2 registers, dex::u2 ins) {
    m.ir_method()->code->registers = registers;
    m.ir_method()->code->ins_count = ins;
  }

  // Appends a call to `name` of `owner` with the parameters `regs`, then the
  // return of its result.
  static void AddCallAndReturn(TestMethod& m, const char* owner,
                               const char* name,
                               const std::vector<dex::u4>& regs,
                               const char* signature = kLabelOf) {
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs(regs), m.MethodRef(owner, name, signature)});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  }

  KotlinDefaultMethodsFilter filter_;
};

// interface Labeled { fun label(): String = "label" }
// class Foo : Labeled
// When Labeled is compiled without default methods in the JVM
// (-jvm-default=disable), Kotlin gives Foo a label() that calls the default
// code in Labeled$DefaultImpls.
TEST_F(KotlinDefaultMethodsFilterTest, ShouldFilterCallToDefaultImpls) {
  TestMethod m("label", kLabel);
  SetFrame(m, /*registers=*/2, /*ins=*/1);
  m.AddLine(2);
  lir::Instruction* first = m.Last();
  AddCallAndReturn(m, kDefaultImpls, "label", {1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// When the agent grows the frame, `this` arrives in v2, and the agent first
// copies it back to v1, which is ignored too.
TEST_F(KotlinDefaultMethodsFilterTest,
       ShouldFilterCallToDefaultImplsWithCopyOfAgent) {
  TestMethod m("label", kLabel);
  SetFrame(m, /*registers=*/3, /*ins=*/1);
  lir::Instruction* first = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(1), m.Reg(2)});
  m.AddLine(2);
  AddCallAndReturn(m, kDefaultImpls, "label", {1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// When `this` is above v15, the call is an invoke-static/range. The stubs have
// fewer registers, but a longer method that starts with super.label() can have
// that many.
TEST_F(KotlinDefaultMethodsFilterTest, ShouldFilterRangeCallToDefaultImpls) {
  TestMethod m("label", kLabel);
  SetFrame(m, /*registers=*/17, /*ins=*/1);
  m.AddLine(2);
  lir::Instruction* first = m.Last();
  m.Add(dex::OP_INVOKE_STATIC_RANGE,
        {m.RegRange(16, 1), m.MethodRef(kDefaultImpls, "label", kLabelOf)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// fun describe(count: Int): String = super.label()
// A method that starts with a call that only passes `this` to the default code
// is filtered whatever its parameters. When the agent grows its frame, it first
// copies `this` back to v1 and `count` back to v2.
TEST_F(KotlinDefaultMethodsFilterTest,
       ShouldFilterCallToDefaultImplsWithCopiesOfAgent) {
  TestMethod m("describe", "(I)Ljava/lang/String;");
  SetFrame(m, /*registers=*/4, /*ins=*/2);
  lir::Instruction* first = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(1), m.Reg(2)});
  m.Add(dex::OP_MOVE_16, {m.Reg(2), m.Reg(3)});
  m.AddLine(2);
  AddCallAndReturn(m, kDefaultImpls, "label", {1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// interface Named : Labeled { override fun label() = super.label() }
// When Named is compiled without default methods in the JVM, its default code
// is in the static Named$DefaultImpls.label(Named), which passes its first
// parameter to the default code in Labeled$DefaultImpls.
TEST_F(KotlinDefaultMethodsFilterTest,
       ShouldFilterStaticMethodThatCallsDefaultImpls) {
  context_.class_name = "Lcom/example/Named$DefaultImpls;";
  TestMethod m("label", "(Lcom/example/Named;)Ljava/lang/String;");
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccStatic;
  SetFrame(m, /*registers=*/2, /*ins=*/1);
  m.AddLine(1);
  lir::Instruction* first = m.Last();
  AddCallAndReturn(m, kDefaultImpls, "label", {1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// interface Labeled { fun label(prefix: String): String = prefix }
// The call also passes the parameter `prefix`.
TEST_F(KotlinDefaultMethodsFilterTest,
       ShouldNotFilterCallThatPassesOtherParameters) {
  TestMethod m("label", "(Ljava/lang/String;)Ljava/lang/String;");
  SetFrame(m, /*registers=*/3, /*ins=*/2);
  m.AddLine(2);
  AddCallAndReturn(m, kDefaultImpls, "label", {1, 2},
                   "(Lcom/example/Labeled;Ljava/lang/String;)"
                   "Ljava/lang/String;");

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The call passes the second parameter instead of `this`.
TEST_F(KotlinDefaultMethodsFilterTest, ShouldNotFilterCallOnOtherParameter) {
  TestMethod m("labelOf", kLabelOf);
  SetFrame(m, /*registers=*/3, /*ins=*/2);
  m.AddLine(2);
  AddCallAndReturn(m, kDefaultImpls, "label", {2});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// override fun label(): String {
//   log()
//   return super.label()
// }
TEST_F(KotlinDefaultMethodsFilterTest, ShouldNotFilterCallAfterOtherCode) {
  TestMethod m("label", kLabel);
  SetFrame(m, /*registers=*/2, /*ins=*/1);
  m.AddLine(2);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef("Lcom/example/Log;", "log", "()V")});
  m.AddLine(3);
  AddCallAndReturn(m, kDefaultImpls, "label", {1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The default method that D8 moves to a companion class, when it desugars
// default methods, isn't in a DefaultImpls class.
TEST_F(KotlinDefaultMethodsFilterTest, ShouldNotFilterCallToOtherClass) {
  TestMethod m("label", kLabel);
  SetFrame(m, /*registers=*/2, /*ins=*/1);
  m.AddLine(2);
  AddCallAndReturn(m, kCompanion, "$default$label", {1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// interface Labeled { fun label(): String = "label" }
// When Labeled is compiled with default methods in the JVM
// (-jvm-default=enable), Kotlin also generates Labeled$DefaultImpls for the
// classes compiled against Labeled without them. Its label() calls the default
// method through the accessor access$label$jd, which D8 moves to a companion
// class when it desugars default methods.
TEST_F(KotlinDefaultMethodsFilterTest,
       ShouldFilterMethodOfDefaultImplsThatCallsDefaultMethod) {
  context_.class_name = kDefaultImpls;
  TestMethod m("label", kLabelOf);
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccStatic;
  SetFrame(m, /*registers=*/2, /*ins=*/1);
  m.AddLine(1);
  lir::Instruction* first = m.Last();
  AddCallAndReturn(m, kCompanion, "access$label$jd", {1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// When Labeled is compiled without default methods in the JVM, the code of the
// default method is in Labeled$DefaultImpls.
TEST_F(KotlinDefaultMethodsFilterTest,
       ShouldNotFilterMethodOfDefaultImplsWithCode) {
  context_.class_name = kDefaultImpls;
  TestMethod m("label", kLabelOf);
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccStatic;
  SetFrame(m, /*registers=*/2, /*ins=*/1);
  m.AddLine(1);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("label")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// interface Labeled {
//   fun label(): String = prefix
//   companion object { private val prefix = "label" }
// }
// When Labeled is compiled without default methods in the JVM, its default code
// reads `prefix` through an accessor of the companion object, which isn't one
// of a default method.
TEST_F(KotlinDefaultMethodsFilterTest,
       ShouldNotFilterMethodOfDefaultImplsThatCallsOtherAccessor) {
  context_.class_name = kDefaultImpls;
  TestMethod m("label", kLabelOf);
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccStatic;
  SetFrame(m, /*registers=*/2, /*ins=*/1);
  m.AddLine(1);
  AddCallAndReturn(m, "Lcom/example/Labeled$Companion;", "access$getPrefix$p",
                   {}, kLabel);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The name of the called method ends with $jd, but it isn't an accessor.
TEST_F(KotlinDefaultMethodsFilterTest,
       ShouldNotFilterMethodOfDefaultImplsThatCallsOtherMethod) {
  context_.class_name = kDefaultImpls;
  TestMethod m("label", kLabelOf);
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccStatic;
  SetFrame(m, /*registers=*/2, /*ins=*/1);
  m.AddLine(1);
  AddCallAndReturn(m, kCompanion, "label$jd", {1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Only the methods of a DefaultImpls class call the accessors of default
// methods.
TEST_F(KotlinDefaultMethodsFilterTest,
       ShouldNotFilterMethodOfOtherClassThatCallsDefaultMethod) {
  TestMethod m("label", kLabelOf);
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccStatic;
  SetFrame(m, /*registers=*/2, /*ins=*/1);
  m.AddLine(1);
  AddCallAndReturn(m, kCompanion, "access$label$jd", {1});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
