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

#include "tools/base/android-test/coverage/agent/native/synthetic_method_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

// Unit tests for SyntheticMethodFilter. The class under test is
// kTestClassName, unless a test names a companion class in context_.
class SyntheticMethodFilterTest : public FilterTestBase {
 protected:
  // Appends a call to the static method user() of kTestClassName, then
  // return-void.
  static void AddCallToUser(TestMethod& m) {
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({}), m.MethodRef(kTestClassName, "user", "()V")});
    m.Add(dex::OP_RETURN_VOID);
  }

  SyntheticMethodFilter filter_;
};

// interface Command { default void invoke() { user(); } }
// class Foo implements Command {}
// When D8 desugars the default method, it moves its code to the companion
// class Command$-CC and gives Foo a synthetic invoke() that calls it, without
// lines.
TEST_F(SyntheticMethodFilterTest,
       ShouldFilterMethodThatCallsDesugaredDefaultMethod) {
  TestMethod m("invoke", "()V");
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccSynthetic;
  lir::Instruction* first =
      m.Add(dex::OP_INVOKE_STATIC,
            {m.Regs({1}),
             m.MethodRef("LCommand$-CC;", "$default$invoke", "(LCommand;)V")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// Runnable run() { return () -> print(); }
// When the body of a lambda calls an instance method, it is an instance method
// too, which D8 calls through a synthetic accessor whose name contains lambda$
// but doesn't start with it.
TEST_F(SyntheticMethodFilterTest, ShouldFilterAccessorOfLambdaBody) {
  TestMethod m("$r8$lambda$94kupmzo68baTWeuwJma4zYq8is", "(LFoo;)V");
  m.ir_method()->access_flags =
      dex::kAccPublic | dex::kAccStatic | dex::kAccSynthetic;
  lir::Instruction* first =
      m.Add(dex::OP_INVOKE_DIRECT,
            {m.Regs({0}), m.MethodRef(kTestClassName, "lambda$run$0", "()V")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// Runnable run() { return () -> user(); }
// javac compiles the body of the lambda into a synthetic method.
TEST_F(SyntheticMethodFilterTest, ShouldNotFilterBodyOfLambda) {
  TestMethod m("lambda$run$0", "()V");
  m.ir_method()->access_flags = dex::kAccStatic | dex::kAccSynthetic;
  m.AddLine(2);
  AddCallToUser(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// def run(): () => Unit = () => user()
// Scala compiles the body of an anonymous function into a synthetic method.
TEST_F(SyntheticMethodFilterTest, ShouldNotFilterBodyOfScalaAnonymousFunction) {
  TestMethod m("$anonfun$run$1", "()V");
  m.ir_method()->access_flags =
      dex::kAccPublic | dex::kAccStatic | dex::kAccFinal | dex::kAccSynthetic;
  m.AddLine(2);
  AddCallToUser(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// void invoke() { user(); }
TEST_F(SyntheticMethodFilterTest, ShouldNotFilterMethodThatIsNotSynthetic) {
  TestMethod m("invoke", "()V");
  m.ir_method()->access_flags = dex::kAccPublic;
  m.AddLine(2);
  AddCallToUser(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// interface Command {
//   void invoke();
//   default Runnable later() { return () -> invoke(); }
// }
// When D8 desugars the default method, it moves the body of the lambda, an
// instance method, to the companion class Command$-CC as a static method that
// takes the Command, and renames it.
TEST_F(SyntheticMethodFilterTest, ShouldNotFilterMethodOfCompanionOfInterface) {
  context_.class_name = "LCommand$-CC;";
  TestMethod m("$private$lambda$later$0", "(LCommand;)V");
  m.ir_method()->access_flags =
      dex::kAccPublic | dex::kAccStatic | dex::kAccSynthetic;
  m.AddLine(3);
  m.Add(dex::OP_INVOKE_INTERFACE,
        {m.Regs({0}), m.MethodRef("LCommand;", "invoke", "()V")});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
