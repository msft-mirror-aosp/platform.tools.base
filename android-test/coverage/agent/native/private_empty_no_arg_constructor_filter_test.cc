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

#include "tools/base/android-test/coverage/agent/native/private_empty_no_arg_constructor_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kObject[] = "Ljava/lang/Object;";
constexpr dex::u4 kPrivateConstructor = dex::kAccPrivate | dex::kAccConstructor;

// Unit tests for PrivateEmptyNoArgConstructorFilter. The class under test is
// kTestClassName, a subclass of java.lang.Object.
class PrivateEmptyNoArgConstructorFilterTest : public FilterTestBase {
 protected:
  // Appends a call to the constructor of `owner` with the parameters
  // `signature` on v<reg>, and returns it.
  static lir::Instruction* AddConstructorCall(TestMethod& m, dex::u4 reg,
                                              const char* owner,
                                              const char* signature = "()V") {
    return m.Add(dex::OP_INVOKE_DIRECT,
                 {m.Regs({reg}), m.MethodRef(owner, "<init>", signature)});
  }

  PrivateEmptyNoArgConstructorFilter filter_;
};

// object Defaults
// The Kotlin compiler gives an object a private constructor that only calls
// the constructor of its super class.
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldFilterPrivateEmptyConstructor) {
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  m.AddLine(3);
  lir::Instruction* first = m.Last();
  AddConstructorCall(m, /*reg=*/0, kObject);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// When the agent grows the frame of the constructor to get a free register,
// `this` moves to the next register, and the agent copies it back after the
// call to the super constructor.
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldFilterConstructorWithCopyOfAgent) {
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  lir::Instruction* call = AddConstructorCall(m, /*reg=*/1, kObject);
  m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(0), m.Reg(1)});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{call, m.Last()}});
}

// class Registry private constructor() : Base()
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldFilterConstructorOfSubclass) {
  context_.super_class_name = "Lcom/example/Base;";
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  lir::Instruction* call = m.Add(
      dex::OP_INVOKE_DIRECT_RANGE,
      {m.RegRange(0, 1), m.MethodRef("Lcom/example/Base;", "<init>", "()V")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{call, m.Last()}});
}

TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterConstructorThatIsNotPrivate) {
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccConstructor;
  AddConstructorCall(m, /*reg=*/0, kObject);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// class Foo private constructor(name: String)
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterConstructorWithParameters) {
  TestMethod m("<init>", "(Ljava/lang/String;)V");
  m.ir_method()->access_flags = kPrivateConstructor;
  AddConstructorCall(m, /*reg=*/0, kObject);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// private Foo() { count = 1; }
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterConstructorWithBody) {
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  AddConstructorCall(m, /*reg=*/1, kObject);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Add(dex::OP_IPUT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "count", "I")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// private Foo() { this(1); }
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterConstructorCallingOtherConstructor) {
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 0}), m.MethodRef(kTestClassName, "<init>", "(I)V")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The only call is to the constructor without parameters of the class itself,
// not of its super class.
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterCallToConstructorOfOtherClass) {
  context_.super_class_name = "Lcom/example/Base;";
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  AddConstructorCall(m, /*reg=*/0, kObject);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The call passes `this` to another constructor of the super class.
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterCallToSuperConstructorWithParameters) {
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 0}),
         m.MethodRef(kObject, "<init>", "(Ljava/lang/Object;)V")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The call isn't to a constructor.
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterCallToOtherMethod) {
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0}), m.MethodRef(kObject, "init", "()V")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// A private method with the same code isn't a constructor.
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterPrivateMethodThatIsNotConstructor) {
  TestMethod m("reset", "()V");
  m.ir_method()->access_flags = dex::kAccPrivate;
  AddConstructorCall(m, /*reg=*/0, kObject);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Without the super class, the call can't be checked.
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterWithoutSuperClass) {
  context_.super_class_name.clear();
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  AddConstructorCall(m, /*reg=*/0, kObject);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The constructor throws instead of returning.
TEST_F(PrivateEmptyNoArgConstructorFilterTest,
       ShouldNotFilterConstructorThatDoesNotReturn) {
  TestMethod m("<init>", "()V");
  m.ir_method()->access_flags = kPrivateConstructor;
  AddConstructorCall(m, /*reg=*/0, kObject);
  m.Add(dex::OP_THROW, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
