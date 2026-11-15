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

#include "tools/base/android-test/coverage/agent/native/record_pattern_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

namespace coverage {
namespace {

constexpr char kThrowable[] = "Ljava/lang/Throwable;";
constexpr char kMatchException[] = "Ljava/lang/MatchException;";
constexpr char kRuntimeException[] = "Ljava/lang/RuntimeException;";
constexpr char kToString[] = "()Ljava/lang/String;";
constexpr char kMessageAndCause[] =
    "(Ljava/lang/String;Ljava/lang/Throwable;)V";
constexpr char kPoint[] = "Lcom/example/Point;";
// The signature of static int sum(Object o).
constexpr char kSignature[] = "(Ljava/lang/Object;)I";
// The signature of static int sum(Object o, Object p).
constexpr char kSignatureOfTwo[] = "(Ljava/lang/Object;Ljava/lang/Object;)I";

// How RecordPatternFilterTest::AddHandler() lays out the handler: the code
// that D8 generates, with the cause in v0, the exception in v1 and the message
// in v2, unless a test changes one of the fields.
struct HandlerShape {
  // How the handler creates the exception into v1, and its class.
  dex::Opcode creation = dex::OP_NEW_INSTANCE;
  const char* exception = kRuntimeException;
  // How the handler describes the cause: it calls the method `description`
  // of `described_class` on v<described> with `description_call`.
  dex::Opcode description_call = dex::OP_INVOKE_VIRTUAL;
  const char* described_class = kThrowable;
  const char* description = "toString";
  dex::u4 described = 0;
  // How the handler puts the message into v2: with the move-result-object of
  // the description, with a const-string after it, or not at all.
  enum class Message { kDescription, kString, kNone };
  Message message_load = Message::kDescription;
  // How the handler calls the constructor with `constructor_call`: the one of
  // `constructor_class`, or of the exception if it is nullptr, with
  // `constructor_signature`, on v<receiver> with v<message> and v<cause>.
  dex::Opcode constructor_call = dex::OP_INVOKE_DIRECT;
  const char* constructor_class = nullptr;
  const char* constructor_signature = kMessageAndCause;
  dex::u4 receiver = 1;
  dex::u4 message = 2;
  dex::u4 cause = 0;
  // Whether the handler throws v<thrown>, or returns it.
  bool throws = true;
  dex::u4 thrown = 1;
};

// Unit tests for RecordPatternFilter, on the code that javac and D8 generate
// for
//   record Point(int x, int y) {}
//
//   static int sum(Object o) {
//     if (o instanceof Point(int x, int y)) {
//       return x + y;
//     }
//     return 0;
//   }
// with o in v3, unless the comment of a test gives other code. javac calls the
// accessors x() and y() in a try block whose handler throws a MatchException
// with what they throw, which D8 replaces with a RuntimeException.
class RecordPatternFilterTest : public FilterTestBase {
 protected:
  // Appends the check that v<object> is a Point, which jumps to `otherwise`
  // if not, then the calls to the accessors of that Point, cast into v<point>,
  // in a try block whose exceptions go to `handler`: those of type `caught`,
  // or all of them without `caught`. x goes to v<point + 1> and y to
  // v<point>.
  static void AddMatch(TestMethod& m, dex::u4 object, dex::u4 point,
                       lir::Label* handler, lir::Label* otherwise,
                       const char* caught = nullptr) {
    m.Add(dex::OP_INSTANCE_OF,
          {m.Reg(point), m.Reg(object), m.TypeRef(kPoint)});
    m.Add(dex::OP_IF_EQZ, {m.Reg(point), m.Target(otherwise)});
    m.Add(dex::OP_MOVE_OBJECT, {m.Reg(point), m.Reg(object)});
    m.Add(dex::OP_CHECK_CAST, {m.Reg(point), m.TypeRef(kPoint)});
    lir::TryBlockBegin* begin = m.AddTryBlockBegin();
    AddCalls(m, point);
    m.AddTryBlockEnd(begin, handler, caught);
  }

  // Appends the calls to the accessors of the Point in v<point>, which move x
  // to v<point + 1> and y to v<point>.
  static void AddCalls(TestMethod& m, dex::u4 point) {
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({point}), m.MethodRef(kPoint, "x", "()I")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(point + 1)});
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({point}), m.MethodRef(kPoint, "y", "()I")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(point)});
  }

  // Appends `return x + y`, with x in v1 and y in v0.
  static void AddReturnOfSum(TestMethod& m) {
    m.Add(dex::OP_ADD_INT, {m.Reg(2), m.Reg(1), m.Reg(0)});
    m.Add(dex::OP_RETURN, {m.Reg(2)});
  }

  // Appends `return 0` at `otherwise`.
  static void AddReturnZero(TestMethod& m, lir::Label* otherwise) {
    m.Place(otherwise);
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    m.Add(dex::OP_RETURN, {m.Reg(0)});
  }

  // Appends the handler at `handler`, laid out as `shape`, and returns its
  // code.
  static Range AddHandler(TestMethod& m, lir::Label* handler,
                          const HandlerShape& shape = HandlerShape()) {
    m.Place(handler);
    lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
    m.Add(shape.creation, {m.Reg(1), m.TypeRef(shape.exception)});
    m.Add(shape.description_call,
          {m.Regs({shape.described}),
           m.MethodRef(shape.described_class, shape.description, kToString)});
    if (shape.message_load == HandlerShape::Message::kDescription) {
      m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
    } else if (shape.message_load == HandlerShape::Message::kString) {
      m.Add(dex::OP_CONST_STRING, {m.Reg(2), m.StringRef("message")});
    }
    m.Add(shape.constructor_call,
          {m.Regs({shape.receiver, shape.message, shape.cause}),
           m.MethodRef(shape.constructor_class == nullptr
                           ? shape.exception
                           : shape.constructor_class,
                       "<init>", shape.constructor_signature)});
    return {caught, m.Add(shape.throws ? dex::OP_THROW : dex::OP_RETURN_OBJECT,
                          {m.Reg(shape.thrown)})};
  }

  // Appends the rest of the handler after its move-exception, with the cause
  // in v0, and returns it.
  static Range AddRestOfHandler(TestMethod& m) {
    lir::Instruction* created =
        m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef(kRuntimeException)});
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({0}), m.MethodRef(kThrowable, "toString", kToString)});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({1, 2, 0}),
           m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
    return {created, m.Add(dex::OP_THROW, {m.Reg(1)})};
  }

  void Filter(TestMethod& m) {
    RecordPatternFilter().Filter(context_, m.ir_method(), m.code_ir(), output_);
  }

  // Builds sum() with the handler laid out as `shape`, and expects the filter
  // to leave it alone.
  void ExpectNotFiltered(const HandlerShape& shape) {
    TestMethod m("sum", kSignature);
    lir::Label* handler = m.NewLabel();
    lir::Label* otherwise = m.NewLabel();
    AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
    AddReturnOfSum(m);
    AddHandler(m, handler, shape);
    AddReturnZero(m, otherwise);

    Filter(m);

    ExpectIgnored(m, {});
  }
};

// The code that javac and D8 generate. Only the handler is ignored, not the
// check, the calls to the accessors, or the code after the handler.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerOfRecordPattern) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  Range handled = AddHandler(m, handler);
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {handled});
}

// static int sum(Object o) {
//   return switch (o) {
//     case Point(int x, int y) -> x + y;
//     default -> 0;
//   };
// }
// D8 puts the handler between the goto at the end of the case and the return
// that the cases share.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerOfSwitchCase) {
  TestMethod m("sum", kSignature);
  lir::Label* cases = m.NewLabel();
  lir::Label* point = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* done = m.NewLabel();
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef("Ljava/util/Objects;", "requireNonNull",
                                  "(Ljava/lang/Object;)Ljava/lang/Object;")});
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3, 0}), m.MethodRef("Lcom/example/Foo$1;", "switchDispatch",
                                     "(Ljava/lang/Object;I)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(1), m.Target(cases)});
  m.Add(dex::OP_GOTO, {m.Target(done)});
  m.Place(point);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(3)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef(kPoint)});
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  AddCalls(m, /*point=*/0);
  m.AddTryBlockEnd(begin, handler);
  m.Add(dex::OP_ADD_INT, {m.Reg(2), m.Reg(1), m.Reg(0)});
  m.Add(dex::OP_MOVE, {m.Reg(0), m.Reg(2)});
  m.Add(dex::OP_GOTO, {m.Target(done)});
  Range handled = AddHandler(m, handler);
  m.Place(done);
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {point});

  Filter(m);

  ExpectIgnored(m, {handled});
}

// The handler that javac generates creates a MatchException, which D8
// replaces.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerWithMatchException) {
  HandlerShape shape;
  shape.exception = kMatchException;
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  Range handled = AddHandler(m, handler, shape);
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {handled});
}

// javac catches Throwable, which D8 turns into a catch of any exception.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerOfThrowable) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise, kThrowable);
  AddReturnOfSum(m);
  Range handled = AddHandler(m, handler);
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {handled});
}

// static int sum(Object o, Object p) {
//   if (o instanceof Point(int x, int y) && p instanceof Point(int a, int b)) {
//     return x + y + a + b;
//   }
//   return 0;
// }
// The try blocks of both patterns share the handler, which is ignored once.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerOfSeveralTryBlocksOnce) {
  TestMethod m("sum", kSignatureOfTwo);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/5, /*point=*/0, handler, otherwise);
  AddMatch(m, /*object=*/6, /*point=*/2, handler, otherwise);
  m.Add(dex::OP_ADD_INT, {m.Reg(4), m.Reg(1), m.Reg(0)});
  m.Add(dex::OP_ADD_INT_2ADDR, {m.Reg(4), m.Reg(3)});
  m.Add(dex::OP_ADD_INT_2ADDR, {m.Reg(4), m.Reg(2)});
  m.Add(dex::OP_RETURN, {m.Reg(4)});
  Range handled = AddHandler(m, handler);
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {handled});
}

// A try block that catches any exception and one that catches Throwable can
// share the handler too.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerOfCatchAllAndThrowableOnce) {
  TestMethod m("sum", kSignatureOfTwo);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/5, /*point=*/0, handler, otherwise);
  AddMatch(m, /*object=*/6, /*point=*/2, handler, otherwise, kThrowable);
  m.Add(dex::OP_ADD_INT, {m.Reg(4), m.Reg(1), m.Reg(0)});
  m.Add(dex::OP_ADD_INT_2ADDR, {m.Reg(4), m.Reg(3)});
  m.Add(dex::OP_ADD_INT_2ADDR, {m.Reg(4), m.Reg(2)});
  m.Add(dex::OP_RETURN, {m.Reg(4)});
  Range handled = AddHandler(m, handler);
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {handled});
}

// static int sum(Object o, Object p) {
//   int sum = 0;
//   if (o instanceof Point(int x, int y)) {
//     sum += x + y;
//   }
//   if (p instanceof Point(int a, int b)) {
//     sum += a + b;
//   }
//   return sum;
// }
// The try blocks of both patterns share the handler. D8 puts its move-exception
// after the code of the first pattern, and jumps from there to the rest of the
// handler, after the code of the second one.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerSplitByGoto) {
  TestMethod m("sum", kSignatureOfTwo);
  lir::Label* handler = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* rest = m.NewLabel();
  lir::Label* end = m.NewLabel();
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  AddMatch(m, /*object=*/4, /*point=*/1, handler, second);
  m.Add(dex::OP_ADD_INT, {m.Reg(3), m.Reg(2), m.Reg(1)});
  m.Add(dex::OP_ADD_INT_2ADDR, {m.Reg(0), m.Reg(3)});
  m.Add(dex::OP_GOTO, {m.Target(second)});
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(rest)});
  m.Place(second);
  AddMatch(m, /*object=*/5, /*point=*/1, handler, end);
  m.Add(dex::OP_ADD_INT, {m.Reg(3), m.Reg(2), m.Reg(1)});
  m.Add(dex::OP_ADD_INT_2ADDR, {m.Reg(0), m.Reg(3)});
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(rest);
  Range rest_of_handler = AddRestOfHandler(m);
  m.Place(end);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {{caught, jump}, rest_of_handler});
}

// A goto of the handler that leads to another goto is followed too. In longer
// methods, the gotos are goto/16 or goto/32.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerSplitByGotoToGoto) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Label* middle = m.NewLabel();
  lir::Label* rest = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  lir::Instruction* first_jump = m.Add(dex::OP_GOTO_16, {m.Target(middle)});
  AddReturnZero(m, otherwise);
  m.Place(middle);
  lir::Instruction* second_jump = m.Add(dex::OP_GOTO_32, {m.Target(rest)});
  m.Place(rest);
  Range rest_of_handler = AddRestOfHandler(m);

  Filter(m);

  ExpectIgnored(
      m, {{caught, first_jump}, {second_jump, second_jump}, rest_of_handler});
}

TEST_F(RecordPatternFilterTest, ShouldRunOnJavaClasses) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  Range handled = AddHandler(m, handler);
  AddReturnZero(m, otherwise);

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(handled.from));
  EXPECT_TRUE(result.IsIgnored(handled.to));
}

// Kotlin has no record patterns. The code of
//   try {
//     ...
//   } catch (t: Throwable) {
//     throw RuntimeException(t.toString(), t)
//   }
// is the same as the handler, and stays.
TEST_F(RecordPatternFilterTest, ShouldNotRunOnKotlinClasses) {
  context_.class_annotations.insert(kKotlinMetadataDescriptor);
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  Range handled = AddHandler(m, handler);
  AddReturnZero(m, otherwise);

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_FALSE(result.IsIgnored(handled.from));
  EXPECT_FALSE(result.IsIgnored(handled.to));
}

// catch (Exception e) { throw new RuntimeException(e.toString(), e); }
// The handler must catch any exception.
TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerOfException) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise,
           "Ljava/lang/Exception;");
  AddReturnOfSum(m);
  AddHandler(m, handler);
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// catch (Throwable t) { throw new RuntimeException(OTHER.toString(), OTHER); }
// The handler must start with the move-exception of the cause.
TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerWithoutMoveException) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_SGET_OBJECT,
        {m.Reg(0), m.FieldRef(kTestClassName, "OTHER", kThrowable)});
  AddRestOfHandler(m);
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// catch (Throwable t) { throw t; }
TEST_F(RecordPatternFilterTest, ShouldNotFilterRethrow) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_THROW, {m.Reg(0)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// catch (Throwable t) { throw new RuntimeException(t); }
// In release mode, D8 can copy the exception to the register of a parameter
// that is no longer live first, as in a method with a pattern and eight long
// parameters.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerWithCopyOfCause) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(2), m.Reg(0)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(3), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kThrowable, "toString", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(4)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({3, 4, 2}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(3)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {{caught, thrown}});
}

// The handler copies another register than the exception, and describes it.
TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerThatCopiesOtherRegister) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(2), m.Reg(3)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kThrowable, "toString", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(4)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 4, 2}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(1)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// In a try block whose finally keeps longs in all the low registers, D8 moves
// a long out of v2 before it puts the message there.
TEST_F(RecordPatternFilterTest, ShouldFilterHandlerWithMoveOfOtherValue) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_MOVE_WIDE_FROM16, {m.code_ir().Alloc<lir::VRegPair>(18),
                                   m.code_ir().Alloc<lir::VRegPair>(2)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kThrowable, "toString", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 2, 0}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(1)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {{caught, thrown}});
}

// catch (Throwable t) { Throwable x; for (;;) { x = t; } }
TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerThatLoopsOnMove) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Label* loop = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Place(loop);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(1)});
  m.Add(dex::OP_GOTO, {m.Target(loop)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// The handler overwrites the caught exception before it describes it.
TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerThatOverwritesCause) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(3)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kThrowable, "toString", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 2, 0}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(1)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// The handler overwrites the exception that it creates.
TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerThatOverwritesException) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(3)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kThrowable, "toString", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 2, 0}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(1)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// The exception that the handler creates goes into the register of the
// caught one.
TEST_F(RecordPatternFilterTest, ShouldNotFilterCauseOverwrittenByException) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kThrowable, "toString", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 2, 0}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(0)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerThatOverwritesMessage) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kThrowable, "toString", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(2), m.Reg(3)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 2, 0}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(1)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

TEST_F(RecordPatternFilterTest,
       ShouldNotFilterHandlerWithWideMoveOverException) {
  // The second register of a move-wide into v1 is v2, which holds the new
  // exception.
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(2), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_MOVE_WIDE, {m.code_ir().Alloc<lir::VRegPair>(1),
                            m.code_ir().Alloc<lir::VRegPair>(4)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kThrowable, "toString", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(3)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({2, 3, 0}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(2)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

TEST_F(RecordPatternFilterTest, ShouldNotFilterCauseOverwrittenByMessage) {
  // The message goes into the register of the caught exception.
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kThrowable, "toString", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 0, 0}),
         m.MethodRef(kRuntimeException, "<init>", kMessageAndCause)});
  m.Add(dex::OP_THROW, {m.Reg(1)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

TEST_F(RecordPatternFilterTest, ShouldNotFilterWrappedCause) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef(kRuntimeException)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 0}),
         m.MethodRef(kRuntimeException, "<init>", "(Ljava/lang/Throwable;)V")});
  m.Add(dex::OP_THROW, {m.Reg(1)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// The goto after the move-exception leads to no bytecode.
TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerThatJumpsToNothing) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Label* nowhere = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_GOTO, {m.Target(nowhere)});
  AddReturnZero(m, otherwise);
  m.Place(nowhere);

  Filter(m);

  ExpectIgnored(m, {});
}

// The goto after the move-exception leads to itself.
TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerThatLoops) {
  TestMethod m("sum", kSignature);
  lir::Label* handler = m.NewLabel();
  lir::Label* otherwise = m.NewLabel();
  lir::Label* loop = m.NewLabel();
  AddMatch(m, /*object=*/3, /*point=*/0, handler, otherwise);
  AddReturnOfSum(m);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Place(loop);
  m.Add(dex::OP_GOTO, {m.Target(loop)});
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// The code of the handler, but no try block leads to it.
TEST_F(RecordPatternFilterTest, ShouldNotFilterCodeWithoutTryBlock) {
  TestMethod m("sum", kSignature);
  lir::Label* otherwise = m.NewLabel();
  m.Add(dex::OP_INSTANCE_OF, {m.Reg(0), m.Reg(3), m.TypeRef(kPoint)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(otherwise)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(3)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef(kPoint)});
  AddCalls(m, /*point=*/0);
  AddReturnOfSum(m);
  AddHandler(m, m.NewLabel());
  AddReturnZero(m, otherwise);

  Filter(m);

  ExpectIgnored(m, {});
}

// catch (Throwable t) {
//   throw new IllegalStateException(t.toString(), t);
// }
TEST_F(RecordPatternFilterTest, ShouldNotFilterOtherException) {
  HandlerShape shape;
  shape.exception = "Ljava/lang/IllegalStateException;";
  ExpectNotFiltered(shape);
}

// The handler loads the class RuntimeException instead of creating an
// exception.
TEST_F(RecordPatternFilterTest, ShouldNotFilterClassOfException) {
  HandlerShape shape;
  shape.creation = dex::OP_CONST_CLASS;
  ExpectNotFiltered(shape);
}

// The handler calls toString() of Throwable with invoke-super.
TEST_F(RecordPatternFilterTest, ShouldNotFilterSuperCallOfToString) {
  HandlerShape shape;
  shape.description_call = dex::OP_INVOKE_SUPER;
  ExpectNotFiltered(shape);
}

// The handler calls toString() of Object.
TEST_F(RecordPatternFilterTest, ShouldNotFilterToStringOfObject) {
  HandlerShape shape;
  shape.described_class = "Ljava/lang/Object;";
  ExpectNotFiltered(shape);
}

// catch (Throwable t) {
//   throw new RuntimeException(t.getMessage(), t);
// }
TEST_F(RecordPatternFilterTest, ShouldNotFilterMessageOfCause) {
  HandlerShape shape;
  shape.description = "getMessage";
  ExpectNotFiltered(shape);
}

// The handler describes o instead of the cause.
TEST_F(RecordPatternFilterTest, ShouldNotFilterDescriptionOfOtherObject) {
  HandlerShape shape;
  shape.described = 3;
  ExpectNotFiltered(shape);
}

// The handler doesn't move the description into a register.
TEST_F(RecordPatternFilterTest, ShouldNotFilterDiscardedDescription) {
  HandlerShape shape;
  shape.message_load = HandlerShape::Message::kNone;
  ExpectNotFiltered(shape);
}

// catch (Throwable t) {
//   t.toString();
//   throw new RuntimeException("message", t);
// }
TEST_F(RecordPatternFilterTest, ShouldNotFilterMessageThatIsNotDescription) {
  HandlerShape shape;
  shape.message_load = HandlerShape::Message::kString;
  ExpectNotFiltered(shape);
}

// The handler calls the constructor with invoke-virtual.
TEST_F(RecordPatternFilterTest, ShouldNotFilterVirtualCallOfConstructor) {
  HandlerShape shape;
  shape.constructor_call = dex::OP_INVOKE_VIRTUAL;
  ExpectNotFiltered(shape);
}

// The handler creates a RuntimeException, but calls the constructor of
// MatchException.
TEST_F(RecordPatternFilterTest, ShouldNotFilterConstructorOfOtherClass) {
  HandlerShape shape;
  shape.constructor_class = kMatchException;
  ExpectNotFiltered(shape);
}

// The handler calls a constructor that takes an Object as the message.
TEST_F(RecordPatternFilterTest, ShouldNotFilterConstructorWithOtherSignature) {
  HandlerShape shape;
  shape.constructor_signature = "(Ljava/lang/Object;Ljava/lang/Throwable;)V";
  ExpectNotFiltered(shape);
}

// The handler calls the constructor on o instead of the exception.
TEST_F(RecordPatternFilterTest, ShouldNotFilterConstructionOfOtherObject) {
  HandlerShape shape;
  shape.receiver = 3;
  ExpectNotFiltered(shape);
}

// The handler passes o as the message.
TEST_F(RecordPatternFilterTest, ShouldNotFilterOtherMessage) {
  HandlerShape shape;
  shape.message = 3;
  ExpectNotFiltered(shape);
}

// The handler passes o as the cause.
TEST_F(RecordPatternFilterTest, ShouldNotFilterOtherCause) {
  HandlerShape shape;
  shape.cause = 3;
  ExpectNotFiltered(shape);
}

// The handler passes the cause before the message.
TEST_F(RecordPatternFilterTest, ShouldNotFilterArgumentsInOtherOrder) {
  HandlerShape shape;
  shape.message = 0;
  shape.cause = 2;
  ExpectNotFiltered(shape);
}

// The handler throws the cause instead of the exception.
TEST_F(RecordPatternFilterTest, ShouldNotFilterThrowOfOtherRegister) {
  HandlerShape shape;
  shape.thrown = 0;
  ExpectNotFiltered(shape);
}

// The handler returns the exception instead of throwing it.
TEST_F(RecordPatternFilterTest, ShouldNotFilterHandlerThatReturns) {
  HandlerShape shape;
  shape.throws = false;
  ExpectNotFiltered(shape);
}

}  // namespace
}  // namespace coverage
