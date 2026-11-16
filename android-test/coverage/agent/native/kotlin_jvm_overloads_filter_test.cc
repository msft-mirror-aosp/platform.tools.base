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

#include "tools/base/android-test/coverage/agent/native/kotlin_jvm_overloads_filter.h"

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

constexpr char kLogDefault[] =
    "(LFoo;Ljava/lang/String;Ljava/lang/String;IILjava/lang/Object;)V";
constexpr char kConstructorWithMarker[] =
    "(ILjava/lang/String;ILkotlin/jvm/internal/DefaultConstructorMarker;)V";
// The last line of the declaration, which the overloads get.
constexpr int kLastLine = 10;

// What a test changes in the overload of AddOverload().
struct Overload {
  // The invoke of the call, its registers, from v0 on for the /range invokes,
  // and the name and the signature of what it calls.
  dex::Opcode invoke = dex::OP_INVOKE_STATIC_RANGE;
  std::vector<dex::u4> registers = {0, 1, 2, 3, 4, 5};
  const char* name = "log$default";
  const char* signature = kLogDefault;
  // Whether a line starts before the call, as in a function written by hand,
  // and whether one starts with the return.
  bool line_before_call = false;
  bool line_before_return = true;
  // Whether the agent copied parameters back after the call, and whether a
  // constant comes between the call and the return.
  bool copies_after_call = false;
  bool constant_after_call = false;
  // Whether the overload returns the value of the call, or its parameter.
  bool returns_value = false;
  bool returns_parameter = false;
};

// The instructions of an overload.
struct OverloadCode {
  // The annotation of the source file that slicer adds first.
  lir::Instruction* first = nullptr;
  lir::Instruction* call = nullptr;
  lir::Instruction* last = nullptr;
};

// Unit tests for KotlinJvmOverloadsFilter. The class under test is a Kotlin
// class.
class KotlinJvmOverloadsFilterTest : public FilterTestBase {
 protected:
  KotlinJvmOverloadsFilterTest() {
    context_.class_annotations.insert(kKotlinMetadataDescriptor);
    context_.source_file = "Example.kt";
  }

  // Appends the code of the overload log(message) that Kotlin 2.0 generates
  // for `@JvmOverloads fun log(message: String, tag: String = "tag", level: Int
  // = 3)`, whose declaration ends on line kLastLine, as D8 lays it out, changed
  // as `o` says. Like slicer, it starts with the annotations of the source file
  // and of the start line of the debug information:
  //   const-string v0, "message"
  //   invoke-static {v7, v0}, Intrinsics.checkNotNullParameter(...)V
  //   const/4 v4, 6  (the mask of the parameters that take their defaults)
  //   const/4 v5, 0
  //   const/4 v2, 0
  //   const/4 v3, 0
  //   move-object v0, v6
  //   move-object v1, v7
  //   invoke-static/range {v0 .. v5}, LFoo;->log$default(...)V
  //   return-void  (line kLastLine)
  static OverloadCode AddOverload(TestMethod& m, const Overload& o) {
    OverloadCode code;
    code.first = m.code_ir().Alloc<lir::DbgInfoAnnotation>(dex::DBG_SET_FILE);
    m.code_ir().instructions.push_back(code.first);
    m.AddLine(kLastLine);  // The start line.
    if (o.line_before_call) {
      m.AddLine(kLastLine - 1);
    }
    m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("message")});
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({7, 0}),
           m.MethodRef("Lkotlin/jvm/internal/Intrinsics;",
                       "checkNotNullParameter",
                       "(Ljava/lang/Object;Ljava/lang/String;)V")});
    m.Add(dex::OP_CONST_4, {m.Reg(4), m.Literal(6)});
    m.Add(dex::OP_CONST_4, {m.Reg(5), m.Literal(0)});
    m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(0)});
    m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(0)});
    m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(6)});
    m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(7)});
    const bool range = o.invoke == dex::OP_INVOKE_STATIC_RANGE ||
                       o.invoke == dex::OP_INVOKE_VIRTUAL_RANGE ||
                       o.invoke == dex::OP_INVOKE_DIRECT_RANGE;
    lir::Operand* registers =
        range ? static_cast<lir::Operand*>(
                    m.RegRange(0, static_cast<int>(o.registers.size())))
              : m.Regs(o.registers);
    code.call =
        m.Add(o.invoke,
              {registers, m.MethodRef(kTestClassName, o.name, o.signature)});
    if (o.copies_after_call) {
      m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(6), m.Reg(7)});
      m.Add(dex::OP_MOVE_16, {m.Reg(7), m.Reg(8)});
      m.Add(dex::OP_MOVE_WIDE_16, {m.code_ir().Alloc<lir::VRegPair>(8),
                                   m.code_ir().Alloc<lir::VRegPair>(10)});
    }
    if (o.constant_after_call) {
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    }
    if (o.returns_value) {
      m.Add(dex::OP_MOVE_RESULT, {m.Reg(7)});
    }
    if (o.line_before_return) {
      m.AddLine(kLastLine);
    }
    if (o.returns_value) {
      code.last = m.Add(dex::OP_RETURN, {m.Reg(7)});
    } else if (o.returns_parameter) {
      code.last = m.Add(dex::OP_RETURN_OBJECT, {m.Reg(7)});
    } else {
      code.last = m.Add(dex::OP_RETURN_VOID);
    }
    return code;
  }

  // Runs the filter on the overload of `o` and expects it to ignore nothing.
  void ExpectNotFiltered(const Overload& o) {
    TestMethod m("log", "(Ljava/lang/String;)V");
    AddOverload(m, o);

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

    ExpectIgnored(m, {});
  }

  KotlinJvmOverloadsFilter filter_;
};

TEST_F(KotlinJvmOverloadsFilterTest, ShouldFilterOverloadOfFunction) {
  TestMethod m("log", "(Ljava/lang/String;)V");
  OverloadCode code = AddOverload(m, Overload());

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

// @JvmOverloads fun topLevel(x: Int, y: Int = 1): D8 passes few enough
// registers to call topLevel$default without /range.
TEST_F(KotlinJvmOverloadsFilterTest, ShouldFilterOverloadWithoutRange) {
  Overload o;
  o.invoke = dex::OP_INVOKE_STATIC;
  o.registers = {3, 2, 0, 1};
  o.name = "topLevel$default";
  o.signature = "(IIILjava/lang/Object;)V";
  TestMethod m("topLevel", "(I)V");
  OverloadCode code = AddOverload(m, o);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

// class Foo @JvmOverloads constructor(val a: Int = 1, val b: String = "b"):
// the overloads call the constructor that takes a DefaultConstructorMarker.
TEST_F(KotlinJvmOverloadsFilterTest, ShouldFilterOverloadOfConstructor) {
  for (dex::Opcode invoke :
       {dex::OP_INVOKE_DIRECT, dex::OP_INVOKE_DIRECT_RANGE}) {
    SCOPED_TRACE(invoke);
    Overload o;
    o.invoke = invoke;
    o.registers = invoke == dex::OP_INVOKE_DIRECT
                      ? std::vector<dex::u4>{2, 3, 0, 1, 0}
                      : std::vector<dex::u4>{0, 1, 2, 3, 4};
    o.name = "<init>";
    o.signature = kConstructorWithMarker;
    TestMethod m("<init>", "(I)V");
    OverloadCode code = AddOverload(m, o);
    output_ = Output();

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

    ExpectIgnored(m, {Range{code.first, code.last}});
  }
}

// When the agent grows the frame of a constructor, it copies the parameters
// back right after the call of the other constructor.
TEST_F(KotlinJvmOverloadsFilterTest, ShouldFilterOverloadWithCopiesAfterCall) {
  Overload o;
  o.invoke = dex::OP_INVOKE_DIRECT;
  o.registers = {2, 3, 0, 1, 0};
  o.name = "<init>";
  o.signature = kConstructorWithMarker;
  o.copies_after_call = true;
  TestMethod m("<init>", "(I)V");
  OverloadCode code = AddOverload(m, o);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

// @JvmOverloads fun sum(a: Int, b: Int = 2): Int: the overload sum(a) returns
// the value of sum$default, and keeps its return.
TEST_F(KotlinJvmOverloadsFilterTest, ShouldNotFilterOverloadThatReturnsValue) {
  Overload o;
  o.name = "sum$default";
  o.signature = "(LFoo;IIILjava/lang/Object;)I";
  o.returns_value = true;
  ExpectNotFiltered(o);
}

// An overload ends with return-void: a return of another value, such as a
// parameter, ends no overload, even right after the call.
TEST_F(KotlinJvmOverloadsFilterTest, ShouldNotFilterReturnOfOtherValue) {
  Overload o;
  o.returns_parameter = true;
  ExpectNotFiltered(o);
}

// fun callsDefault() {
//   log("x")
// }
// A function written by hand that calls log$default has a line before it.
TEST_F(KotlinJvmOverloadsFilterTest, ShouldNotFilterCallWithLineBeforeIt) {
  Overload o;
  o.line_before_call = true;
  ExpectNotFiltered(o);
}

// fun callsDefault() = log("x")
TEST_F(KotlinJvmOverloadsFilterTest, ShouldNotFilterCallOnSingleLine) {
  Overload o;
  o.line_before_call = true;
  o.line_before_return = false;
  ExpectNotFiltered(o);
}

// Kotlin before 2.0 and since 2.2 gives the overloads no line numbers, and
// KotlinGeneratedFilter ignores them.
TEST_F(KotlinJvmOverloadsFilterTest, ShouldNotFilterOverloadWithoutLines) {
  Overload o;
  o.line_before_return = false;
  ExpectNotFiltered(o);
}

TEST_F(KotlinJvmOverloadsFilterTest, ShouldNotFilterCodeBetweenCallAndReturn) {
  Overload o;
  o.constant_after_call = true;
  ExpectNotFiltered(o);
}

// fun nothing() {}: a return that starts a line, with nothing before it.
TEST_F(KotlinJvmOverloadsFilterTest, ShouldNotFilterMethodWithOnlyReturn) {
  TestMethod m("nothing", "()V");
  m.AddLine(kLastLine);  // The start line.
  m.AddLine(kLastLine);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Static calls of methods without the suffix, also with the marker, a virtual
// call of a $default method, and direct calls without the marker or of a
// method that returns a value.
TEST_F(KotlinJvmOverloadsFilterTest, ShouldNotFilterOtherCalls) {
  struct Call {
    dex::Opcode invoke;
    std::vector<dex::u4> registers;
    const char* name;
    const char* signature;
  };
  const std::vector<dex::u4> six = {0, 1, 2, 3, 4, 5};
  for (const Call& call : {
           Call{dex::OP_INVOKE_STATIC_RANGE, six, "log", kLogDefault},
           Call{dex::OP_INVOKE_STATIC_RANGE, six, "log$defaults", kLogDefault},
           Call{dex::OP_INVOKE_STATIC_RANGE, six, "logdefault", kLogDefault},
           Call{dex::OP_INVOKE_STATIC_RANGE, six, "create",
                kConstructorWithMarker},
           Call{dex::OP_INVOKE_VIRTUAL_RANGE, six, "log$default", kLogDefault},
           Call{dex::OP_INVOKE_DIRECT, {0, 1, 2}, "<init>", "(II)V"},
           Call{dex::OP_INVOKE_DIRECT,
                {0, 1, 2},
                "<init>",
                "(ILkotlin/jvm/internal/DefaultConstructorMarker;)I"},
       }) {
    SCOPED_TRACE(call.name);
    Overload o;
    o.invoke = call.invoke;
    o.registers = call.registers;
    o.name = call.name;
    o.signature = call.signature;
    output_ = Output();
    ExpectNotFiltered(o);
  }
}

TEST_F(KotlinJvmOverloadsFilterTest, ShouldRunOnKotlinClasses) {
  TestMethod m("log", "(Ljava/lang/String;)V");
  OverloadCode code = AddOverload(m, Overload());

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(code.last));
}

TEST_F(KotlinJvmOverloadsFilterTest, ShouldNotRunOnOtherClasses) {
  context_.class_annotations.clear();
  context_.source_file = "Foo.java";
  TestMethod m("log", "(Ljava/lang/String;)V");
  OverloadCode code = AddOverload(m, Overload());

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_FALSE(result.IsIgnored(code.last));
}

}  // namespace
}  // namespace coverage
