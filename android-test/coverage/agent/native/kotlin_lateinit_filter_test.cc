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

#include "tools/base/android-test/coverage/agent/native/kotlin_lateinit_filter.h"

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kString[] = "Ljava/lang/String;";
constexpr char kIntrinsics[] = "Lkotlin/jvm/internal/Intrinsics;";
constexpr char kThrowUninitialized[] =
    "throwUninitializedPropertyAccessException";
constexpr char kThrowUninitializedSignature[] = "(Ljava/lang/String;)V";

// Unit tests for KotlinLateinitFilter.
class KotlinLateinitFilterTest : public FilterTestBase {
 protected:
  // Appends the code that throws if the lateinit property `name` is read before
  // it is initialized.
  static void AddThrowUninitialized(TestMethod& m, dex::u4 reg,
                                    const char* name) {
    m.Add(dex::OP_CONST_STRING, {m.Reg(reg), m.StringRef(name)});
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({reg}), m.MethodRef(kIntrinsics, kThrowUninitialized,
                                      kThrowUninitializedSignature)});
  }

  KotlinLateinitFilter filter_;
};

// private lateinit var title: String
// fun readPrivate(): Int = title.length
TEST_F(KotlinLateinitFilterTest, ShouldFilterPrivatePropertyRead) {
  TestMethod m("readPrivate", "()I");
  lir::Label* initialized = m.NewLabel();
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "title", kString)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(initialized)});
  lir::Instruction* expected_from = m.Last();
  AddThrowUninitialized(m, 0, "title");
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  lir::Instruction* expected_to = m.Last();
  m.Place(initialized);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kString, "length", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{expected_from, expected_to}});
}

// lateinit var name: String
// Its getter, getName(), checks that it is initialized.
TEST_F(KotlinLateinitFilterTest, ShouldFilterGetter) {
  TestMethod m("getName", "()Ljava/lang/String;");
  lir::Label* uninitialized = m.NewLabel();
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "name", kString)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(uninitialized)});
  lir::Instruction* branch = m.Last();
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(uninitialized);
  lir::Instruction* expected_from = m.Last();
  AddThrowUninitialized(m, 0, "name");
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  lir::Instruction* expected_to = m.Last();

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{branch, branch}, Range{expected_from, expected_to}});
}

// fun readLocal(flag: Boolean): Int {
//   lateinit var local: String
//   if (flag) local = "x"
//   return local.length
// }
TEST_F(KotlinLateinitFilterTest, ShouldFilterLocalVariableRead) {
  TestMethod m("readLocal", "(Z)I");
  lir::Label* check = m.NewLabel();
  lir::Label* initialized = m.NewLabel();
  lir::Label* read = m.NewLabel();
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(3), m.Target(check)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("x")});
  m.Place(check);
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(initialized)});
  lir::Instruction* expected_from = m.Last();
  AddThrowUninitialized(m, 1, "local");
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_GOTO, {m.Target(read)});
  lir::Instruction* expected_to = m.Last();
  m.Place(initialized);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(0)});
  m.Place(read);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1}), m.MethodRef(kString, "length", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
  m.Add(dex::OP_RETURN, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  // The branch on `flag` is written by the user, so it is kept.
  ExpectIgnored(m, {Range{expected_from, expected_to}});
}

// The jump target can directly follow the call.
TEST_F(KotlinLateinitFilterTest, ShouldFilterReadWithoutInstructionAfterCall) {
  TestMethod m("read", "()Ljava/lang/String;");
  lir::Label* initialized = m.NewLabel();
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(2), m.FieldRef(kTestClassName, "title", kString)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(initialized)});
  lir::Instruction* expected_from = m.Last();
  AddThrowUninitialized(m, 1, "title");
  lir::Instruction* expected_to = m.Last();
  m.Place(initialized);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{expected_from, expected_to}});
}

// A getter can throw null after the call.
TEST_F(KotlinLateinitFilterTest, ShouldFilterGetterThatThrows) {
  TestMethod m("getName", "()Ljava/lang/String;");
  lir::Label* uninitialized = m.NewLabel();
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "name", kString)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(uninitialized)});
  lir::Instruction* branch = m.Last();
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(uninitialized);
  lir::Instruction* expected_from = m.Last();
  AddThrowUninitialized(m, 0, "name");
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_THROW, {m.Reg(0)});
  lir::Instruction* expected_to = m.Last();

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{branch, branch}, Range{expected_from, expected_to}});
}

// Large dex files need const-string/jumbo for string indexes above 65535, and
// invoke-static/range for registers above v15.
TEST_F(KotlinLateinitFilterTest, ShouldFilterJumboStringAndRangeCall) {
  TestMethod m("readPrivate", "()I");
  lir::Label* initialized = m.NewLabel();
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "title", kString)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(initialized)});
  lir::Instruction* expected_from = m.Last();
  m.Add(dex::OP_CONST_STRING_JUMBO, {m.Reg(16), m.StringRef("title")});
  m.Add(dex::OP_INVOKE_STATIC_RANGE,
        {m.RegRange(16, 1), m.MethodRef(kIntrinsics, kThrowUninitialized,
                                        kThrowUninitializedSignature)});
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  lir::Instruction* expected_to = m.Last();
  m.Place(initialized);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kString, "length", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{expected_from, expected_to}});
}

// fun isNameInitialized(): Boolean = ::name.isInitialized
TEST_F(KotlinLateinitFilterTest, ShouldNotFilterIsInitializedCheck) {
  TestMethod m("isNameInitialized", "()Z");
  lir::Label* uninitialized = m.NewLabel();
  lir::Label* end = m.NewLabel();
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "name", kString)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(uninitialized)});
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(uninitialized);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Place(end);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// A check is filtered only if it calls the static method
// Intrinsics.throwUninitializedPropertyAccessException(String).
TEST_F(KotlinLateinitFilterTest, ShouldNotFilterCallToOtherMethod) {
  TestMethod m("read", "()V");
  lir::Label* skip_other_name = m.NewLabel();
  lir::Label* skip_other_class = m.NewLabel();
  lir::Label* skip_other_signature = m.NewLabel();
  lir::Label* skip_virtual_call = m.NewLabel();
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(2), m.FieldRef(kTestClassName, "title", kString)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(skip_other_name)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef("title")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({1}), m.MethodRef(kIntrinsics, "throwUninitializedProperty",
                                  kThrowUninitializedSignature)});
  m.Place(skip_other_name);
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(skip_other_class)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef("title")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({1}), m.MethodRef(kTestClassName, kThrowUninitialized,
                                  kThrowUninitializedSignature)});
  m.Place(skip_other_class);
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(skip_other_signature)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef("title")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({1}), m.MethodRef(kIntrinsics, kThrowUninitialized,
                                  "(Ljava/lang/Object;)V")});
  m.Place(skip_other_signature);
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(skip_virtual_call)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef("title")});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1}), m.MethodRef(kIntrinsics, kThrowUninitialized,
                                  kThrowUninitializedSignature)});
  m.Place(skip_virtual_call);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Generated code always loads the property name with const-string right before
// the call.
TEST_F(KotlinLateinitFilterTest, ShouldNotFilterCallWithoutPropertyName) {
  TestMethod m("read", "()Ljava/lang/String;");
  lir::Label* initialized = m.NewLabel();
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(2), m.FieldRef(kTestClassName, "title", kString)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(initialized)});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({1}), m.MethodRef(kIntrinsics, kThrowUninitialized,
                                  kThrowUninitializedSignature)});
  m.Place(initialized);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Generated code has at most two instructions between the call and the jump
// target, so a check with more is not filtered.
TEST_F(KotlinLateinitFilterTest, ShouldNotFilterMoreCodeAfterCall) {
  TestMethod m("readPrivate", "()I");
  lir::Label* initialized = m.NewLabel();
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(2), m.FieldRef(kTestClassName, "title", kString)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(initialized)});
  AddThrowUninitialized(m, 1, "title");
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN, {m.Reg(1)});
  m.Place(initialized);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kString, "length", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
