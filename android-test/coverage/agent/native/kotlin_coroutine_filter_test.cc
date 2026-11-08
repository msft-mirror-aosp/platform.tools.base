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

#include "tools/base/android-test/coverage/agent/native/kotlin_coroutine_filter.h"

#include <vector>

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kObject[] = "Ljava/lang/Object;";
constexpr char kContinuation[] = "Lkotlin/coroutines/Continuation;";
constexpr char kSuspendingSignature[] =
    "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;";
constexpr char kIllegalStateException[] = "Ljava/lang/IllegalStateException;";
constexpr char kResumeBeforeInvoke[] =
    "call to 'resume' before 'invoke' with coroutine";
// The continuation of the method under test: its own class for a suspending
// function, or the class of a suspending lambda.
constexpr char kCoroutine[] = "LFoo$run$1;";

// Unit tests for KotlinCoroutineFilter.
class KotlinCoroutineFilterTest : public FilterTestBase {
 protected:
  // Appends the call that returns the marker of a suspension, followed by a
  // move of the marker to `reg` unless `reg` is negative. Returns the call.
  static lir::Bytecode* AddGetCoroutineSuspended(TestMethod& m, int reg) {
    lir::Bytecode* call =
        m.Add(dex::OP_INVOKE_STATIC,
              {m.Regs({}),
               m.MethodRef("Lkotlin/coroutines/intrinsics/IntrinsicsKt;",
                           "getCOROUTINE_SUSPENDED", "()Ljava/lang/Object;")});
    if (reg >= 0) {
      m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(reg)});
    }
    return call;
  }

  // Appends the code at the start of a suspending function that gets the
  // continuation of a resumed call, or creates one, into v0. The function
  // receives its continuation in v5. Returns the first instruction.
  static lir::Bytecode* AddContinuationPrologue(TestMethod& m) {
    lir::Label* create = m.NewLabel();
    lir::Label* created = m.NewLabel();
    lir::Bytecode* first =
        m.Add(dex::OP_INSTANCE_OF, {m.Reg(0), m.Reg(5), m.TypeRef(kCoroutine)});
    m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(create)});
    m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(5)});
    m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef(kCoroutine)});
    m.Add(dex::OP_IGET, {m.Reg(1), m.Reg(0), StateField(m)});
    m.Add(dex::OP_CONST_HIGH16, {m.Reg(2), m.Literal(0x80000000)});
    m.Add(dex::OP_AND_INT_2ADDR, {m.Reg(1), m.Reg(2)});
    m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(create)});
    m.Add(dex::OP_IGET, {m.Reg(1), m.Reg(0), StateField(m)});
    m.Add(dex::OP_SUB_INT_2ADDR, {m.Reg(1), m.Reg(2)});
    m.Add(dex::OP_IPUT, {m.Reg(1), m.Reg(0), StateField(m)});
    m.Add(dex::OP_GOTO, {m.Target(created)});
    m.Place(create);
    m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kCoroutine)});
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({0, 4, 5}),
           m.MethodRef(kCoroutine, "<init>",
                       "(LFoo;Lkotlin/coroutines/Continuation;)V")});
    m.Place(created);
    m.Add(dex::OP_IGET_OBJECT,
          {m.Reg(1), m.Reg(0), m.FieldRef(kCoroutine, "result", kObject)});
    return first;
  }

  // The field that holds the state of the coroutine.
  static lir::Field* StateField(TestMethod& m) {
    return m.FieldRef(kCoroutine, "label", "I");
  }

  // Appends the switch on the state of the coroutine in `coroutine`, which
  // jumps to the payload after `states`. Returns the switch.
  static lir::Bytecode* AddStateSwitch(TestMethod& m, dex::u4 coroutine,
                                       dex::u4 state, lir::Label* states) {
    m.Add(dex::OP_IGET, {m.Reg(state), m.Reg(coroutine), StateField(m)});
    return m.Add(dex::OP_PACKED_SWITCH, {m.Reg(state), m.Target(states)});
  }

  // Appends the code that throws when the coroutine is resumed in an unknown
  // state. Returns its range.
  static Range AddResumeBeforeInvoke(
      TestMethod& m, const char* exception = kIllegalStateException,
      const char* message = kResumeBeforeInvoke) {
    lir::Bytecode* first =
        m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(exception)});
    m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef(message)});
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({0, 1}),
           m.MethodRef(exception, "<init>", "(Ljava/lang/String;)V")});
    lir::Bytecode* last = m.Add(dex::OP_THROW, {m.Reg(0)});
    return Range{first, last};
  }

  // Appends the call that throws the exception of a failed result.
  static lir::Bytecode* AddThrowOnFailure(TestMethod& m, dex::u4 result) {
    return m.Add(
        dex::OP_INVOKE_STATIC,
        {m.Regs({result}), m.MethodRef("Lkotlin/ResultKt;", "throwOnFailure",
                                       "(Ljava/lang/Object;)V")});
  }

  // Appends the update of the state of the coroutine to `state`, which it
  // resumes in after the next suspension point.
  static void AddSetState(TestMethod& m, dex::u4 coroutine, dex::u4 reg,
                          dex::u4 state) {
    m.Add(dex::OP_CONST_4, {m.Reg(reg), m.Literal(state)});
    m.Add(dex::OP_IPUT, {m.Reg(reg), m.Reg(coroutine), StateField(m)});
  }

  // Appends a call to the suspending function `name`, and the move of its
  // result to `result`.
  static void AddSuspendingCall(TestMethod& m, const char* name,
                                dex::u4 coroutine, dex::u4 result) {
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({coroutine}),
           m.MethodRef(kTestClassName, name, kSuspendingSignature)});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(result)});
  }

  // Appends the check that returns the marker in `suspended` if the call
  // whose result is in `result` suspended. Returns its range.
  static Range AddSuspensionCheck(TestMethod& m, dex::u4 result,
                                  dex::u4 suspended, lir::Label* continuation) {
    lir::Bytecode* check =
        m.Add(dex::OP_IF_NE,
              {m.Reg(result), m.Reg(suspended), m.Target(continuation)});
    lir::Bytecode* suspend = m.Add(dex::OP_RETURN_OBJECT, {m.Reg(suspended)});
    return Range{check, suspend};
  }

  // Appends `nop()` and `return Unit`.
  static void AddNopAndReturnUnit(TestMethod& m, dex::u4 reg) {
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({}), m.MethodRef(kTestClassName, "nop", "()V")});
    m.Add(
        dex::OP_SGET_OBJECT,
        {m.Reg(reg), m.FieldRef("Lkotlin/Unit;", "INSTANCE", "Lkotlin/Unit;")});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(reg)});
  }

  // How AddLambda() builds a suspending lambda. By default, as the compiler
  // and D8 do; each test of a rule of the filter breaks one of them.
  struct LambdaShape {
    bool keeps_marker = true;
    bool reads_state = true;
    bool switches_on_state = true;
    dex::s4 first_state = 0;
    const char* unknown_state_exception = kIllegalStateException;
    const char* unknown_state_message = kResumeBeforeInvoke;
    bool starts_with_result_check = true;
    bool resumption_checks_result = true;
    bool resumption_is_jump_target = false;
    bool check_compares_marker = true;
    bool check_returns_marker = true;
  };

  // The parts of the lambda that AddLambda() builds.
  struct Lambda {
    lir::Bytecode* first;
    lir::Bytecode* state_switch;
    Range unknown_state;
    lir::Label* start;
    lir::Bytecode* started;
    lir::Label* resume;
    lir::Bytecode* resumed;
    Range check;
  };

  // Appends a suspending lambda with one suspension point:
  //
  //   invoke-static IntrinsicsKt.getCOROUTINE_SUSPENDED()
  //   move-result-object v0
  //   iget v1, v3, label
  //   packed-switch v1, :states
  //   (throw for an unknown state)
  //   :resume
  //   invoke-static ResultKt.throwOnFailure(v4)
  //   goto :continuation
  //   :start
  //   invoke-static ResultKt.throwOnFailure(v4)
  //   (label = 1, other())
  //   if-ne v1, v0, :continuation
  //   return-object v0
  //   :continuation
  //   (nop(), return Unit)
  static Lambda AddLambda(TestMethod& m, const LambdaShape& shape) {
    Lambda lambda;
    lir::Label* continuation = m.NewLabel();
    lir::Label* states = m.NewLabel();
    lambda.resume = m.NewLabel();
    lambda.start = m.NewLabel();
    lambda.first = AddGetCoroutineSuspended(m, shape.keeps_marker ? 0 : -1);
    if (shape.reads_state) {
      m.Add(dex::OP_IGET, {m.Reg(1), m.Reg(3), StateField(m)});
    } else {
      m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
    }
    lambda.state_switch =
        m.Add(dex::OP_PACKED_SWITCH,
              {m.Reg(shape.switches_on_state ? 1 : 2), m.Target(states)});
    lambda.unknown_state = AddResumeBeforeInvoke(
        m, shape.unknown_state_exception, shape.unknown_state_message);
    m.Place(lambda.resume);
    if (shape.resumption_checks_result) {
      AddThrowOnFailure(m, 4);
    }
    if (shape.resumption_is_jump_target) {
      m.Place(m.NewLabel());
    }
    m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(4)});
    lambda.resumed = m.Add(dex::OP_GOTO, {m.Target(continuation)});
    m.Place(lambda.start);
    if (shape.starts_with_result_check) {
      lambda.started = AddThrowOnFailure(m, 4);
    }
    AddSetState(m, 3, 2, 1);
    AddSuspendingCall(m, "other", 3, 1);
    lambda.check.from = m.Add(
        dex::OP_IF_NE, {m.Reg(1), m.Reg(shape.check_compares_marker ? 0 : 2),
                        m.Target(continuation)});
    lambda.check.to = m.Add(dex::OP_RETURN_OBJECT,
                            {m.Reg(shape.check_returns_marker ? 0 : 1)});
    m.Place(continuation);
    AddNopAndReturnUnit(m, 0);
    m.Place(states);
    m.AddPackedSwitchPayload(shape.first_state, {lambda.start, lambda.resume});
    return lambda;
  }

  // The ranges that the filter ignores in a lambda of AddLambda(), up to the
  // resumption.
  static std::vector<Range> StateMachine(const Lambda& lambda) {
    return {Range{lambda.first, lambda.state_switch}, lambda.unknown_state,
            Range{lambda.start, lambda.started}};
  }

  KotlinCoroutineFilter filter_;
};

// suspend fun run() {
//   other()
//   nop()
// }
TEST_F(KotlinCoroutineFilterTest, ShouldFilterSuspendingFunction) {
  TestMethod m("run", kSuspendingSignature);
  lir::Label* resume = m.NewLabel();
  lir::Label* start = m.NewLabel();
  lir::Label* continuation = m.NewLabel();
  lir::Label* states = m.NewLabel();
  lir::Bytecode* first = AddContinuationPrologue(m);
  AddGetCoroutineSuspended(m, 2);
  lir::Bytecode* state_switch = AddStateSwitch(m, 0, 3, states);
  Range unknown_state = AddResumeBeforeInvoke(m);
  m.Place(resume);
  AddThrowOnFailure(m, 1);
  lir::Bytecode* resumed = m.Add(dex::OP_GOTO, {m.Target(continuation)});
  m.Place(start);
  lir::Bytecode* started = AddThrowOnFailure(m, 1);
  AddSetState(m, 0, 3, 1);
  AddSuspendingCall(m, "other", 0, 3);
  Range check = AddSuspensionCheck(m, 3, 2, continuation);
  m.Place(continuation);
  AddNopAndReturnUnit(m, 2);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start, resume});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, state_switch}, unknown_state,
                    Range{start, started}, Range{resume, resumed}, check});
}

// launch {
//   if (flag) {
//     other()
//   }
//   nop()
// }
TEST_F(KotlinCoroutineFilterTest, ShouldFilterSuspendingLambda) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Label* resume = m.NewLabel();
  lir::Label* start = m.NewLabel();
  lir::Label* continuation = m.NewLabel();
  lir::Label* end = m.NewLabel();
  lir::Label* states = m.NewLabel();
  lir::Bytecode* first = AddGetCoroutineSuspended(m, 0);
  lir::Bytecode* state_switch = AddStateSwitch(m, 3, 1, states);
  Range unknown_state = AddResumeBeforeInvoke(m);
  m.Place(resume);
  AddThrowOnFailure(m, 4);
  lir::Bytecode* resumed = m.Add(dex::OP_GOTO, {m.Target(continuation)});
  m.Place(start);
  lir::Bytecode* started = AddThrowOnFailure(m, 4);
  m.Add(dex::OP_IGET_BOOLEAN,
        {m.Reg(1), m.Reg(3), m.FieldRef(kCoroutine, "$flag", "Z")});
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(end)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(3)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(1), m.TypeRef(kContinuation)});
  AddSetState(m, 3, 2, 1);
  AddSuspendingCall(m, "other", 1, 1);
  Range check = AddSuspensionCheck(m, 1, 0, continuation);
  m.Place(continuation);
  m.Add(dex::OP_NOP);
  m.Place(end);
  AddNopAndReturnUnit(m, 0);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start, resume});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  // The branch on `flag` is written by the user, so it is kept.
  ExpectIgnored(m, {Range{first, state_switch}, unknown_state,
                    Range{start, started}, Range{resume, resumed}, check});
}

// launch {
//   val a = value()
//   nop(a)
//   val b = value()
//   nop(a + b)
// }
TEST_F(KotlinCoroutineFilterTest, ShouldFilterResumptionsThatRestoreVariables) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Label* resume_second = m.NewLabel();
  lir::Label* resume_first = m.NewLabel();
  lir::Label* start = m.NewLabel();
  lir::Label* after_first = m.NewLabel();
  lir::Label* not_suspended_second = m.NewLabel();
  lir::Label* after_second = m.NewLabel();
  lir::Label* states = m.NewLabel();
  lir::Bytecode* first = AddGetCoroutineSuspended(m, 0);
  lir::Bytecode* state_switch = AddStateSwitch(m, 5, 1, states);
  Range unknown_state = AddResumeBeforeInvoke(m);
  m.Place(resume_second);
  m.Add(dex::OP_IGET, {m.Reg(2), m.Reg(5), m.FieldRef(kCoroutine, "I$0", "I")});
  AddThrowOnFailure(m, 6);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(4), m.Reg(6)});
  lir::Bytecode* resumed_second = m.Add(dex::OP_GOTO, {m.Target(after_second)});
  m.Place(resume_first);
  AddThrowOnFailure(m, 6);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(3), m.Reg(6)});
  lir::Bytecode* resumed_first = m.Add(dex::OP_GOTO, {m.Target(after_first)});
  m.Place(start);
  lir::Bytecode* started = AddThrowOnFailure(m, 6);
  AddSetState(m, 5, 3, 1);
  AddSuspendingCall(m, "value", 5, 3);
  Range first_check = AddSuspensionCheck(m, 3, 0, after_first);
  m.Place(after_first);
  m.Add(dex::OP_CHECK_CAST, {m.Reg(3), m.TypeRef("Ljava/lang/Number;")});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({3}), m.MethodRef("Ljava/lang/Number;", "intValue", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(3)});
  m.Add(dex::OP_IPUT, {m.Reg(3), m.Reg(5), m.FieldRef(kCoroutine, "I$0", "I")});
  AddSetState(m, 5, 4, 2);
  AddSuspendingCall(m, "value", 5, 4);
  Range second_check = AddSuspensionCheck(m, 4, 0, not_suspended_second);
  m.Place(not_suspended_second);
  // D8 moves the saved variable to where the resumption restores it.
  m.Add(dex::OP_MOVE, {m.Reg(2), m.Reg(3)});
  m.Place(after_second);
  m.Add(dex::OP_CHECK_CAST, {m.Reg(4), m.TypeRef("Ljava/lang/Number;")});
  AddNopAndReturnUnit(m, 0);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start, resume_first, resume_second});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(
      m, {Range{first, state_switch}, unknown_state, Range{start, started},
          Range{resume_first, resumed_first},
          Range{resume_second, resumed_second}, first_check, second_check});
}

// D8 can move constants of the code that follows before the switch.
TEST_F(KotlinCoroutineFilterTest, ShouldFilterStateSwitchAfterConstant) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Label* resume = m.NewLabel();
  lir::Label* start = m.NewLabel();
  lir::Label* continuation = m.NewLabel();
  lir::Label* states = m.NewLabel();
  lir::Bytecode* first = AddGetCoroutineSuspended(m, 1);
  m.Add(dex::OP_IGET, {m.Reg(2), m.Reg(0), StateField(m)});
  m.Add(dex::OP_CONST_4, {m.Reg(3), m.Literal(1)});
  lir::Bytecode* state_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(2), m.Target(states)});
  Range unknown_state = AddResumeBeforeInvoke(m);
  m.Place(resume);
  AddThrowOnFailure(m, 4);
  lir::Bytecode* resumed = m.Add(dex::OP_GOTO, {m.Target(continuation)});
  m.Place(start);
  lir::Bytecode* started = AddThrowOnFailure(m, 4);
  m.Add(dex::OP_IPUT, {m.Reg(3), m.Reg(0), StateField(m)});
  AddSuspendingCall(m, "other", 0, 2);
  Range check = AddSuspensionCheck(m, 2, 1, continuation);
  m.Place(continuation);
  AddNopAndReturnUnit(m, 1);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start, resume});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, state_switch}, unknown_state,
                    Range{start, started}, Range{resume, resumed}, check});
}

// runBlocking { nop() }
TEST_F(KotlinCoroutineFilterTest, ShouldFilterLambdaWithoutSuspensionPoint) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Label* start = m.NewLabel();
  lir::Label* states = m.NewLabel();
  lir::Bytecode* first = AddGetCoroutineSuspended(m, -1);
  lir::Bytecode* state_switch = AddStateSwitch(m, 2, 0, states);
  Range unknown_state = AddResumeBeforeInvoke(m);
  m.Place(start);
  lir::Bytecode* started = AddThrowOnFailure(m, 3);
  AddNopAndReturnUnit(m, 0);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(
      m, {Range{first, state_switch}, unknown_state, Range{start, started}});
}

// launch { nop(wrapper()) }, where wrapper() returns an inline value class.
TEST_F(KotlinCoroutineFilterTest, ShouldFilterResumptionThatUnboxesResult) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Label* resume = m.NewLabel();
  lir::Label* start = m.NewLabel();
  lir::Label* not_suspended = m.NewLabel();
  lir::Label* continuation = m.NewLabel();
  lir::Label* states = m.NewLabel();
  lir::Bytecode* first = AddGetCoroutineSuspended(m, 0);
  lir::Bytecode* state_switch = AddStateSwitch(m, 3, 1, states);
  Range unknown_state = AddResumeBeforeInvoke(m);
  m.Place(resume);
  AddThrowOnFailure(m, 4);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(2), m.Reg(4)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(2), m.TypeRef("LWrapper;")});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}),
         m.MethodRef("LWrapper;", "unbox-impl", "()Ljava/lang/String;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
  lir::Bytecode* resumed = m.Add(dex::OP_GOTO, {m.Target(continuation)});
  m.Place(start);
  lir::Bytecode* started = AddThrowOnFailure(m, 4);
  AddSetState(m, 3, 1, 1);
  AddSuspendingCall(m, "wrapper", 3, 1);
  Range check = AddSuspensionCheck(m, 1, 0, not_suspended);
  m.Place(not_suspended);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(2), m.Reg(1)});
  m.Place(continuation);
  AddNopAndReturnUnit(m, 0);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start, resume});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, state_switch}, unknown_state,
                    Range{start, started}, Range{resume, resumed}, check});
}

// suspend fun run() {
//   nop()
//   other()
// }
TEST_F(KotlinCoroutineFilterTest, ShouldFilterCheckAfterTailCall) {
  TestMethod m("run", kSuspendingSignature);
  lir::Label* not_suspended = m.NewLabel();
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "nop", "()V")});
  AddSuspendingCall(m, "other", 3, 0);
  lir::Bytecode* get_suspended = AddGetCoroutineSuspended(m, 1);
  m.Add(dex::OP_IF_NE, {m.Reg(0), m.Reg(1), m.Target(not_suspended)});
  lir::Bytecode* suspend = m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(not_suspended);
  m.Add(dex::OP_SGET_OBJECT,
        {m.Reg(0), m.FieldRef("Lkotlin/Unit;", "INSTANCE", "Lkotlin/Unit;")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{get_suspended, suspend}});
}

// suspend fun run(): Int = suspendCoroutine { it.resume(1) }
TEST_F(KotlinCoroutineFilterTest, ShouldFilterCheckOfSuspendCoroutine) {
  TestMethod m("run", kSuspendingSignature);
  lir::Label* not_suspended = m.NewLabel();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef("Lkotlin/coroutines/SafeContinuation;",
                                  "getOrThrow", "()Ljava/lang/Object;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  AddGetCoroutineSuspended(m, 1);
  lir::Bytecode* check =
      m.Add(dex::OP_IF_NE, {m.Reg(0), m.Reg(1), m.Target(not_suspended)});
  lir::Bytecode* probe =
      m.Add(dex::OP_INVOKE_STATIC,
            {m.Regs({5}),
             m.MethodRef("Lkotlin/coroutines/jvm/internal/DebugProbesKt;",
                         "probeCoroutineSuspended",
                         "(Lkotlin/coroutines/Continuation;)V")});
  m.Place(not_suspended);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{check, probe}});
}

// Inside a state machine, the continuation is cast before the call.
TEST_F(KotlinCoroutineFilterTest,
       ShouldFilterCheckOfSuspendCoroutineWithCastContinuation) {
  TestMethod m("run", kSuspendingSignature);
  lir::Label* not_suspended = m.NewLabel();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({4}), m.MethodRef("Lkotlin/coroutines/SafeContinuation;",
                                  "getOrThrow", "()Ljava/lang/Object;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(3)});
  AddGetCoroutineSuspended(m, 4);
  lir::Bytecode* check =
      m.Add(dex::OP_IF_NE, {m.Reg(3), m.Reg(4), m.Target(not_suspended)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(4), m.Reg(0)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(4), m.TypeRef(kContinuation)});
  lir::Bytecode* probe =
      m.Add(dex::OP_INVOKE_STATIC,
            {m.Regs({4}),
             m.MethodRef("Lkotlin/coroutines/jvm/internal/DebugProbesKt;",
                         "probeCoroutineSuspended",
                         "(Lkotlin/coroutines/Continuation;)V")});
  m.Place(not_suspended);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(3)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{check, probe}});
}

// The state machine is matched only if an unknown state throws.
TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterSwitchWithoutUnknownState) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Label* start = m.NewLabel();
  lir::Label* states = m.NewLabel();
  AddGetCoroutineSuspended(m, -1);
  AddStateSwitch(m, 2, 0, states);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(3)});
  m.Place(start);
  AddThrowOnFailure(m, 3);
  AddNopAndReturnUnit(m, 0);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The state machine is matched only if it checks the result in its initial
// state, after the switch.
TEST_F(KotlinCoroutineFilterTest,
       ShouldNotFilterStateMachineThatChecksResultBeforeSwitch) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Label* resume = m.NewLabel();
  lir::Label* start = m.NewLabel();
  lir::Label* continuation = m.NewLabel();
  lir::Label* states = m.NewLabel();
  AddGetCoroutineSuspended(m, 0);
  AddThrowOnFailure(m, 4);
  AddStateSwitch(m, 3, 1, states);
  AddResumeBeforeInvoke(m);
  m.Place(resume);
  m.Add(dex::OP_GOTO, {m.Target(continuation)});
  m.Place(start);
  AddSetState(m, 3, 2, 1);
  AddSuspendingCall(m, "other", 3, 1);
  AddSuspensionCheck(m, 1, 0, continuation);
  m.Place(continuation);
  AddNopAndReturnUnit(m, 0);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start, resume});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Code of the user in a resumption is kept, and so is the check whose
// continuation the resumption does not match.
TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterResumptionWithOtherCode) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Label* resume = m.NewLabel();
  lir::Label* start = m.NewLabel();
  lir::Label* continuation = m.NewLabel();
  lir::Label* states = m.NewLabel();
  lir::Bytecode* first = AddGetCoroutineSuspended(m, 0);
  lir::Bytecode* state_switch = AddStateSwitch(m, 3, 1, states);
  Range unknown_state = AddResumeBeforeInvoke(m);
  m.Place(resume);
  AddThrowOnFailure(m, 4);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "nop", "()V")});
  m.Add(dex::OP_GOTO, {m.Target(continuation)});
  m.Place(start);
  lir::Bytecode* started = AddThrowOnFailure(m, 4);
  AddSetState(m, 3, 2, 1);
  AddSuspendingCall(m, "other", 3, 1);
  AddSuspensionCheck(m, 1, 0, continuation);
  m.Place(continuation);
  AddNopAndReturnUnit(m, 0);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start, resume});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(
      m, {Range{first, state_switch}, unknown_state, Range{start, started}});
}

// After the last suspension point, D8 can reuse the register of the marker. A
// comparison that does not continue where a resumption does is kept.
TEST_F(KotlinCoroutineFilterTest,
       ShouldNotFilterComparisonThatDoesNotContinueAfterSuspensionPoint) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Label* resume = m.NewLabel();
  lir::Label* start = m.NewLabel();
  lir::Label* continuation = m.NewLabel();
  lir::Label* different = m.NewLabel();
  lir::Label* states = m.NewLabel();
  lir::Bytecode* first = AddGetCoroutineSuspended(m, 0);
  lir::Bytecode* state_switch = AddStateSwitch(m, 3, 1, states);
  Range unknown_state = AddResumeBeforeInvoke(m);
  m.Place(resume);
  AddThrowOnFailure(m, 4);
  lir::Bytecode* resumed = m.Add(dex::OP_GOTO, {m.Target(continuation)});
  m.Place(start);
  lir::Bytecode* started = AddThrowOnFailure(m, 4);
  AddSetState(m, 3, 2, 1);
  AddSuspendingCall(m, "other", 3, 1);
  Range check = AddSuspensionCheck(m, 1, 0, continuation);
  m.Place(continuation);
  // if (x !== y) return y, with y in the register of the marker.
  m.Add(dex::OP_SGET_OBJECT,
        {m.Reg(0), m.FieldRef(kTestClassName, "y", kObject)});
  m.Add(dex::OP_IF_NE, {m.Reg(2), m.Reg(0), m.Target(different)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(different);
  AddNopAndReturnUnit(m, 0);
  m.Place(states);
  m.AddPackedSwitchPayload(0, {start, resume});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, state_switch}, unknown_state,
                    Range{start, started}, Range{resume, resumed}, check});
}

// The check of suspendCoroutine() passes the continuation to the probe.
TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterCheckWithOtherCast) {
  TestMethod m("run", kSuspendingSignature);
  lir::Label* not_suspended = m.NewLabel();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({4}), m.MethodRef("Lkotlin/coroutines/SafeContinuation;",
                                  "getOrThrow", "()Ljava/lang/Object;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(3)});
  AddGetCoroutineSuspended(m, 4);
  m.Add(dex::OP_IF_NE, {m.Reg(3), m.Reg(4), m.Target(not_suspended)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(4), m.Reg(0)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(4), m.TypeRef(kObject)});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({4}),
         m.MethodRef("Lkotlin/coroutines/jvm/internal/DebugProbesKt;",
                     "probeCoroutineSuspended",
                     "(Lkotlin/coroutines/Continuation;)V")});
  m.Place(not_suspended);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(3)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The lambda of AddLambda() as the compiler and D8 build it, which the tests
// of the rules below change.
TEST_F(KotlinCoroutineFilterTest, ShouldFilterLambdaWithOneSuspensionPoint) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  Lambda lambda = AddLambda(m, LambdaShape());

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  std::vector<Range> expected = StateMachine(lambda);
  expected.push_back(Range{lambda.resume, lambda.resumed});
  expected.push_back(lambda.check);
  ExpectIgnored(m, expected);
}

TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterSwitchWithoutStateRead) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.reads_state = false;
  AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterSwitchOnOtherRegister) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.switches_on_state = false;
  AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterSwitchFromOtherState) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.first_state = 1;
  AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Without the marker, there is no suspension point to resume after.
TEST_F(KotlinCoroutineFilterTest,
       ShouldNotFilterSeveralStatesWithoutSuspensionMarker) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.keeps_marker = false;
  AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinCoroutineFilterTest,
       ShouldNotFilterStateMachineWithoutInitialResultCheck) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.starts_with_result_check = false;
  AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterUnknownStateWithOtherMessage) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.unknown_state_message = "unknown state";
  AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinCoroutineFilterTest,
       ShouldNotFilterUnknownStateWithOtherException) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.unknown_state_exception = "Ljava/lang/IllegalArgumentException;";
  AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// A resumption rethrows the exception of a failed result, so code that does
// not is kept, and so is the check that continues where it does.
TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterResumptionWithoutResultCheck) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.resumption_checks_result = false;
  Lambda lambda = AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, StateMachine(lambda));
}

// Code that other code jumps into is not only a resumption.
TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterResumptionWithJumpTarget) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.resumption_is_jump_target = true;
  Lambda lambda = AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, StateMachine(lambda));
}

TEST_F(KotlinCoroutineFilterTest, ShouldNotFilterComparisonWithoutMarker) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.check_compares_marker = false;
  Lambda lambda = AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  std::vector<Range> expected = StateMachine(lambda);
  expected.push_back(Range{lambda.resume, lambda.resumed});
  ExpectIgnored(m, expected);
}

TEST_F(KotlinCoroutineFilterTest,
       ShouldNotFilterComparisonThatDoesNotReturnMarker) {
  TestMethod m("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
  LambdaShape shape;
  shape.check_returns_marker = false;
  Lambda lambda = AddLambda(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  std::vector<Range> expected = StateMachine(lambda);
  expected.push_back(Range{lambda.resume, lambda.resumed});
  ExpectIgnored(m, expected);
}

// The check after a tail call jumps right after its return.
TEST_F(KotlinCoroutineFilterTest,
       ShouldNotFilterTailCallCheckThatJumpsFurther) {
  TestMethod m("run", kSuspendingSignature);
  lir::Label* elsewhere = m.NewLabel();
  lir::Label* not_suspended = m.NewLabel();
  AddSuspendingCall(m, "other", 3, 0);
  AddGetCoroutineSuspended(m, 1);
  m.Add(dex::OP_IF_NE, {m.Reg(0), m.Reg(1), m.Target(not_suspended)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(elsewhere);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "nop", "()V")});
  m.Place(not_suspended);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The check of suspendCoroutine() jumps right after the probe.
TEST_F(KotlinCoroutineFilterTest,
       ShouldNotFilterCheckOfSuspendCoroutineThatJumpsFurther) {
  TestMethod m("run", kSuspendingSignature);
  lir::Label* elsewhere = m.NewLabel();
  lir::Label* not_suspended = m.NewLabel();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef("Lkotlin/coroutines/SafeContinuation;",
                                  "getOrThrow", "()Ljava/lang/Object;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  AddGetCoroutineSuspended(m, 1);
  m.Add(dex::OP_IF_NE, {m.Reg(0), m.Reg(1), m.Target(not_suspended)});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({5}),
         m.MethodRef("Lkotlin/coroutines/jvm/internal/DebugProbesKt;",
                     "probeCoroutineSuspended",
                     "(Lkotlin/coroutines/Continuation;)V")});
  m.Place(elsewhere);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "nop", "()V")});
  m.Place(not_suspended);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
