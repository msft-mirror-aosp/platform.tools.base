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

#include "tools/base/android-test/coverage/agent/native/assert_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

namespace coverage {
namespace {

constexpr char kClass[] = "Ljava/lang/Class;";
constexpr char kAssertionsDisabled[] = "$assertionsDisabled";
constexpr char kAssertionError[] = "Ljava/lang/AssertionError;";

// What a test changes in the code of AddStatus().
struct Status {
  // Whether const-class loads the class, rather than another bytecode.
  bool const_class = true;
  // The register that desiredAssertionStatus() is called on, the number of
  // registers that the call passes from it on, and whether it passes them in
  // a range.
  dex::u4 receiver = 0;
  int count = 1;
  bool range = false;
  // The class, the name and the signature of the method that is called.
  const char* method_owner = kClass;
  const char* method_name = "desiredAssertionStatus";
  const char* method_signature = "()Z";
  // The opcode that moves the result, the register that it writes, and
  // whether a call comes after it.
  dex::Opcode move = dex::OP_MOVE_RESULT;
  dex::u4 result = 0;
  bool call_after_move = false;
  // Whether the negation is a xor with a constant, rather than with a literal.
  bool xor_constant = false;
  // Whether D8 shares the constant with earlier code, which loads it before
  // the class, and whether a bytecode writes its register again or a label
  // comes before the class then.
  bool shared_constant = false;
  bool constant_written = false;
  bool label_before_class = false;
  // Whether D8 loads a 1 for other code right before the negation.
  bool other_constant = false;
  // The value of the literal or of the constant, the register that the xor
  // with a constant takes it from, and the register that the xor writes.
  dex::s4 value = 1;
  dex::u4 constant = 1;
  dex::u4 negated = 0;
  // The class and the name of the field that sput-boolean writes, and the
  // register that it writes.
  const char* field_owner = kTestClassName;
  const char* field_name = kAssertionsDisabled;
  dex::u4 stored = 0;
};

// What a test changes in the code of AddAssert().
struct Assert {
  // Whether the statement reads the field, rather than branching on v0 that
  // already holds it, and the opcode, the class and the name of the field that
  // it reads.
  bool read = true;
  dex::Opcode get = dex::OP_SGET_BOOLEAN;
  const char* field_owner = kTestClassName;
  const char* field_name = kAssertionsDisabled;
  // The register that D8 loads a string that later code shares to between the
  // read and the branch, if any.
  int string = -1;
  // The opcode of the branch and the register that it tests.
  dex::Opcode branch = dex::OP_IF_NEZ;
  dex::u4 tested = 0;
};

// The bytecodes of AddStatus() that set the field.
struct StatusCode {
  lir::Instruction* first = nullptr;
  lir::Instruction* last = nullptr;
};

// Unit tests for AssertFilter. The class under test is kTestClassName, a Java
// class unless a test says otherwise.
class AssertFilterTest : public FilterTestBase {
 protected:
  // Appends the code that the static initializer of Foo has for its field
  // $assertionsDisabled, as D8 passes it through, changed as `s` says:
  //   const-class v0, LFoo;
  //   invoke-virtual {v0}, Ljava/lang/Class;->desiredAssertionStatus()Z
  //   move-result v0
  //   xor-int/lit8 v0, v0, 1  (or const/4 v1, 1 and xor-int/2addr v0, v1)
  //   sput-boolean v0, LFoo;->$assertionsDisabled:Z
  static StatusCode AddStatus(TestMethod& m, const Status& s,
                              const char* loaded_class = kTestClassName) {
    if (s.shared_constant) {
      // The ordinal of the second constant of an enum class.
      m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(s.value)});
      m.Add(dex::OP_INVOKE_DIRECT,
            {m.Regs({2, 3, 1}),
             m.MethodRef(kTestClassName, "<init>", "(Ljava/lang/String;I)V")});
      if (s.constant_written) {
        m.Add(dex::OP_SGET,
              {m.Reg(1), m.FieldRef(kTestClassName, "counter", "I")});
      }
      if (s.label_before_class) {
        m.Place(m.NewLabel());
      }
    }
    StatusCode code;
    code.first =
        s.const_class
            ? m.Add(dex::OP_CONST_CLASS, {m.Reg(0), m.TypeRef(loaded_class)})
            : m.Add(dex::OP_SGET_OBJECT,
                    {m.Reg(0), m.FieldRef(kTestClassName, "type", kClass)});
    std::vector<dex::u4> passed;
    for (int i = 0; i < s.count; ++i) {
      passed.push_back(s.receiver + i);
    }
    m.Add(s.range ? dex::OP_INVOKE_VIRTUAL_RANGE : dex::OP_INVOKE_VIRTUAL,
          {s.range ? static_cast<lir::Operand*>(m.RegRange(s.receiver, s.count))
                   : m.Regs(passed),
           m.MethodRef(s.method_owner, s.method_name, s.method_signature)});
    m.Add(s.move, {m.Reg(s.result)});
    if (s.call_after_move) {
      m.Add(dex::OP_INVOKE_STATIC,
            {m.Regs({}), m.MethodRef(kTestClassName, "run", "()V")});
    }
    if (s.xor_constant) {
      if (!s.shared_constant) {
        m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(s.value)});
      }
      if (s.other_constant) {
        m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(1)});
      }
      m.Add(dex::OP_XOR_INT_2ADDR, {m.Reg(s.negated), m.Reg(s.constant)});
    } else {
      m.Add(dex::OP_XOR_INT_LIT8,
            {m.Reg(s.negated), m.Reg(0), m.Literal(s.value)});
    }
    code.last =
        m.Add(dex::OP_SPUT_BOOLEAN,
              {m.Reg(s.stored), m.FieldRef(s.field_owner, s.field_name, "Z")});
    return code;
  }

  // Appends `assert x > 0;` with x in v2, as javac compiles it, changed as `a`
  // says, and returns the branch on the field:
  //   sget-boolean v0, LFoo;->$assertionsDisabled:Z
  //   if-nez v0, :after
  //   if-gtz v2, :after
  //   new-instance v0, Ljava/lang/AssertionError;
  //   invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
  //   throw v0
  // :after
  static lir::Instruction* AddAssert(TestMethod& m, const Assert& a) {
    lir::Label* after = m.NewLabel();
    if (a.read) {
      m.Add(a.get, {m.Reg(0), m.FieldRef(a.field_owner, a.field_name, "Z")});
    }
    if (a.string >= 0) {
      m.Add(dex::OP_CONST_STRING,
            {m.Reg(a.string), m.StringRef("Assertion failed")});
    }
    lir::Instruction* branch =
        m.Add(a.branch, {m.Reg(a.tested), m.Target(after)});
    m.Add(dex::OP_IF_GTZ, {m.Reg(2), m.Target(after)});
    m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kAssertionError)});
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({0}), m.MethodRef(kAssertionError, "<init>", "()V")});
    m.Add(dex::OP_THROW, {m.Reg(0)});
    m.Place(after);
    return branch;
  }

  // Appends the read of the field $assertionsDisabled of Foo to `reg`.
  static void AddRead(TestMethod& m, dex::u4 reg) {
    m.Add(dex::OP_SGET_BOOLEAN,
          {m.Reg(reg), m.FieldRef(kTestClassName, kAssertionsDisabled, "Z")});
  }

  // Runs the filter on the static initializer that AddStatus() appends with
  // `s`, and expects it to ignore nothing.
  void ExpectStatusNotFiltered(const Status& s) {
    TestMethod m("<clinit>", "()V");
    AddStatus(m, s);
    m.Add(dex::OP_RETURN_VOID);

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

    ExpectIgnored(m, {});
  }

  // Runs the filter on a method with the statement that AddAssert() appends
  // with `a`, and expects it to ignore nothing.
  void ExpectAssertNotFiltered(const Assert& a) {
    TestMethod m("check", "(I)V");
    ExpectAssertNotFiltered(m, a);
  }

  // Appends to `m` the statement that AddAssert() appends with `a`, runs the
  // filter on `m`, and expects it to ignore nothing.
  void ExpectAssertNotFiltered(TestMethod& m, const Assert& a) {
    AddAssert(m, a);
    m.Add(dex::OP_RETURN_VOID);

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

    ExpectIgnored(m, {});
  }

  AssertFilter filter_;
};

// class Inner { void check(int x) { assert x != 0; } }: the field of the
// nested class takes the status of the top-level class.
TEST_F(AssertFilterTest, ShouldFilterStatusInStaticInitializer) {
  TestMethod m("<clinit>", "()V");
  StatusCode code = AddStatus(m, Status(), /*loaded_class=*/"LOuter;");
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

// static int counter = 1: D8 shares the constant 1 of the negation with the
// initializer of the other field.
TEST_F(AssertFilterTest, ShouldFilterStatusNegatedWithConstant) {
  TestMethod m("<clinit>", "()V");
  Status s;
  s.xor_constant = true;
  StatusCode code = AddStatus(m, s);
  m.AddLine(8);
  m.Add(dex::OP_SPUT, {m.Reg(1), m.FieldRef(kTestClassName, "counter", "I")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

// enum class Foo { A, B }: Kotlin sets the field after the constants, and D8
// shares the ordinal of B, 1, with the negation.
TEST_F(AssertFilterTest, ShouldFilterStatusNegatedWithSharedConstant) {
  TestMethod m("<clinit>", "()V");
  Status s;
  s.xor_constant = true;
  s.shared_constant = true;
  StatusCode code = AddStatus(m, s);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

// D8 calls desiredAssertionStatus() with a range when the register of the
// class is above v15.
TEST_F(AssertFilterTest, ShouldFilterStatusAskedWithRange) {
  TestMethod m("<clinit>", "()V");
  Status s;
  s.range = true;
  StatusCode code = AddStatus(m, s);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}});
}

TEST_F(AssertFilterTest, ShouldFilterBranchOnField) {
  TestMethod m("check", "(I)V");
  lir::Instruction* branch = AddAssert(m, Assert());
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{branch, branch}});
}

TEST_F(AssertFilterTest, ShouldFilterBranchOfEachStatement) {
  TestMethod m("check", "(I)V");
  lir::Instruction* first = AddAssert(m, Assert());
  lir::Instruction* second = AddAssert(m, Assert());
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, first}, Range{second, second}});
}

// assert(x > 0); assert(y > 0) in Kotlin: D8 loads the message that both
// statements share between the read of the first one and its branch.
TEST_F(AssertFilterTest, ShouldFilterBranchAfterSharedString) {
  TestMethod m("check", "(I)V");
  Assert a;
  a.string = 1;
  lir::Instruction* branch = AddAssert(m, a);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{branch, branch}});
}

// void fail() { assert false; }: D8 puts the throw after the return, and
// branches to it when assertions are enabled.
TEST_F(AssertFilterTest, ShouldFilterBranchToThrow) {
  TestMethod m("fail", "()V");
  lir::Label* fail = m.NewLabel();
  AddRead(m, 0);
  lir::Instruction* branch = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(fail)});
  m.Add(dex::OP_RETURN_VOID);
  m.Place(fail);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kAssertionError)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0}), m.MethodRef(kAssertionError, "<init>", "()V")});
  m.Add(dex::OP_THROW, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{branch, branch}});
}

// assert x > 0; assert x > 0; in a release build: D8 reads the field once, and
// the second statement branches on the register that holds it.
TEST_F(AssertFilterTest, ShouldFilterBranchOnFieldKeptInRegister) {
  TestMethod m("check", "(I)V");
  lir::Instruction* first = AddAssert(m, Assert());
  Assert a;
  a.read = false;
  lir::Instruction* second = AddAssert(m, a);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, first}, Range{second, second}});
}

// After a try-catch statement in a release build, D8 moves the field from the
// register of the handler to the one that the other way holds it in.
TEST_F(AssertFilterTest, ShouldFilterBranchOnFieldMovedToOtherRegister) {
  TestMethod m("check", "(I)V");
  AddRead(m, 1);
  m.Add(dex::OP_MOVE, {m.Reg(0), m.Reg(1)});
  Assert a;
  a.read = false;
  lir::Instruction* branch = AddAssert(m, a);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{branch, branch}});
}

// if (y > 3) { assert x > 0; } else { assert x > 0; } in a release build: D8
// reads the field once before the if statement.
TEST_F(AssertFilterTest, ShouldFilterBranchesOnFieldAfterJumps) {
  TestMethod m("check", "(II)V");
  lir::Label* otherwise = m.NewLabel();
  lir::Label* end = m.NewLabel();
  AddRead(m, 0);
  m.Add(dex::OP_IF_LE, {m.Reg(3), m.Reg(4), m.Target(otherwise)});
  Assert a;
  a.read = false;
  lir::Instruction* first = AddAssert(m, a);
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(otherwise);
  lir::Instruction* second = AddAssert(m, a);
  m.Place(end);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, first}, Range{second, second}});
}

// assert x > 0; switch (y) { case 0: assert x > 0; } in a release build: the
// statement in the case branches on the register that holds the field.
TEST_F(AssertFilterTest, ShouldFilterBranchOnFieldInCase) {
  for (bool sparse : {false, true}) {
    SCOPED_TRACE(sparse ? "sparse switch" : "packed switch");
    output_ = Output();
    TestMethod m("check", "(II)V");
    lir::Label* payload = m.NewLabel();
    lir::Label* zero = m.NewLabel();
    lir::Label* end = m.NewLabel();
    lir::Instruction* first = AddAssert(m, Assert());
    m.Add(sparse ? dex::OP_SPARSE_SWITCH : dex::OP_PACKED_SWITCH,
          {m.Reg(3), m.Target(payload)});
    m.Add(dex::OP_GOTO, {m.Target(end)});
    m.Place(zero);
    Assert a;
    a.read = false;
    lir::Instruction* second = AddAssert(m, a);
    m.Place(end);
    m.Add(dex::OP_RETURN_VOID);
    m.Place(payload);
    if (sparse) {
      // Debug information can come before the payload.
      m.AddLine(30);
      m.AddSparseSwitchPayload({{0, zero}});
    } else {
      m.AddPackedSwitchPayload(0, {zero});
    }

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

    ExpectIgnored(m, {Range{first, first}, Range{second, second}});
  }
}

// static { assert counter > 0; } in a release build: D8 branches on the status
// that the static initializer negates for the field, or on the value that it
// stores.
TEST_F(AssertFilterTest, ShouldFilterBranchesOnStatusInStaticInitializer) {
  TestMethod m("<clinit>", "()V");
  Status s;
  s.negated = 1;
  s.stored = 1;
  StatusCode code = AddStatus(m, s);
  Assert status;
  status.read = false;
  status.branch = dex::OP_IF_EQZ;
  lir::Instruction* first = AddAssert(m, status);
  Assert stored;
  stored.read = false;
  stored.tested = 1;
  lir::Instruction* second = AddAssert(m, stored);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}, Range{first, first},
                    Range{second, second}});
}

// static { assert counter > 0; }
TEST_F(AssertFilterTest, ShouldFilterStatementInStaticInitializer) {
  TestMethod m("<clinit>", "()V");
  StatusCode code = AddStatus(m, Status());
  lir::Instruction* branch = AddAssert(m, Assert());
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{code.first, code.last}, Range{branch, branch}});
}

// Only the static initializer sets the field.
TEST_F(AssertFilterTest, ShouldNotFilterStatusInOtherMethods) {
  TestMethod m("init", "()V");
  AddStatus(m, Status());
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(AssertFilterTest, ShouldNotFilterOtherStatusCode) {
  {
    SCOPED_TRACE("class not loaded with const-class");
    Status s;
    s.const_class = false;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("call on another register");
    output_ = Output();
    Status s;
    s.receiver = 1;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("other method");
    output_ = Output();
    Status s;
    s.method_name = "isInterface";
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("method of another class");
    output_ = Output();
    Status s;
    s.method_owner = "LBar;";
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("method with another signature");
    output_ = Output();
    Status s;
    s.method_signature = "()I";
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("result moved as an object");
    output_ = Output();
    Status s;
    s.move = dex::OP_MOVE_RESULT_OBJECT;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("negation of another register");
    output_ = Output();
    Status s;
    s.result = 1;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("negation of another register with a constant");
    output_ = Output();
    Status s;
    s.xor_constant = true;
    s.result = 2;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("status stored without the negation");
    output_ = Output();
    Status s;
    s.negated = 1;
    s.stored = 0;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("status stored without the negation with a constant");
    output_ = Output();
    Status s;
    s.xor_constant = true;
    s.negated = 2;
    s.stored = 0;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("1 for other code before the negation with a shared constant");
    output_ = Output();
    Status s;
    s.xor_constant = true;
    s.shared_constant = true;
    s.other_constant = true;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("call before the negation with a shared constant");
    output_ = Output();
    Status s;
    s.xor_constant = true;
    s.shared_constant = true;
    s.call_after_move = true;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("call with a range of two registers");
    output_ = Output();
    Status s;
    s.range = true;
    s.count = 2;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("call with two registers");
    output_ = Output();
    Status s;
    s.count = 2;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("xor with another literal");
    output_ = Output();
    Status s;
    s.value = 2;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("xor with another constant");
    output_ = Output();
    Status s;
    s.xor_constant = true;
    s.value = 0;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("xor with another register than the constant");
    output_ = Output();
    Status s;
    s.xor_constant = true;
    s.constant = 2;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("shared constant written again");
    output_ = Output();
    Status s;
    s.xor_constant = true;
    s.shared_constant = true;
    s.constant_written = true;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("shared constant before a label");
    output_ = Output();
    Status s;
    s.xor_constant = true;
    s.shared_constant = true;
    s.label_before_class = true;
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("field of another class");
    output_ = Output();
    Status s;
    s.field_owner = "LBar;";
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("other field");
    output_ = Output();
    Status s;
    s.field_name = "$disabled";
    ExpectStatusNotFiltered(s);
  }
  {
    SCOPED_TRACE("other register stored");
    output_ = Output();
    Status s;
    s.stored = 1;
    ExpectStatusNotFiltered(s);
  }
}

TEST_F(AssertFilterTest, ShouldNotFilterOtherBranches) {
  {
    SCOPED_TRACE("field of another class");
    Assert a;
    a.field_owner = "LBar;";
    ExpectAssertNotFiltered(a);
  }
  {
    SCOPED_TRACE("other field");
    output_ = Output();
    Assert a;
    a.field_name = "$disabled";
    ExpectAssertNotFiltered(a);
  }
  {
    SCOPED_TRACE("read of an int");
    output_ = Output();
    Assert a;
    a.get = dex::OP_SGET;
    ExpectAssertNotFiltered(a);
  }
  {
    SCOPED_TRACE("string loaded to the register before the branch");
    output_ = Output();
    Assert a;
    a.string = 0;
    ExpectAssertNotFiltered(a);
  }
  {
    SCOPED_TRACE("branch on another register");
    output_ = Output();
    Assert a;
    a.tested = 2;
    ExpectAssertNotFiltered(a);
  }
  {
    SCOPED_TRACE("other branch");
    output_ = Output();
    Assert a;
    a.branch = dex::OP_IF_LTZ;
    ExpectAssertNotFiltered(a);
  }
}

// The register only holds the field on some of the ways to the branch.
TEST_F(AssertFilterTest, ShouldNotFilterBranchReachedWithoutField) {
  Assert a;
  a.read = false;
  {
    SCOPED_TRACE("jump without the field");
    TestMethod m("check", "(IZZ)V");
    lir::Label* join = m.NewLabel();
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    m.Add(dex::OP_IF_EQZ, {m.Reg(3), m.Target(join)});
    AddRead(m, 0);
    m.Place(join);
    ExpectAssertNotFiltered(m, a);
  }
  {
    SCOPED_TRACE("code that falls through without the field");
    output_ = Output();
    TestMethod m("check", "(IZZ)V");
    lir::Label* join = m.NewLabel();
    AddRead(m, 0);
    m.Add(dex::OP_IF_EQZ, {m.Reg(3), m.Target(join)});
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    m.Place(join);
    ExpectAssertNotFiltered(m, a);
  }
  {
    SCOPED_TRACE("first of two jumps without the field");
    output_ = Output();
    TestMethod m("check", "(IZZ)V");
    lir::Label* join = m.NewLabel();
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    m.Add(dex::OP_IF_EQZ, {m.Reg(3), m.Target(join)});
    AddRead(m, 0);
    m.Add(dex::OP_IF_EQZ, {m.Reg(4), m.Target(join)});
    m.Add(dex::OP_RETURN_VOID);
    m.Place(join);
    ExpectAssertNotFiltered(m, a);
  }
}

// The code that jumps back to the branch writes the register first.
TEST_F(AssertFilterTest, ShouldNotFilterBranchReachedFromLaterCode) {
  TestMethod m("check", "(I)V");
  lir::Label* loop = m.NewLabel();
  AddRead(m, 0);
  m.Place(loop);
  Assert a;
  a.read = false;
  AddAssert(m, a);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_GOTO, {m.Target(loop)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// An exception can reach the handler where the register holds 0.
TEST_F(AssertFilterTest, ShouldNotFilterBranchInHandler) {
  for (bool catch_all : {false, true}) {
    SCOPED_TRACE(catch_all ? "handler of all exceptions" : "handler of a type");
    output_ = Output();
    TestMethod m("check", "(I)V");
    lir::Label* handler = m.NewLabel();
    lir::TryBlockBegin* begin = m.AddTryBlockBegin();
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({}), m.MethodRef(kTestClassName, "run", "()V")});
    m.AddTryBlockEnd(begin, handler,
                     catch_all ? nullptr : "Ljava/lang/RuntimeException;");
    AddRead(m, 0);
    m.Place(handler);
    Assert a;
    a.read = false;
    ExpectAssertNotFiltered(m, a);
  }
}

// The switch can jump to the case where the register holds 0.
TEST_F(AssertFilterTest, ShouldNotFilterBranchInCaseReachedWithoutField) {
  TestMethod m("check", "(II)V");
  lir::Label* payload = m.NewLabel();
  lir::Label* zero = m.NewLabel();
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(3), m.Target(payload)});
  AddRead(m, 0);
  m.Place(zero);
  Assert a;
  a.read = false;
  AddAssert(m, a);
  m.Add(dex::OP_RETURN_VOID);
  m.Place(payload);
  m.AddPackedSwitchPayload(0, {zero});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// A wide constant in v0 and v1 writes over the field in v1.
TEST_F(AssertFilterTest, ShouldNotFilterBranchOnFieldWrittenByPair) {
  TestMethod m("check", "(I)V");
  AddRead(m, 1);
  m.Add(dex::OP_CONST_WIDE_16,
        {m.code_ir().Alloc<lir::VRegPair>(0), m.Literal(0)});
  Assert a;
  a.read = false;
  a.tested = 1;
  ExpectAssertNotFiltered(m, a);
}

// Without the cases of a switch, the registers at them aren't known, so no
// branch is ignored.
TEST_F(AssertFilterTest, ShouldNotFilterBranchesWithoutCasesOfSwitch) {
  TestMethod m("check", "(II)V");
  lir::Label* zero = m.NewLabel();
  AddAssert(m, Assert());
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(3), m.Target(zero)});
  m.Place(zero);
  Assert a;
  a.read = false;
  ExpectAssertNotFiltered(m, a);
}

TEST_F(AssertFilterTest, ShouldNotFilterBranchOnOtherMovedValues) {
  {
    SCOPED_TRACE("move of another register");
    TestMethod m("check", "(I)V");
    AddRead(m, 1);
    m.Add(dex::OP_MOVE, {m.Reg(0), m.Reg(3)});
    Assert a;
    a.read = false;
    ExpectAssertNotFiltered(m, a);
  }
  {
    SCOPED_TRACE("move over the field");
    output_ = Output();
    TestMethod m("check", "(I)V");
    AddRead(m, 0);
    m.Add(dex::OP_MOVE, {m.Reg(0), m.Reg(3)});
    Assert a;
    a.read = false;
    ExpectAssertNotFiltered(m, a);
  }
}

TEST_F(AssertFilterTest, ShouldRunOnJavaClasses) {
  TestMethod m("check", "(I)V");
  lir::Instruction* branch = AddAssert(m, Assert());
  m.Add(dex::OP_RETURN_VOID);

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(branch));
  EXPECT_FALSE(result.IsIgnored(branch->next));  // The check of x.
}

// Kotlin generates the same code for assert() with -Xassertions=jvm.
TEST_F(AssertFilterTest, ShouldRunOnKotlinClasses) {
  context_.class_annotations.insert(kKotlinMetadataDescriptor);
  context_.source_file = "Foo.kt";
  TestMethod m("check", "(I)V");
  m.AddLine(3);  // The start line.
  m.AddLine(3);
  lir::Instruction* branch = AddAssert(m, Assert());
  m.Add(dex::OP_RETURN_VOID);

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(branch));
  EXPECT_FALSE(result.IsIgnored(branch->next));  // The check of x.
}

}  // namespace
}  // namespace coverage
