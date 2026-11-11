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

#include "tools/base/android-test/coverage/agent/native/kotlin_compose_filter.h"

#include <vector>

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kComposer[] = "Landroidx/compose/runtime/Composer;";
constexpr char kComposerKt[] = "Landroidx/compose/runtime/ComposerKt;";
constexpr char kScopeUpdateScope[] =
    "Landroidx/compose/runtime/ScopeUpdateScope;";
constexpr char kLambda[] = "LFoo$0;";

class KotlinComposeFilterTest : public FilterTestBase {
 protected:
  // Adds a call of the method `name` of Composer on `regs`.
  lir::Bytecode* AddComposerCall(TestMethod& m, std::vector<dex::u4> regs,
                                 const char* name, const char* signature) {
    return m.Add(dex::OP_INVOKE_INTERFACE,
                 {m.Regs(regs), m.MethodRef(kComposer, name, signature)});
  }

  // Adds `if (isTraceInProgress()) traceEventStart(...)` and returns what the
  // filter ignores.
  Range AddTraceEventStart(TestMethod& m) {
    lir::Label* end = m.NewLabel();
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({}), m.MethodRef(kComposerKt, "isTraceInProgress", "()Z")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(2)});
    lir::Bytecode* check = m.Add(dex::OP_IF_EQZ, {m.Reg(2), m.Target(end)});
    m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(0)});
    m.Add(dex::OP_CONST_STRING, {m.Reg(3), m.StringRef("example")});
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({0, 1, 2, 3}), m.MethodRef(kComposerKt, "traceEventStart",
                                             "(IIILjava/lang/String;)V")});
    m.Place(end);
    return {check, end};
  }

  // Adds `if (isTraceInProgress()) traceEventEnd()` and returns what the
  // filter ignores.
  Range AddTraceEventEnd(TestMethod& m) {
    lir::Label* end = m.NewLabel();
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({}), m.MethodRef(kComposerKt, "isTraceInProgress", "()Z")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    lir::Bytecode* check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(end)});
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({}), m.MethodRef(kComposerKt, "traceEventEnd", "()V")});
    m.Place(end);
    return {check, end};
  }

  // Adds a call of endRestartGroup() and the null check `null_check` of its
  // result, and returns the null check.
  lir::Bytecode* AddEndRestartGroup(TestMethod& m, dex::u4 composer,
                                    dex::Opcode null_check,
                                    lir::Label* target) {
    AddComposerCall(m, {composer}, "endRestartGroup",
                    "()Landroidx/compose/runtime/ScopeUpdateScope;");
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    return m.Add(null_check, {m.Reg(0), m.Target(target)});
  }

  // Adds the code that creates the lambda and passes it to updateScope().
  lir::Bytecode* AddUpdateScopeCall(TestMethod& m) {
    m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef(kLambda)});
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({1}), m.MethodRef(kLambda, "<init>", "()V")});
    return m.Add(
        dex::OP_INVOKE_INTERFACE,
        {m.Regs({0, 1}), m.MethodRef(kScopeUpdateScope, "updateScope",
                                     "(Lkotlin/jvm/functions/Function2;)V")});
  }

  // Adds the whole `?.updateScope { ... }` and returns what the filter
  // ignores.
  Range AddUpdateScope(TestMethod& m, dex::u4 composer) {
    lir::Label* end = m.NewLabel();
    lir::Bytecode* check = AddEndRestartGroup(m, composer, dex::OP_IF_EQZ, end);
    AddUpdateScopeCall(m);
    m.Place(end);
    return {check, end};
  }

  // Adds `@Composable fun example() { body() }` with pausable composition,
  // and returns what the filter ignores.
  std::vector<Range> AddPausableComposable(TestMethod& m) {
    const dex::u4 composer = 2;
    const dex::u4 changed = 3;
    lir::Label* not_changed = m.NewLabel();
    lir::Label* decided = m.NewLabel();
    lir::Label* skip = m.NewLabel();
    lir::Label* end = m.NewLabel();
    lir::Bytecode* first = m.Add(dex::OP_CONST, {m.Reg(0), m.Literal(1234)});
    AddComposerCall(m, {composer, 0}, "startRestartGroup",
                    "(I)Landroidx/compose/runtime/Composer;");
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(composer)});
    m.Add(dex::OP_IF_EQZ, {m.Reg(changed), m.Target(not_changed)});
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
    m.Add(dex::OP_GOTO, {m.Target(decided)});
    m.Place(not_changed);
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    m.Place(decided);
    m.Add(dex::OP_AND_INT_LIT8, {m.Reg(1), m.Reg(changed), m.Literal(1)});
    AddComposerCall(m, {composer, 0, 1}, "shouldExecute", "(ZI)Z");
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    lir::Bytecode* check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(skip)});
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({composer}),
           m.MethodRef(kTestClassName, "body",
                       "(Landroidx/compose/runtime/Composer;)V")});
    m.Add(dex::OP_GOTO, {m.Target(end)});
    m.Place(skip);
    AddComposerCall(m, {composer}, "skipToGroupEnd", "()V");
    m.Place(end);
    Range update = AddUpdateScope(m, composer);
    m.Add(dex::OP_RETURN_VOID);
    return {{first, check}, update};
  }

  // Adds a jump with `jump_opcode` on `reg` over a call of body(), and the
  // return.
  void AddJumpOverBody(TestMethod& m, dex::Opcode jump_opcode, dex::u4 reg) {
    lir::Label* end = m.NewLabel();
    m.Add(jump_opcode, {m.Reg(reg), m.Target(end)});
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({2}), m.MethodRef(kTestClassName, "body",
                                    "(Landroidx/compose/runtime/Composer;)V")});
    m.Place(end);
    m.Add(dex::OP_RETURN_VOID);
  }

  KotlinComposeFilter filter_;
};

// @Composable fun example(x: Int) {
//   if (x < 0) return
//   example(x - 1)
// }
//
// D8 places the call to skipToGroupEnd() right after the check of
// $composer.skipping, before the body.
TEST_F(KotlinComposeFilterTest, ShouldFilterSkippableComposable) {
  const dex::u4 x = 4;
  const dex::u4 composer = 5;
  const dex::u4 changed = 6;
  TestMethod m("example", "(ILandroidx/compose/runtime/Composer;I)V");
  lir::Label* skip = m.NewLabel();
  lir::Label* body = m.NewLabel();
  lir::Label* recurse = m.NewLabel();
  lir::Label* end = m.NewLabel();
  lir::Bytecode* first = m.Add(dex::OP_CONST, {m.Reg(0), m.Literal(1234)});
  AddComposerCall(m, {composer, 0}, "startRestartGroup",
                  "(I)Landroidx/compose/runtime/Composer;");
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(composer)});
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(2), m.Reg(changed), m.Literal(3)});
  m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(2)});
  m.Add(dex::OP_IF_NE, {m.Reg(2), m.Reg(3), m.Target(body)});
  AddComposerCall(m, {composer}, "getSkipping", "()Z");
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(2)});
  lir::Bytecode* check = m.Add(dex::OP_IF_NEZ, {m.Reg(2), m.Target(skip)});
  m.Add(dex::OP_GOTO, {m.Target(body)});
  m.Place(skip);
  AddComposerCall(m, {composer}, "skipToGroupEnd", "()V");
  lir::Bytecode* skipped = m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(body);
  Range trace_start = AddTraceEventStart(m);
  m.Add(dex::OP_IF_GEZ, {m.Reg(x), m.Target(recurse)});
  Range early_trace_end = AddTraceEventEnd(m);
  Range early_update = AddUpdateScope(m, composer);
  m.Add(dex::OP_RETURN_VOID);
  m.Place(recurse);
  m.Add(dex::OP_ADD_INT_LIT8,
        {m.Reg(0), m.Reg(x), m.Literal(static_cast<dex::u4>(-1))});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0, composer, 1}),
         m.MethodRef(kTestClassName, "example",
                     "(ILandroidx/compose/runtime/Composer;I)V")});
  Range trace_end = AddTraceEventEnd(m);
  m.Place(end);
  Range update = AddUpdateScope(m, composer);
  lir::Bytecode* last = m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {{first, check},
                    {skip, skipped},
                    {end, last},
                    trace_start,
                    early_trace_end,
                    early_update,
                    trace_end,
                    update});
}

// Conventional layout where skipToGroupEnd() comes after the body rather than
// before it; the body between the check and :skip must not be ignored.
TEST_F(KotlinComposeFilterTest,
       ShouldFilterSkippableComposableWithSkipAfterBody) {
  const dex::u4 composer = 4;
  const dex::u4 changed = 5;
  TestMethod m("example", "(Landroidx/compose/runtime/Composer;I)V");
  lir::Label* body = m.NewLabel();
  lir::Label* skip = m.NewLabel();
  lir::Label* end = m.NewLabel();
  lir::Bytecode* first = m.Add(dex::OP_CONST, {m.Reg(0), m.Literal(1234)});
  AddComposerCall(m, {composer, 0}, "startRestartGroup",
                  "(I)Landroidx/compose/runtime/Composer;");
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(composer)});
  m.Add(dex::OP_AND_INT_LIT8, {m.Reg(2), m.Reg(changed), m.Literal(3)});
  m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(2)});
  m.Add(dex::OP_IF_NE, {m.Reg(2), m.Reg(3), m.Target(body)});
  AddComposerCall(m, {composer}, "getSkipping", "()Z");
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(2)});
  lir::Bytecode* check = m.Add(dex::OP_IF_NEZ, {m.Reg(2), m.Target(skip)});
  m.Place(body);
  Range trace_start = AddTraceEventStart(m);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({composer}),
         m.MethodRef(kTestClassName, "body",
                     "(Landroidx/compose/runtime/Composer;)V")});
  Range trace_end = AddTraceEventEnd(m);
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(skip);
  AddComposerCall(m, {composer}, "skipToGroupEnd", "()V");
  m.Place(end);
  Range update = AddUpdateScope(m, composer);
  lir::Bytecode* last = m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m,
                {{first, check}, {skip, last}, trace_start, trace_end, update});
}

// Composable lambda (`invoke(Composer, I)V`) without startRestartGroup() or
// endRestartGroup().
TEST_F(KotlinComposeFilterTest, ShouldFilterComposableLambda) {
  const dex::u4 composer = 4;
  const dex::u4 changed = 5;
  TestMethod m("invoke", "(Landroidx/compose/runtime/Composer;I)V");
  lir::Label* skip = m.NewLabel();
  lir::Label* body = m.NewLabel();
  lir::Label* end = m.NewLabel();
  lir::Bytecode* first =
      m.Add(dex::OP_AND_INT_LIT8, {m.Reg(0), m.Reg(changed), m.Literal(3)});
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(2)});
  m.Add(dex::OP_IF_NE, {m.Reg(0), m.Reg(1), m.Target(body)});
  AddComposerCall(m, {composer}, "getSkipping", "()Z");
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  lir::Bytecode* check = m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(skip)});
  m.Add(dex::OP_GOTO, {m.Target(body)});
  m.Place(skip);
  AddComposerCall(m, {composer}, "skipToGroupEnd", "()V");
  lir::Bytecode* skipped = m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(body);
  Range trace_start = AddTraceEventStart(m);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({composer}),
         m.MethodRef(kTestClassName, "body",
                     "(Landroidx/compose/runtime/Composer;)V")});
  Range trace_end = AddTraceEventEnd(m);
  m.Place(end);
  lir::Bytecode* last = m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(
      m,
      {{first, check}, {skip, skipped}, {end, last}, trace_start, trace_end});
}

// With pausable composition, the call to skipToGroupEnd() is kept.
TEST_F(KotlinComposeFilterTest, ShouldFilterPausableComposition) {
  TestMethod m("example", "(Landroidx/compose/runtime/Composer;I)V");
  std::vector<Range> expected = AddPausableComposable(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, expected);
}

TEST_F(KotlinComposeFilterTest, ShouldNotFilterMethodsWithoutComposer) {
  TestMethod m("example", "(Ljava/lang/Object;I)V");
  AddPausableComposable(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Checks the result of endRestartGroup() with if-nez instead of if-eqz.
TEST_F(KotlinComposeFilterTest, ShouldNotFilterUpdateScopeWithIfNez) {
  TestMethod m("example", "(Landroidx/compose/runtime/Composer;I)V");
  lir::Label* update = m.NewLabel();
  lir::Label* end = m.NewLabel();
  AddEndRestartGroup(m, 2, dex::OP_IF_NEZ, update);
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(update);
  AddUpdateScopeCall(m);
  m.Place(end);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The calls that the filter matches, but of another class.
TEST_F(KotlinComposeFilterTest, ShouldNotFilterCallsOfOtherClasses) {
  TestMethod m("example", "(Landroidx/compose/runtime/Composer;I)V");
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kTestClassName, "getSkipping", "()Z")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  AddJumpOverBody(m, dex::OP_IF_NEZ, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinComposeFilterTest, ShouldNotFilterOtherComposerCalls) {
  TestMethod m("example", "(Landroidx/compose/runtime/Composer;I)V");
  AddComposerCall(m, {2}, "getInserting", "()Z");
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  AddJumpOverBody(m, dex::OP_IF_NEZ, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinComposeFilterTest, ShouldNotFilterCallsWithOtherSignatures) {
  TestMethod m("example", "(Landroidx/compose/runtime/Composer;I)V");
  AddComposerCall(m, {2, 0}, "shouldExecute", "(Z)Z");
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  AddJumpOverBody(m, dex::OP_IF_EQZ, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The jump tests a value that is not the result of the call.
TEST_F(KotlinComposeFilterTest, ShouldNotFilterJumpsOnOtherValues) {
  TestMethod m("example", "(Landroidx/compose/runtime/Composer;I)V");
  AddComposerCall(m, {2}, "getSkipping", "()Z");
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  AddJumpOverBody(m, dex::OP_IF_NEZ, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinComposeFilterTest, ShouldNotFilterJumpsOnOtherRegisters) {
  TestMethod m("example", "(Landroidx/compose/runtime/Composer;I)V");
  AddComposerCall(m, {2}, "getSkipping", "()Z");
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  AddJumpOverBody(m, dex::OP_IF_NEZ, 1);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
