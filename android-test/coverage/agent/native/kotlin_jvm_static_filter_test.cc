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

#include "tools/base/android-test/coverage/agent/native/kotlin_jvm_static_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

namespace coverage {
namespace {

constexpr char kCompanionClass[] = "LFoo$Companion;";
constexpr char kJvmStatic[] = "Lkotlin/jvm/JvmStatic;";
// The last line of the declaration, which the static method gets.
constexpr int kLastLine = 26;
constexpr dex::u4 kPublicStaticFinal =
    dex::kAccPublic | dex::kAccStatic | dex::kAccFinal;

// What a test changes in the static method of AddStaticMethod().
struct StaticMethod {
  // The opcode of the load first in the method, and the class, the name and
  // the type of the field that it loads.
  dex::Opcode load = dex::OP_SGET_OBJECT;
  const char* field_owner = kTestClassName;
  const char* field_name = "Companion";
  const char* field_type = kCompanionClass;
  // Whether a constant comes before the load.
  bool constant_before_load = false;
  // Whether a line of the method starts on the load.
  bool line_on_load = false;
  // Whether the method returns the value of the call.
  bool returns_value = false;
};

// The instructions of a static method.
struct StaticMethodCode {
  lir::Instruction* first = nullptr;
  lir::Instruction* last = nullptr;
};

// Unit tests for KotlinJvmStaticFilter. The class under test is a Kotlin
// class.
class KotlinJvmStaticFilterTest : public FilterTestBase {
 protected:
  KotlinJvmStaticFilterTest() {
    context_.class_annotations.insert(kKotlinMetadataDescriptor);
    context_.source_file = "Example.kt";
  }

  // Makes `m` a public static method annotated with @JvmStatic, as Kotlin
  // generates it.
  static void MakeJvmStatic(TestMethod& m) {
    m.ir_method()->access_flags = kPublicStaticFinal;
    m.AddAnnotation(kJvmStatic);
  }

  // Appends the code of the static method noArgs() that Kotlin generates in
  // Foo for `@JvmStatic fun noArgs()` of its companion object, whose
  // declaration ends on line kLastLine, as D8 lays it out, changed as `s`
  // says. Like slicer, it starts with the annotations of the source file and
  // of the start line of the debug information:
  //   sget-object v0, LFoo;->Companion:LFoo$Companion;
  //   invoke-virtual {v0}, LFoo$Companion;->noArgs()V
  //   return-void  (line kLastLine)
  static StaticMethodCode AddStaticMethod(TestMethod& m,
                                          const StaticMethod& s) {
    StaticMethodCode code;
    code.first = m.code_ir().Alloc<lir::DbgInfoAnnotation>(dex::DBG_SET_FILE);
    m.code_ir().instructions.push_back(code.first);
    m.AddLine(kLastLine);  // The start line.
    if (s.constant_before_load) {
      m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
    }
    if (s.line_on_load) {
      m.AddLine(kLastLine);
    }
    m.Add(s.load,
          {m.Reg(0), m.FieldRef(s.field_owner, s.field_name, s.field_type)});
    if (s.returns_value) {
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kCompanionClass, "value", "()I")});
      m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
      m.AddLine(kLastLine);
      code.last = m.Add(dex::OP_RETURN, {m.Reg(0)});
    } else {
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kCompanionClass, "noArgs", "()V")});
      m.AddLine(kLastLine);
      code.last = m.Add(dex::OP_RETURN_VOID);
    }
    return code;
  }

  // Runs the filter on `m` and expects it to ignore nothing.
  void ExpectNotFiltered(TestMethod& m, const StaticMethod& s) {
    AddStaticMethod(m, s);

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

    ExpectIgnored(m, {});
  }

  KotlinJvmStaticFilter filter_;
};

TEST_F(KotlinJvmStaticFilterTest, ShouldFilterStaticMethodOfCompanionFunction) {
  TestMethod m("noArgs", "()V");
  MakeJvmStatic(m);
  StaticMethodCode code = AddStaticMethod(m, StaticMethod());

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

// @JvmStatic fun value(): Int = 1
TEST_F(KotlinJvmStaticFilterTest, ShouldFilterStaticMethodThatReturnsValue) {
  TestMethod m("value", "()I");
  MakeJvmStatic(m);
  StaticMethod s;
  s.returns_value = true;
  StaticMethodCode code = AddStaticMethod(m, s);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

// @JvmStatic fun withArgs(a: Int): the build-time report keeps the static
// methods that take parameters.
TEST_F(KotlinJvmStaticFilterTest, ShouldNotFilterStaticMethodWithParameters) {
  TestMethod m("withArgs", "(I)V");
  MakeJvmStatic(m);
  ExpectNotFiltered(m, StaticMethod());
}

// The function of the companion object itself is an instance method.
TEST_F(KotlinJvmStaticFilterTest, ShouldNotFilterInstanceMethod) {
  TestMethod m("noArgs", "()V");
  MakeJvmStatic(m);
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccFinal;
  ExpectNotFiltered(m, StaticMethod());
}

// @JvmStatic val prop: the static getter isn't annotated itself.
TEST_F(KotlinJvmStaticFilterTest, ShouldNotFilterStaticMethodWithoutJvmStatic) {
  TestMethod m("getProp", "()I");
  m.ir_method()->access_flags = kPublicStaticFinal;
  StaticMethod s;
  s.returns_value = true;
  ExpectNotFiltered(m, s);
}

// Only annotations that are visible at runtime count, and only @JvmStatic.
TEST_F(KotlinJvmStaticFilterTest, ShouldNotFilterOtherAnnotations) {
  {
    SCOPED_TRACE("build visibility");
    TestMethod m("noArgs", "()V");
    m.ir_method()->access_flags = kPublicStaticFinal;
    m.AddAnnotation(kJvmStatic, dex::kVisibilityBuild);
    ExpectNotFiltered(m, StaticMethod());
  }
  {
    SCOPED_TRACE("other annotation");
    output_ = Output();
    TestMethod m("noArgs", "()V");
    m.ir_method()->access_flags = kPublicStaticFinal;
    m.AddAnnotation("Lkotlin/jvm/JvmName;");
    ExpectNotFiltered(m, StaticMethod());
  }
}

// The annotation of another method of the class doesn't count.
TEST_F(KotlinJvmStaticFilterTest, ShouldNotTakeAnnotationOfOtherMethod) {
  TestMethod m("noArgs", "()V");
  m.ir_method()->access_flags = kPublicStaticFinal;
  m.AddAnnotation(kJvmStatic, dex::kVisibilityRuntime,
                  m.MethodRef(kTestClassName, "other", "()V")->ir_method);
  ExpectNotFiltered(m, StaticMethod());
}

// Dex sorts the annotations of a class by method, and those of a method by
// type, so @JvmStatic can come after those of other methods and after other
// annotations of the method, such as @Deprecated.
TEST_F(KotlinJvmStaticFilterTest, ShouldFindJvmStaticAmongOtherAnnotations) {
  TestMethod m("noArgs", "()V");
  m.ir_method()->access_flags = kPublicStaticFinal;
  m.AddAnnotation(kJvmStatic, dex::kVisibilityRuntime,
                  m.MethodRef(kTestClassName, "create", "()V")->ir_method);
  m.AddAnnotation("Lkotlin/Deprecated;");
  m.AddAnnotation(kJvmStatic);
  StaticMethodCode code = AddStaticMethod(m, StaticMethod());

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

// A named companion object, another class, and a load of another kind.
TEST_F(KotlinJvmStaticFilterTest, ShouldNotFilterOtherLoads) {
  struct Load {
    dex::Opcode load;
    const char* owner;
    const char* name;
    const char* type;
  };
  for (const Load& load : {
           Load{dex::OP_SGET_OBJECT, kTestClassName, "Factory",
                "LFoo$Factory;"},
           Load{dex::OP_SGET_OBJECT, "LBar;", "Companion", "LBar$Companion;"},
           Load{dex::OP_SGET, kTestClassName, "Companion", "I"},
       }) {
    SCOPED_TRACE(load.name);
    output_ = Output();
    TestMethod m("noArgs", "()V");
    MakeJvmStatic(m);
    StaticMethod s;
    s.load = load.load;
    s.field_owner = load.owner;
    s.field_name = load.name;
    s.field_type = load.type;
    ExpectNotFiltered(m, s);
  }
}

// The load of the companion object comes first.
TEST_F(KotlinJvmStaticFilterTest, ShouldNotFilterCodeBeforeLoad) {
  TestMethod m("noArgs", "()V");
  MakeJvmStatic(m);
  StaticMethod s;
  s.constant_before_load = true;
  ExpectNotFiltered(m, s);
}

// The load comes before any line of the method, as in the methods that Kotlin
// generates.
TEST_F(KotlinJvmStaticFilterTest, ShouldNotFilterLineOnLoad) {
  TestMethod m("noArgs", "()V");
  MakeJvmStatic(m);
  StaticMethod s;
  s.line_on_load = true;
  ExpectNotFiltered(m, s);
}

TEST_F(KotlinJvmStaticFilterTest, ShouldNotFilterMethodWithoutBytecode) {
  TestMethod m("noArgs", "()V");
  MakeJvmStatic(m);
  m.AddLine(kLastLine);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinJvmStaticFilterTest, ShouldRunOnKotlinClasses) {
  TestMethod m("noArgs", "()V");
  MakeJvmStatic(m);
  StaticMethodCode code = AddStaticMethod(m, StaticMethod());

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(code.last));
}

TEST_F(KotlinJvmStaticFilterTest, ShouldNotRunOnOtherClasses) {
  context_.class_annotations.clear();
  context_.source_file = "Foo.java";
  TestMethod m("noArgs", "()V");
  MakeJvmStatic(m);
  StaticMethodCode code = AddStaticMethod(m, StaticMethod());

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_FALSE(result.IsIgnored(code.last));
}

}  // namespace
}  // namespace coverage
