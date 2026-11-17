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

#include "tools/base/android-test/coverage/agent/native/try_with_resources_filter.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <initializer_list>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

namespace coverage {
namespace {

constexpr char kInputStream[] = "Ljava/io/InputStream;";
constexpr char kCloseable[] = "Ljava/io/Closeable;";
constexpr char kThrowable[] = "Ljava/lang/Throwable;";
constexpr char kOpen[] = "(Ljava/lang/String;)Ljava/io/InputStream;";
// The classes that D8 generates to close a resource for it.
constexpr char kDispatcher[] =
    "Lcom/example/Foo$$ExternalSyntheticAutoCloseableDispatcher0;";
constexpr char kForwarder[] =
    "Lcom/example/Foo$$ExternalSyntheticAutoCloseableForwarder1;";

// The code of a try-with-resources statement, as D8 lays it out, and what to
// change in it.
struct Shape {
  // Whether the resource can be null: it comes from a call instead of new.
  bool checks_null = true;
  // The class of the close in the handler, and the invoke, the name and the
  // signature of both closes, whether with a range of registers, and the class
  // of the close when the block ends, and its name or signature if they differ.
  const char* owner = kInputStream;
  dex::Opcode close_opcode = dex::OP_INVOKE_VIRTUAL;
  const char* close_name = "close";
  const char* close_signature = "()V";
  bool close_in_range = false;
  const char* block_end_owner = kInputStream;
  const char* block_end_name = nullptr;
  const char* block_end_signature = nullptr;
  // Whether the block moves another value over the resource before its close.
  bool moves_over_resource = false;
  // The register that the block closes when it ends, the one that it checks
  // for null, and whether it checks one.
  dex::u4 block_end_resource = 0;
  dex::u4 block_end_checked = 0;
  bool block_end_checks_null = true;
  // The register that the handler checks for null.
  dex::u4 checked = 0;
  // The exception that the handler catches, or any.
  const char* caught_type = nullptr;
  // Whether the close in the handler has a try block that goes to the
  // suppression.
  bool close_in_try_block = true;
  // Whether the check for null in the handler jumps to the throw, or to the
  // return of the block, and the line of a throw of its own for a null
  // resource, if not 0.
  bool check_skips_to_throw = true;
  int null_throw_line = 0;
  // The call that suppresses the exception of the close: D8's backport of
  // addSuppressed with its signature, or a method of a class with a name, and
  // its arguments.
  bool backport = false;
  const char* backport_signature =
      "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V";
  const char* suppression_owner = kThrowable;
  const char* suppression_name = "addSuppressed";
  std::vector<dex::u4> suppression_arguments = {1, 2};
  // The registers that the handler throws after the close and after the
  // suppression, with one throw if they are the same and the throws aren't
  // separate.
  dex::u4 thrown = 1;
  dex::u4 thrown_after_suppression = 1;
  bool separate_throws = false;
  // The lines of the close, the suppression and the throw of the handler, and
  // of the throw after the close if it is separate.
  int close_line = 20;
  int suppression_line = 20;
  int throw_line = 20;
  int throw_after_close_line = 20;
};

// The bytecodes of a statement that the filter ignores, in code order.
struct Statement {
  lir::Instruction* check = nullptr;
  lir::Instruction* close = nullptr;
  lir::Instruction* caught = nullptr;
  lir::Instruction* handler_check = nullptr;
  lir::Instruction* handler_close = nullptr;
  lir::Instruction* to_throw = nullptr;
  lir::Instruction* suppressed = nullptr;
  lir::Instruction* suppression = nullptr;
  lir::Instruction* throw_after_suppression = nullptr;
  lir::Instruction* rethrow = nullptr;
  lir::Instruction* null_throw = nullptr;
};

// Unit tests for TryWithResourcesFilter.
class TryWithResourcesFilterTest : public FilterTestBase {
 protected:
  // Returns one range for each of `instrs` that isn't null, in this order.
  static std::vector<Range> Each(
      std::initializer_list<lir::Instruction*> instrs) {
    std::vector<Range> ranges;
    for (lir::Instruction* instr : instrs) {
      if (instr != nullptr) {
        ranges.push_back(Range{instr, instr});
      }
    }
    return ranges;
  }

  static std::vector<Range> Ignored(const Statement& s) {
    return Each({s.check, s.close, s.caught, s.handler_check, s.handler_close,
                 s.to_throw, s.suppressed, s.suppression,
                 s.throw_after_suppression, s.rethrow, s.null_throw});
  }

  // Appends the code of `int read(String path)` with `try (InputStream in =
  // open(path)) { return in.read(); }` in the shape that D8 gives it, changed
  // as `shape` says. The resource is in v0, the result in v1, the caught
  // exception in v1 and the exception of the close in v2.
  static Statement AddStatement(TestMethod& m, const Shape& shape) {
    Statement s;
    lir::Label* after = m.NewLabel();
    lir::Label* handler = m.NewLabel();
    lir::Label* suppress = m.NewLabel();
    lir::Label* rethrow = m.NewLabel();
    lir::Label* null_throw =
        shape.null_throw_line != 0 ? m.NewLabel() : rethrow;
    m.AddLine(20);
    if (shape.checks_null) {
      m.Add(dex::OP_INVOKE_STATIC,
            {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
      m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    } else {
      m.Add(dex::OP_NEW_INSTANCE,
            {m.Reg(0), m.TypeRef("Ljava/io/FileInputStream;")});
      m.Add(dex::OP_INVOKE_DIRECT,
            {m.Regs({0, 3}), m.MethodRef("Ljava/io/FileInputStream;", "<init>",
                                         "(Ljava/lang/String;)V")});
    }
    lir::TryBlockBegin* block = m.AddTryBlockBegin();
    m.AddLine(21);
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
    m.AddTryBlockEnd(block, handler, shape.caught_type);
    m.AddLine(22);
    if (shape.moves_over_resource) {
      m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(5)});
    }
    if (shape.block_end_checks_null) {
      s.check = m.Add(dex::OP_IF_EQZ,
                      {m.Reg(shape.block_end_checked), m.Target(after)});
    }
    // The registers that a close of v<reg> passes.
    auto closed = [&](dex::u4 reg) -> lir::Operand* {
      if (shape.close_in_range) {
        return m.RegRange(reg, 1);
      }
      return m.Regs({reg});
    };
    s.close = m.Add(
        shape.close_opcode,
        {closed(shape.block_end_resource),
         m.MethodRef(shape.block_end_owner,
                     shape.block_end_name == nullptr ? shape.close_name
                                                     : shape.block_end_name,
                     shape.block_end_signature == nullptr
                         ? shape.close_signature
                         : shape.block_end_signature)});
    m.Place(after);
    m.AddLine(21);
    m.Add(dex::OP_RETURN, {m.Reg(1)});
    m.Place(handler);
    m.AddLine(20);
    s.caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
    if (shape.checks_null) {
      s.handler_check =
          m.Add(dex::OP_IF_EQZ,
                {m.Reg(shape.checked),
                 m.Target(shape.check_skips_to_throw ? null_throw : after)});
    }
    lir::TryBlockBegin* closing =
        shape.close_in_try_block ? m.AddTryBlockBegin() : nullptr;
    m.AddLine(shape.close_line);
    s.handler_close = m.Add(
        shape.close_opcode,
        {closed(0),
         m.MethodRef(shape.owner, shape.close_name, shape.close_signature)});
    if (closing != nullptr) {
      m.AddTryBlockEnd(closing, suppress);
    }
    s.to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
    m.Place(suppress);
    s.suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
    m.AddLine(shape.suppression_line);
    if (shape.backport) {
      s.suppression =
          m.Add(dex::OP_INVOKE_STATIC,
                {m.Regs(shape.suppression_arguments),
                 m.MethodRef("LFoo$0;", "m", shape.backport_signature)});
    } else {
      s.suppression =
          m.Add(dex::OP_INVOKE_VIRTUAL,
                {m.Regs(shape.suppression_arguments),
                 m.MethodRef(shape.suppression_owner, shape.suppression_name,
                             "(Ljava/lang/Throwable;)V")});
    }
    m.AddLine(shape.throw_line);
    if (shape.separate_throws ||
        shape.thrown_after_suppression != shape.thrown) {
      s.throw_after_suppression =
          m.Add(dex::OP_THROW, {m.Reg(shape.thrown_after_suppression)});
      m.AddLine(shape.throw_after_close_line);
    }
    m.Place(rethrow);
    s.rethrow = m.Add(dex::OP_THROW, {m.Reg(shape.thrown)});
    if (shape.null_throw_line != 0) {
      m.Place(null_throw);
      m.AddLine(shape.null_throw_line);
      s.null_throw = m.Add(dex::OP_THROW, {m.Reg(1)});
    }
    return s;
  }

  // Runs the filter on a method with the statement of `shape` and expects it
  // to ignore nothing.
  void ExpectNotFiltered(const Shape& shape) {
    TestMethod m("read", "(Ljava/lang/String;)I");
    AddStatement(m, shape);

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

    ExpectIgnored(m, {});
  }

  TryWithResourcesFilter filter_;
};

// try (InputStream in = open(path)) {
//   return in.read();
// }
// The block ends with a close that checks the resource for null, and the
// handler closes it too, suppresses the exception of that close and throws.
TEST_F(TryWithResourcesFilterTest, ShouldFilterResourceThatCanBeNull) {
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, Shape());

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
}

// try (InputStream in = new FileInputStream(path)) { ... }
// javac leaves out the checks for null.
TEST_F(TryWithResourcesFilterTest, ShouldFilterResourceCreatedWithNew) {
  Shape shape;
  shape.checks_null = false;
  shape.block_end_checks_null = false;
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
  EXPECT_EQ(s.check, nullptr);
  EXPECT_EQ(s.handler_check, nullptr);
}

// try (Closeable r = c) { ... }
TEST_F(TryWithResourcesFilterTest, ShouldFilterCloseThroughInterface) {
  Shape shape;
  shape.owner = kCloseable;
  shape.block_end_owner = kCloseable;
  shape.close_opcode = dex::OP_INVOKE_INTERFACE;
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
}

// Below API 19, D8 replaces addSuppressed() with a static backport.
TEST_F(TryWithResourcesFilterTest, ShouldFilterBackportOfAddSuppressed) {
  Shape shape;
  shape.backport = true;
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
}

// D8 turns a catch of Throwable into a catch of any exception. A typed catch
// of Throwable is handled the same way.
TEST_F(TryWithResourcesFilterTest, ShouldFilterHandlerOfThrowable) {
  Shape shape;
  shape.caught_type = kThrowable;
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
}

// A catch of Throwable gets every exception that no earlier handler of the try
// block catches, so the catch of any exception never runs.
TEST_F(TryWithResourcesFilterTest, ShouldTakeCatchOfThrowableBeforeCatchAll) {
  Shape shape;
  shape.caught_type = kThrowable;
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);
  lir::Label* other = m.NewLabel();
  for (auto* instr : m.code_ir().instructions) {
    auto* end = dynamic_cast<lir::TryBlockEnd*>(instr);
    if (end != nullptr && !end->handlers.empty()) {
      end->catch_all = other;
      ++other->refCount;
    }
  }
  m.Place(other);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(4)});
  m.Add(dex::OP_THROW, {m.Reg(4)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
}

// try (AutoCloseable r = open(path)) { ... }, or with a TypedArray below API
// 31: from API 19 on, D8 replaces close() with a static method of a class that
// it generates, which takes the resource. With the resource in v16 or above,
// it calls that method with invoke-static/range, and a resource created with
// new isn't checked for null.
TEST_F(TryWithResourcesFilterTest, ShouldFilterDispatcherOfD8) {
  for (bool range : {false, true}) {
    for (bool checks_null : {true, false}) {
      SCOPED_TRACE(testing::Message() << range << checks_null);
      Shape shape;
      shape.owner = kDispatcher;
      shape.block_end_owner = kDispatcher;
      shape.close_opcode =
          range ? dex::OP_INVOKE_STATIC_RANGE : dex::OP_INVOKE_STATIC;
      shape.close_in_range = range;
      shape.close_name = "m";
      shape.close_signature = "(Ljava/lang/Object;)V";
      shape.checks_null = checks_null;
      shape.block_end_checks_null = checks_null;
      TestMethod m("read", "(Ljava/lang/String;)I");
      Statement s = AddStatement(m, shape);
      output_ = Output();

      filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

      ExpectIgnored(m, Ignored(s));
    }
  }
}

// try (ExecutorService e = executor()) { ... }: D8 can also pass the resource
// to a static method that takes an ExecutorService.
TEST_F(TryWithResourcesFilterTest, ShouldFilterForwarderOfD8) {
  Shape shape;
  shape.owner = kForwarder;
  shape.block_end_owner = kForwarder;
  shape.close_opcode = dex::OP_INVOKE_STATIC;
  shape.close_name = "m";
  shape.close_signature = "(Ljava/util/concurrent/ExecutorService;)V";
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
}

// Below API 19, D8 replaces close() of a TypedArray with recycle(), which the
// class had before it became AutoCloseable.
TEST_F(TryWithResourcesFilterTest, ShouldFilterRecycleOfTypedArray) {
  Shape shape;
  shape.owner = "Landroid/content/res/TypedArray;";
  shape.block_end_owner = shape.owner;
  shape.close_name = "recycle";
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
}

// Below API 19, D8 replaces close() of a ContentProviderClient with release(),
// which returns whether it released the client.
TEST_F(TryWithResourcesFilterTest, ShouldFilterReleaseThatReturnsValue) {
  Shape shape;
  shape.owner = "Landroid/content/ContentProviderClient;";
  shape.block_end_owner = shape.owner;
  shape.close_name = "release";
  shape.close_signature = "()Z";
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
}

// The throw for a null resource can be one of its own, on the line of the
// statement too.
TEST_F(TryWithResourcesFilterTest, ShouldFilterThrowOfNullResource) {
  Shape shape;
  shape.null_throw_line = 20;
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
  EXPECT_NE(s.null_throw, nullptr);
}

TEST_F(TryWithResourcesFilterTest, ShouldRunOnJavaClasses) {
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, Shape());

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(s.close));
  EXPECT_TRUE(result.IsIgnored(s.caught));
}

// Kotlin has no try-with-resources statement: use {} compiles to other code.
TEST_F(TryWithResourcesFilterTest, ShouldNotRunOnKotlinClasses) {
  context_.class_annotations.insert(kKotlinMetadataDescriptor);
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, Shape());

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_FALSE(result.IsIgnored(s.close));
  EXPECT_FALSE(result.IsIgnored(s.caught));
}

// try (InputStream x = open(a)) {
//   n = x.read();
// }
// InputStream other = open(b);
// if (other != null) {
//   other.close();
// }
// return n;
// D8 puts the handler after the code that follows the statement and reuses
// the register of x for other, so the close of other comes last before the
// handler. The register is written again before it, so it isn't the close of
// x, which comes first.
TEST_F(TryWithResourcesFilterTest, ShouldNotTakeCloseOfLaterCode) {
  TestMethod m("thenUserClose", "(Ljava/lang/String;Ljava/lang/String;)I");
  lir::Label* after = m.NewLabel();
  lir::Label* after_other = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(23);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(24);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
  m.AddTryBlockEnd(block, handler);
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
  m.AddLine(25);
  lir::Instruction* check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
  lir::Instruction* close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.AddLine(26);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({4}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.AddLine(27);
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after_other)});
  m.AddLine(28);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after_other);
  m.AddLine(30);
  m.Add(dex::OP_RETURN, {m.Reg(1)});
  m.Place(handler);
  m.AddLine(23);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* handler_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  lir::Instruction* handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  lir::Instruction* suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::Instruction* suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 2}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({check, close, caught, handler_check, handler_close,
                         to_throw, suppressed, suppression, thrown}));
}

// try (InputStream x = open(a)) {
//   n = x.read();
// }
// try (InputStream y = open(b)) {
//   n += y.read();
// }
// return n;
// D8 reuses the register of x for y, and puts the handler of y, then the one
// of x, after the return. Each statement keeps its own close, also when the
// resources are created with new and nothing checks them for null.
TEST_F(TryWithResourcesFilterTest, ShouldFilterTwoStatementsInARow) {
  for (bool can_be_null : {true, false}) {
    SCOPED_TRACE(can_be_null);
    TestMethod m("twoInARow", "(Ljava/lang/String;Ljava/lang/String;)I");
    lir::Label* x_after = m.NewLabel();
    lir::Label* y_after = m.NewLabel();
    lir::Label* x_handler = m.NewLabel();
    lir::Label* y_handler = m.NewLabel();
    lir::Label* x_suppress = m.NewLabel();
    lir::Label* y_suppress = m.NewLabel();
    lir::Label* x_rethrow = m.NewLabel();
    lir::Label* y_rethrow = m.NewLabel();
    // Opens the resource of `path` in v0.
    auto open = [&](dex::u4 path) {
      if (can_be_null) {
        m.Add(dex::OP_INVOKE_STATIC,
              {m.Regs({path}), m.MethodRef(kTestClassName, "open", kOpen)});
        m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
      } else {
        m.Add(dex::OP_NEW_INSTANCE,
              {m.Reg(0), m.TypeRef("Ljava/io/FileInputStream;")});
        m.Add(dex::OP_INVOKE_DIRECT,
              {m.Regs({0, path}),
               m.MethodRef("Ljava/io/FileInputStream;", "<init>",
                           "(Ljava/lang/String;)V")});
      }
    };
    // Adds the check for null if the resource can be null, and returns it.
    auto check_for_null = [&](lir::Label* target) -> lir::Instruction* {
      return can_be_null ? m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(target)})
                         : nullptr;
    };
    auto close_resource = [&]() {
      return m.Add(dex::OP_INVOKE_VIRTUAL,
                   {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
    };
    m.AddLine(11);
    open(4);
    lir::TryBlockBegin* x_block = m.AddTryBlockBegin();
    m.AddLine(12);
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
    m.AddTryBlockEnd(x_block, x_handler);
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
    m.AddLine(13);
    lir::Instruction* x_check = check_for_null(x_after);
    lir::Instruction* x_close = close_resource();
    m.Place(x_after);
    m.AddLine(14);
    open(5);
    lir::TryBlockBegin* y_block = m.AddTryBlockBegin();
    m.AddLine(15);
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
    m.AddTryBlockEnd(y_block, y_handler);
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(2)});
    m.Add(dex::OP_ADD_INT_2ADDR, {m.Reg(1), m.Reg(2)});
    m.AddLine(16);
    lir::Instruction* y_check = check_for_null(y_after);
    lir::Instruction* y_close = close_resource();
    m.Place(y_after);
    m.AddLine(17);
    m.Add(dex::OP_RETURN, {m.Reg(1)});
    // The handler of y, then the one of x.
    std::vector<lir::Instruction*> handler_code[2];
    struct {
      lir::Label* handler;
      lir::Label* suppress;
      lir::Label* rethrow;
      int line;
    } handlers[] = {{y_handler, y_suppress, y_rethrow, 14},
                    {x_handler, x_suppress, x_rethrow, 11}};
    for (int i = 0; i < 2; ++i) {
      std::vector<lir::Instruction*>& code = handler_code[i];
      m.Place(handlers[i].handler);
      m.AddLine(handlers[i].line);
      code.push_back(m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)}));
      if (lir::Instruction* checked = check_for_null(handlers[i].rethrow)) {
        code.push_back(checked);
      }
      lir::TryBlockBegin* closing = m.AddTryBlockBegin();
      code.push_back(close_resource());
      m.AddTryBlockEnd(closing, handlers[i].suppress);
      code.push_back(m.Add(dex::OP_GOTO, {m.Target(handlers[i].rethrow)}));
      m.Place(handlers[i].suppress);
      code.push_back(m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(3)}));
      code.push_back(
          m.Add(dex::OP_INVOKE_VIRTUAL,
                {m.Regs({2, 3}), m.MethodRef(kThrowable, "addSuppressed",
                                             "(Ljava/lang/Throwable;)V")}));
      m.Place(handlers[i].rethrow);
      code.push_back(m.Add(dex::OP_THROW, {m.Reg(2)}));
    }

    Output output;
    filter_.Filter(context_, m.ir_method(), m.code_ir(), output);

    // The statement of x first, since its try block ends first.
    std::vector<Range> expected = Each({x_check, x_close});
    for (lir::Instruction* instr : handler_code[1]) {
      expected.push_back(Range{instr, instr});
    }
    for (const Range& range : Each({y_check, y_close})) {
      expected.push_back(range);
    }
    for (lir::Instruction* instr : handler_code[0]) {
      expected.push_back(Range{instr, instr});
    }
    ASSERT_EQ(output.ignored().size(), expected.size());
    for (size_t i = 0; i < expected.size(); ++i) {
      EXPECT_EQ(output.ignored()[i].from, expected[i].from) << i;
      EXPECT_EQ(output.ignored()[i].to, expected[i].to) << i;
    }
  }
}

// if (flag) {
//   FileInputStream probe = new FileInputStream(a);
//   probe.close();
// }
// try (FileInputStream in = new FileInputStream(a)) {
//   throw new IOException(a);
// }
// D8 reuses the register of probe for in. The close of probe comes before the
// block, so the statement has no close, and nothing is filtered.
TEST_F(TryWithResourcesFilterTest, ShouldNotTakeCloseBeforeTheBlock) {
  TestMethod m("throwsAfterClose", "(Ljava/lang/String;Z)V");
  lir::Label* skip = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(47);
  m.Add(dex::OP_IF_EQZ, {m.Reg(4), m.Target(skip)});
  m.AddLine(48);
  m.Add(dex::OP_NEW_INSTANCE,
        {m.Reg(0), m.TypeRef("Ljava/io/FileInputStream;")});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 3}), m.MethodRef("Ljava/io/FileInputStream;", "<init>",
                                     "(Ljava/lang/String;)V")});
  m.AddLine(49);
  m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({0}), m.MethodRef("Ljava/io/FileInputStream;", "close", "()V")});
  m.Place(skip);
  m.AddLine(51);
  m.Add(dex::OP_NEW_INSTANCE,
        {m.Reg(0), m.TypeRef("Ljava/io/FileInputStream;")});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 3}), m.MethodRef("Ljava/io/FileInputStream;", "<init>",
                                     "(Ljava/lang/String;)V")});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(52);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef("Ljava/io/IOException;")});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 3}), m.MethodRef("Ljava/io/IOException;", "<init>",
                                     "(Ljava/lang/String;)V")});
  m.Add(dex::OP_THROW, {m.Reg(1)});
  m.AddTryBlockEnd(block, handler);
  m.Place(handler);
  m.AddLine(51);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({0}), m.MethodRef("Ljava/io/FileInputStream;", "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1, 2}),
         m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// try (FileInputStream in = new FileInputStream(a)) {
//   in.read();
//   in.close();
//   throw new IOException(a);
// }
// A close in the block isn't the close when the block ends.
TEST_F(TryWithResourcesFilterTest, ShouldNotTakeCloseInTheBlock) {
  TestMethod m("closesInBlock", "(Ljava/lang/String;)V");
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(51);
  m.Add(dex::OP_NEW_INSTANCE,
        {m.Reg(0), m.TypeRef("Ljava/io/FileInputStream;")});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 3}), m.MethodRef("Ljava/io/FileInputStream;", "<init>",
                                     "(Ljava/lang/String;)V")});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(52);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef("Ljava/io/FileInputStream;", "read", "()I")});
  m.AddLine(53);
  m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({0}), m.MethodRef("Ljava/io/FileInputStream;", "close", "()V")});
  m.AddLine(54);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef("Ljava/io/IOException;")});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 3}), m.MethodRef("Ljava/io/IOException;", "<init>",
                                     "(Ljava/lang/String;)V")});
  m.Add(dex::OP_THROW, {m.Reg(1)});
  m.AddTryBlockEnd(block, handler);
  m.Place(handler);
  m.AddLine(51);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({0}), m.MethodRef("Ljava/io/FileInputStream;", "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1, 2}),
         m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterHandlerOfOtherException) {
  Shape shape;
  shape.caught_type = "Ljava/io/IOException;";
  ExpectNotFiltered(shape);
}

// try (InputStream in = open(path)) {
//   throw new IOException(path);
// }
// A block that always throws has no close when it ends, and its handler stays.
TEST_F(TryWithResourcesFilterTest, ShouldNotFilterWithoutCloseWhenBlockEnds) {
  TestMethod m("throwing", "(Ljava/lang/String;)V");
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(86);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(87);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(1), m.TypeRef("Ljava/io/IOException;")});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 3}), m.MethodRef("Ljava/io/IOException;", "<init>",
                                     "(Ljava/lang/String;)V")});
  m.Add(dex::OP_THROW, {m.Reg(1)});
  m.AddTryBlockEnd(block, handler);
  m.Place(handler);
  m.AddLine(86);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1, 2}),
         m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// try (InputStream x = open(a);
//      InputStream y = new FileInputStream(b)) {
//   return x.read() + y.read();
// }
// Each resource has its handler, and its close when the block ends, both with
// the line of the statement. The handler of y reuses the register of y for
// the caught exception.
TEST_F(TryWithResourcesFilterTest, ShouldFilterEachResource) {
  TestMethod m("two", "(Ljava/lang/String;Ljava/lang/String;)I");
  lir::Label* after = m.NewLabel();
  lir::Label* outer = m.NewLabel();
  lir::Label* inner = m.NewLabel();
  lir::Label* inner_suppress = m.NewLabel();
  lir::Label* inner_rethrow = m.NewLabel();
  lir::Label* outer_suppress = m.NewLabel();
  lir::Label* outer_rethrow = m.NewLabel();
  m.AddLine(32);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({4}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  lir::TryBlockBegin* x_block = m.AddTryBlockBegin();
  m.AddLine(33);
  m.Add(dex::OP_NEW_INSTANCE,
        {m.Reg(1), m.TypeRef("Ljava/io/FileInputStream;")});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 5}), m.MethodRef("Ljava/io/FileInputStream;", "<init>",
                                     "(Ljava/lang/String;)V")});
  m.AddTryBlockEnd(x_block, outer);
  lir::TryBlockBegin* y_block = m.AddTryBlockBegin();
  m.AddLine(34);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(2)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1}), m.MethodRef(kInputStream, "read", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(3)});
  m.Add(dex::OP_ADD_INT_2ADDR, {m.Reg(2), m.Reg(3)});
  m.AddTryBlockEnd(y_block, inner);
  lir::TryBlockBegin* y_close_block = m.AddTryBlockBegin();
  m.AddLine(35);
  lir::Instruction* y_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({1}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(y_close_block, outer);
  lir::Instruction* x_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
  lir::Instruction* x_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.AddLine(34);
  m.Add(dex::OP_RETURN, {m.Reg(2)});
  // The handler of y.
  m.Place(inner);
  m.AddLine(32);
  lir::Instruction* y_caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::TryBlockBegin* y_closing = m.AddTryBlockBegin();
  lir::Instruction* y_handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({1}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(y_closing, inner_suppress);
  lir::Instruction* y_to_throw = m.Add(dex::OP_GOTO, {m.Target(inner_rethrow)});
  m.Place(inner_suppress);
  lir::Instruction* y_suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(3)});
  lir::TryBlockBegin* y_suppressing = m.AddTryBlockBegin();
  lir::Instruction* y_suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({2, 3}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(inner_rethrow);
  lir::Instruction* y_rethrow = m.Add(dex::OP_THROW, {m.Reg(2)});
  m.AddTryBlockEnd(y_suppressing, outer);
  // The handler of x.
  m.Place(outer);
  lir::Instruction* x_caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* x_handler_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(outer_rethrow)});
  lir::TryBlockBegin* x_closing = m.AddTryBlockBegin();
  lir::Instruction* x_handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(x_closing, outer_suppress);
  lir::Instruction* x_to_throw = m.Add(dex::OP_GOTO, {m.Target(outer_rethrow)});
  m.Place(outer_suppress);
  lir::Instruction* x_suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::Instruction* x_suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 2}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(outer_rethrow);
  lir::Instruction* x_rethrow = m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  // The statement of x first, since its try block ends first.
  std::vector<Range> expected =
      Each({x_check, x_close, x_caught, x_handler_check, x_handler_close,
            x_to_throw, x_suppressed, x_suppression, x_rethrow});
  for (const Range& range :
       Each({y_close, y_caught, y_handler_close, y_to_throw, y_suppressed,
             y_suppression, y_rethrow})) {
    expected.push_back(range);
  }
  ExpectIgnored(m, expected);
}

// try (InputStream in = open(path)) {
//   if (flag) {
//     return 1;
//   }
//   return in.read();
// }
// Each return closes the resource. Only the close right before the handler
// counts as the one of the block, so the other one stays with its check.
TEST_F(TryWithResourcesFilterTest, ShouldKeepEarlierCloseOfReturn) {
  TestMethod m("branch", "(Ljava/lang/String;Z)I");
  lir::Label* read = m.NewLabel();
  lir::Label* one = m.NewLabel();
  lir::Label* after = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(69);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.AddLine(70);
  m.Add(dex::OP_IF_EQZ, {m.Reg(4), m.Target(read)});
  m.AddLine(74);
  lir::Instruction* early_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(one)});
  lir::Instruction* early_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(one);
  m.AddLine(71);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  m.Add(dex::OP_RETURN, {m.Reg(1)});
  m.Place(read);
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(73);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
  m.AddTryBlockEnd(block, handler);
  m.AddLine(74);
  lir::Instruction* check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
  lir::Instruction* close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.AddLine(73);
  m.Add(dex::OP_RETURN, {m.Reg(1)});
  m.Place(handler);
  m.AddLine(69);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* handler_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  lir::Instruction* handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  lir::Instruction* suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::Instruction* suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 2}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({check, close, caught, handler_check, handler_close,
                         to_throw, suppressed, suppression, thrown}));
  EXPECT_NE(early_check, check);
  EXPECT_NE(early_close, close);
}

// D8 can jump from the backport through two gotos to the throw, which it puts
// between them.
TEST_F(TryWithResourcesFilterTest, ShouldFilterHandlerSplitByGotos) {
  TestMethod m("loop", "(Ljava/lang/String;)I");
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* first_jump = m.NewLabel();
  lir::Label* second_jump = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(93);
  m.Add(dex::OP_NEW_INSTANCE,
        {m.Reg(1), m.TypeRef("Ljava/io/BufferedReader;")});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1, 4}), m.MethodRef("Ljava/io/BufferedReader;", "<init>",
                                     "(Ljava/io/Reader;)V")});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(94);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1}), m.MethodRef("Ljava/io/BufferedReader;", "readLine",
                                  "()Ljava/lang/String;")});
  m.AddTryBlockEnd(block, handler);
  m.AddLine(97);
  lir::Instruction* close = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1}), m.MethodRef("Ljava/io/BufferedReader;", "close", "()V")});
  m.AddLine(98);
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  m.Place(handler);
  m.AddLine(93);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  lir::Instruction* handler_close = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1}), m.MethodRef("Ljava/io/BufferedReader;", "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  lir::Instruction* to_first = m.Add(dex::OP_GOTO, {m.Target(first_jump)});
  m.Place(suppress);
  lir::Instruction* suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(3)});
  lir::Instruction* suppression =
      m.Add(dex::OP_INVOKE_STATIC,
            {m.Regs({2, 3}),
             m.MethodRef("LFoo$0;", "m",
                         "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V")});
  m.Place(first_jump);
  lir::Instruction* to_second = m.Add(dex::OP_GOTO, {m.Target(second_jump)});
  m.Place(rethrow);
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(2)});
  m.Place(second_jump);
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({close, caught, handler_close, to_first, suppressed,
                         suppression, to_second, thrown, to_throw}));
}

// When many registers are live, D8 can copy the resource from a high register
// in the handler, and copy the caught exception before throwing it. The block
// then closes the resource in the high register with invoke-virtual/range.
TEST_F(TryWithResourcesFilterTest, ShouldFilterHandlerWithMovesOfD8) {
  TestMethod m("spilled", "()V");
  lir::Label* after = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(20);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_MOVE_OBJECT_FROM16, {m.Reg(20), m.Reg(0)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(21);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "user", "()V")});
  m.AddTryBlockEnd(block, handler);
  m.AddLine(22);
  lir::Instruction* check = m.Add(dex::OP_IF_EQZ, {m.Reg(20), m.Target(after)});
  lir::Instruction* close =
      m.Add(dex::OP_INVOKE_VIRTUAL_RANGE,
            {m.RegRange(20, 1), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.Add(dex::OP_RETURN_VOID);
  m.Place(handler);
  m.AddLine(20);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* move =
      m.Add(dex::OP_MOVE_OBJECT_FROM16, {m.Reg(0), m.Reg(20)});
  lir::Instruction* handler_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  lir::Instruction* handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  lir::Instruction* suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::Instruction* suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 2}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  lir::Instruction* copy = m.Add(dex::OP_MOVE_OBJECT, {m.Reg(3), m.Reg(1)});
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(3)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m,
                Each({check, close, caught, move, handler_check, handler_close,
                      to_throw, suppressed, suppression, copy, thrown}));
}

// A goto right after the close when the block ends belongs to it, as when
// code follows the statement.
TEST_F(TryWithResourcesFilterTest, ShouldFilterGotoAfterCloseOfBlock) {
  TestMethod m("then", "(Ljava/lang/String;)V");
  lir::Label* after = m.NewLabel();
  lir::Label* next = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(20);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(21);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
  m.AddTryBlockEnd(block, handler);
  m.AddLine(22);
  lir::Instruction* check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
  lir::Instruction* close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  lir::Instruction* to_next = m.Add(dex::OP_GOTO, {m.Target(next)});
  m.Place(handler);
  m.AddLine(20);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* handler_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  lir::Instruction* handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  lir::Instruction* suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::Instruction* suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 2}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(1)});
  m.Place(after);
  m.Place(next);
  m.AddLine(23);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(
      m, Each({check, close, to_next, caught, handler_check, handler_close,
               to_throw, suppressed, suppression, thrown}));
}

// D8 can put the close of the handler before the handler and jump there. The
// close of the handler isn't the one of the block, which comes before it.
TEST_F(TryWithResourcesFilterTest, ShouldNotTakeCloseOfHandlerForCloseOfBlock) {
  TestMethod m("moved", "(Ljava/lang/String;)I");
  lir::Label* after = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* closing_code = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(20);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(21);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
  m.AddTryBlockEnd(block, handler);
  m.AddLine(22);
  lir::Instruction* check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
  lir::Instruction* close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.AddLine(21);
  m.Add(dex::OP_RETURN, {m.Reg(1)});
  m.Place(closing_code);
  m.AddLine(20);
  lir::Instruction* handler_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  lir::Instruction* handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* to_closing = m.Add(dex::OP_GOTO, {m.Target(closing_code)});
  m.Place(suppress);
  lir::Instruction* suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::Instruction* suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 2}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({check, close, handler_check, handler_close, to_throw,
                         caught, to_closing, suppressed, suppression, thrown}));
}

// static int handWritten(String path) throws IOException {
//   InputStream in = open(path);
//   int result;
//   try {
//     result = in.read();
//   } catch (Throwable t) {
//     if (in != null) {
//       try {
//         in.close();
//       } catch (Throwable s) {
//         t.addSuppressed(s);
//       }
//     }
//     throw t;
//   }
//   if (in != null) {
//     in.close();
//   }
//   return result;
// }
// D8 lays out this code like a try-with-resources statement, but the close,
// the suppression and the throw of the handler are on lines of their own.
TEST_F(TryWithResourcesFilterTest, ShouldNotFilterHandlerOnSeveralLines) {
  Shape shape;
  shape.close_line = 110;
  shape.suppression_line = 112;
  shape.throw_line = 115;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCloseOnOtherLine) {
  Shape shape;
  shape.close_line = 21;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterSuppressionOnOtherLine) {
  Shape shape;
  shape.suppression_line = 21;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterThrowOnOtherLine) {
  Shape shape;
  shape.throw_line = 21;
  ExpectNotFiltered(shape);
}

// The paths after the close and after the suppression can end with throws of
// their own.
TEST_F(TryWithResourcesFilterTest, ShouldFilterSeparateThrows) {
  Shape shape;
  shape.separate_throws = true;
  TestMethod m("read", "(Ljava/lang/String;)I");
  Statement s = AddStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(s));
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterThrowAfterCloseOnOtherLine) {
  Shape shape;
  shape.separate_throws = true;
  shape.throw_after_close_line = 21;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest,
       ShouldNotFilterThrowAfterSuppressionOnOtherLine) {
  Shape shape;
  shape.separate_throws = true;
  shape.throw_line = 21;
  shape.throw_after_close_line = 20;
  ExpectNotFiltered(shape);
}

// D8 can put a piece of the handler before the close when the block ends, and
// that piece can write the register of the resource after its own close. Code
// of the handler doesn't end the code that can hold that close.
TEST_F(TryWithResourcesFilterTest, ShouldFilterHandlerThatWritesResource) {
  TestMethod m("moved", "(Ljava/lang/String;)I");
  lir::Label* block_end = m.NewLabel();
  lir::Label* after = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* closing_code = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(20);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(21);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
  m.AddTryBlockEnd(block, handler);
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(2)});
  m.Add(dex::OP_GOTO, {m.Target(block_end)});
  m.Place(closing_code);
  m.AddLine(20);
  lir::Instruction* handler_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  lir::Instruction* handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  lir::Instruction* reuse = m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(5)});
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(block_end);
  m.AddLine(22);
  lir::Instruction* check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
  lir::Instruction* close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.AddLine(21);
  m.Add(dex::OP_RETURN, {m.Reg(2)});
  m.Place(handler);
  m.AddLine(20);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* to_closing = m.Add(dex::OP_GOTO, {m.Target(closing_code)});
  m.Place(suppress);
  lir::Instruction* suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::Instruction* suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 2}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(
      m, Each({handler_check, handler_close, reuse, to_throw, check, close,
               caught, to_closing, suppressed, suppression, thrown}));
}

// With values in v16 and above, D8 uses the /range invokes, here for a close
// through an interface and for addSuppressed or its backport.
TEST_F(TryWithResourcesFilterTest, ShouldFilterRangeInvokes) {
  for (bool backport : {false, true}) {
    SCOPED_TRACE(backport);
    TestMethod m("ranges", "(Ljava/io/Closeable;)V");
    lir::Label* after = m.NewLabel();
    lir::Label* handler = m.NewLabel();
    lir::Label* suppress = m.NewLabel();
    lir::Label* rethrow = m.NewLabel();
    m.AddLine(63);
    m.Add(dex::OP_MOVE_OBJECT_FROM16, {m.Reg(20), m.Reg(24)});
    lir::TryBlockBegin* block = m.AddTryBlockBegin();
    m.AddLine(64);
    m.Add(dex::OP_INVOKE_STATIC,
          {m.Regs({}), m.MethodRef(kTestClassName, "user", "()V")});
    m.AddTryBlockEnd(block, handler);
    m.AddLine(65);
    lir::Instruction* check =
        m.Add(dex::OP_IF_EQZ, {m.Reg(20), m.Target(after)});
    lir::Instruction* close =
        m.Add(dex::OP_INVOKE_INTERFACE_RANGE,
              {m.RegRange(20, 1), m.MethodRef(kCloseable, "close", "()V")});
    m.Place(after);
    m.AddLine(66);
    m.Add(dex::OP_RETURN_VOID);
    m.Place(handler);
    m.AddLine(63);
    lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
    lir::Instruction* copy =
        m.Add(dex::OP_MOVE_OBJECT_FROM16, {m.Reg(21), m.Reg(1)});
    lir::Instruction* handler_check =
        m.Add(dex::OP_IF_EQZ, {m.Reg(20), m.Target(rethrow)});
    lir::TryBlockBegin* closing = m.AddTryBlockBegin();
    lir::Instruction* handler_close =
        m.Add(dex::OP_INVOKE_INTERFACE_RANGE,
              {m.RegRange(20, 1), m.MethodRef(kCloseable, "close", "()V")});
    m.AddTryBlockEnd(closing, suppress);
    lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
    m.Place(suppress);
    lir::Instruction* suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
    lir::Instruction* suppressed_copy =
        m.Add(dex::OP_MOVE_OBJECT_FROM16, {m.Reg(22), m.Reg(2)});
    lir::Instruction* suppression =
        backport ? m.Add(dex::OP_INVOKE_STATIC_RANGE,
                         {m.RegRange(21, 2),
                          m.MethodRef(
                              "LFoo$0;", "m",
                              "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V")})
                 : m.Add(dex::OP_INVOKE_VIRTUAL_RANGE,
                         {m.RegRange(21, 2),
                          m.MethodRef(kThrowable, "addSuppressed",
                                      "(Ljava/lang/Throwable;)V")});
    m.Place(rethrow);
    lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(1)});

    Output output;
    filter_.Filter(context_, m.ir_method(), m.code_ir(), output);

    std::vector<Range> expected =
        Each({check, close, caught, copy, handler_check, handler_close,
              to_throw, suppressed, suppressed_copy, suppression, thrown});
    ASSERT_EQ(output.ignored().size(), expected.size());
    for (size_t i = 0; i < expected.size(); ++i) {
      EXPECT_EQ(output.ignored()[i].from, expected[i].from) << i;
      EXPECT_EQ(output.ignored()[i].to, expected[i].to) << i;
    }
  }
}

// What to change in the statement of AddCopiedStatement().
struct CopyShape {
  // The register that the block copies to v16 before its close.
  dex::u4 copied = 1;
  // Where the block makes the copy: after the try block, in it after the read,
  // or first in it.
  enum class Place { kAfterBlock, kInBlock, kStartOfBlock };
  Place place = Place::kAfterBlock;
  // Whether the block writes v16 again before its close.
  bool overwrites_copy = false;
};

// The statement of `long sum(String path)`, whose method needs more than 16
// registers, with `try (InputStream in = open(path)) { return in.read(); }`.
// D8 keeps the resource in v1 for the read, then copies it to v16 to write
// the long into v0 and v1, and closes the copy, in the handler too:
//   invoke-virtual {v1}, Ljava/io/InputStream;->read()I    (in the try block)
//   move-result v0
//   move-object/from16 v16, v1
//   int-to-long v0, v0
//   if-eqz v16, :after
//   invoke-virtual/range {v16}, Ljava/io/InputStream;->close()V
struct CopiedStatement {
  lir::Instruction* check = nullptr;
  lir::Instruction* close = nullptr;
  lir::Instruction* caught = nullptr;
  lir::Instruction* handler_copy = nullptr;
  lir::Instruction* exception_copy = nullptr;
  lir::Instruction* handler_check = nullptr;
  lir::Instruction* handler_close = nullptr;
  lir::Instruction* to_throw = nullptr;
  lir::Instruction* suppressed = nullptr;
  lir::Instruction* suppression = nullptr;
  lir::Instruction* thrown = nullptr;
};

CopiedStatement AddCopiedStatement(TestMethod& m, const CopyShape& shape) {
  CopiedStatement s;
  lir::Label* after = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(20);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({20}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(1)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(21);
  if (shape.place == CopyShape::Place::kStartOfBlock) {
    m.Add(dex::OP_MOVE_OBJECT_FROM16, {m.Reg(16), m.Reg(shape.copied)});
  }
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1}), m.MethodRef(kInputStream, "read", "()I")});
  if (shape.place == CopyShape::Place::kInBlock) {
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    m.Add(dex::OP_MOVE_OBJECT_FROM16, {m.Reg(16), m.Reg(shape.copied)});
    m.AddTryBlockEnd(block, handler);
  } else {
    m.AddTryBlockEnd(block, handler);
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    if (shape.place == CopyShape::Place::kAfterBlock) {
      m.Add(dex::OP_MOVE_OBJECT_FROM16, {m.Reg(16), m.Reg(shape.copied)});
    }
  }
  m.Add(dex::OP_INT_TO_LONG, {m.code_ir().Alloc<lir::VRegPair>(0), m.Reg(0)});
  m.AddLine(22);
  if (shape.overwrites_copy) {
    m.Add(dex::OP_CONST_16, {m.Reg(16), m.Literal(0)});
  }
  s.check = m.Add(dex::OP_IF_EQZ, {m.Reg(16), m.Target(after)});
  s.close =
      m.Add(dex::OP_INVOKE_VIRTUAL_RANGE,
            {m.RegRange(16, 1), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.AddLine(21);
  m.Add(dex::OP_RETURN_WIDE, {m.code_ir().Alloc<lir::VRegPair>(0)});
  m.Place(handler);
  m.AddLine(20);
  s.caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  s.handler_copy = m.Add(dex::OP_MOVE_OBJECT_FROM16, {m.Reg(16), m.Reg(1)});
  s.exception_copy = m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(0)});
  s.handler_check = m.Add(dex::OP_IF_EQZ, {m.Reg(16), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  s.handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL_RANGE,
            {m.RegRange(16, 1), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  s.to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  s.suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  s.suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 0}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  s.thrown = m.Add(dex::OP_THROW, {m.Reg(1)});
  return s;
}

TEST_F(TryWithResourcesFilterTest, ShouldFollowCopyOfResource) {
  for (CopyShape::Place place :
       {CopyShape::Place::kAfterBlock, CopyShape::Place::kInBlock,
        CopyShape::Place::kStartOfBlock}) {
    SCOPED_TRACE(static_cast<int>(place));
    CopyShape shape;
    shape.place = place;
    TestMethod m("sum", "(Ljava/lang/String;)J");
    CopiedStatement s = AddCopiedStatement(m, shape);
    output_ = Output();

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

    ExpectIgnored(m, Each({s.check, s.close, s.caught, s.handler_copy,
                           s.exception_copy, s.handler_check, s.handler_close,
                           s.to_throw, s.suppressed, s.suppression, s.thrown}));
  }
}

// After the copy is written, it doesn't hold the resource anymore.
TEST_F(TryWithResourcesFilterTest, ShouldNotFollowCopyWrittenBeforeClose) {
  CopyShape shape;
  shape.overwrites_copy = true;
  TestMethod m("sum", "(Ljava/lang/String;)J");
  AddCopiedStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFollowCopyOfOtherRegister) {
  CopyShape shape;
  shape.copied = 5;
  TestMethod m("sum", "(Ljava/lang/String;)J");
  AddCopiedStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// D8 puts the handlers after the code that follows the statement. With the
// handler first, the code that can hold the close never ends at it, so the
// close after the block doesn't count.
TEST_F(TryWithResourcesFilterTest, ShouldNotTakeCloseWithHandlerFirst) {
  TestMethod m("read", "(Ljava/lang/String;)I");
  lir::Label* start = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  lir::Label* after = m.NewLabel();
  m.AddLine(20);
  m.Add(dex::OP_GOTO, {m.Target(start)});
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({1, 2}),
         m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  m.Add(dex::OP_THROW, {m.Reg(1)});
  m.Place(start);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(21);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
  m.AddTryBlockEnd(block, handler);
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
  m.AddLine(22);
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.AddLine(21);
  m.Add(dex::OP_RETURN, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The handler of the close can reuse the register of the caught exception for
// the exception of the close, after the handler copied the caught one before
// the close. Each path keeps the values from where it starts.
TEST_F(TryWithResourcesFilterTest, ShouldFilterCopyOfCaughtException) {
  TestMethod m("copied", "(Ljava/lang/String;)I");
  lir::Label* after = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* suppress = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(20);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.AddLine(21);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
  m.AddTryBlockEnd(block, handler);
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(2)});
  m.AddLine(22);
  lir::Instruction* check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
  lir::Instruction* close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.AddLine(21);
  m.Add(dex::OP_RETURN, {m.Reg(2)});
  m.Place(handler);
  m.AddLine(20);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* copy = m.Add(dex::OP_MOVE_OBJECT, {m.Reg(4), m.Reg(1)});
  lir::Instruction* handler_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  lir::Instruction* handler_close =
      m.Add(dex::OP_INVOKE_VIRTUAL,
            {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, suppress);
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
  m.Place(suppress);
  lir::Instruction* suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* suppression = m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({4, 1}),
       m.MethodRef(kThrowable, "addSuppressed", "(Ljava/lang/Throwable;)V")});
  m.Place(rethrow);
  lir::Instruction* thrown = m.Add(dex::OP_THROW, {m.Reg(4)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m,
                Each({check, close, caught, copy, handler_check, handler_close,
                      to_throw, suppressed, suppression, thrown}));
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCloseOfOtherResource) {
  Shape shape;
  shape.block_end_resource = 5;
  shape.block_end_checked = 5;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCheckOfOtherRegisterAtEnd) {
  Shape shape;
  shape.block_end_checked = 5;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCloseOfOtherRegisterAtEnd) {
  Shape shape;
  shape.block_end_resource = 5;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCloseOfOtherNewResource) {
  Shape shape;
  shape.checks_null = false;
  shape.block_end_checks_null = false;
  shape.block_end_resource = 5;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCloseOfOtherClass) {
  Shape shape;
  shape.block_end_owner = "Ljava/io/Reader;";
  ExpectNotFiltered(shape);
}

// javac checks the resource for null in both places, or in neither.
TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCloseWithoutCheckForNull) {
  Shape shape;
  shape.block_end_checks_null = false;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCheckOfOtherRegister) {
  Shape shape;
  shape.checked = 5;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCallOfOtherClose) {
  Shape shape;
  shape.close_signature = "(Z)V";
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterOtherCallWithoutArguments) {
  Shape shape;
  shape.close_name = "flush";
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterOtherMethodWhenBlockEnds) {
  Shape shape;
  shape.block_end_name = "release";
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterOtherSignatureWhenBlockEnds) {
  Shape shape;
  shape.close_name = "release";
  shape.block_end_signature = "()Z";
  ExpectNotFiltered(shape);
}

// The register of the resource holds another value from the move on.
TEST_F(TryWithResourcesFilterTest, ShouldNotTakeCloseAfterMoveOverResource) {
  Shape shape;
  shape.moves_over_resource = true;
  ExpectNotFiltered(shape);
}

// Shapes of the static methods that D8 doesn't call instead of close().
TEST_F(TryWithResourcesFilterTest, ShouldNotFilterOtherStaticCalls) {
  for (const char* signature :
       {"(Ljava/lang/Object;)Z", "(I)V", "([Ljava/lang/Object;)V",
        "(Ljava/lang/Object;Ljava/lang/Object;)V"}) {
    SCOPED_TRACE(signature);
    Shape shape;
    shape.owner = kDispatcher;
    shape.block_end_owner = kDispatcher;
    shape.close_opcode = dex::OP_INVOKE_STATIC;
    shape.close_name = "m";
    shape.close_signature = signature;
    output_ = Output();
    ExpectNotFiltered(shape);
  }
}

TEST_F(TryWithResourcesFilterTest,
       ShouldNotFilterOtherStaticCallWhenBlockEnds) {
  Shape shape;
  shape.owner = kDispatcher;
  shape.block_end_owner = kForwarder;
  shape.close_opcode = dex::OP_INVOKE_STATIC;
  shape.close_name = "m";
  shape.close_signature = "(Ljava/lang/Object;)V";
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCloseOutsideTryBlock) {
  Shape shape;
  shape.close_in_try_block = false;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterHandlerWithoutSuppression) {
  Shape shape;
  shape.suppression_name = "initCause";
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest,
       ShouldNotFilterSuppressionOfCaughtException) {
  Shape shape;
  shape.suppression_arguments = {2, 1};
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterSuppressionInOtherException) {
  Shape shape;
  shape.suppression_arguments = {5, 2};
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterSuppressionOfOtherException) {
  Shape shape;
  shape.suppression_arguments = {1, 5};
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterAddSuppressedOfOtherClass) {
  Shape shape;
  shape.suppression_owner = kTestClassName;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterBackportOfOtherCall) {
  Shape shape;
  shape.backport = true;
  shape.suppression_arguments = {2, 1};
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterStaticCallOfOtherMethod) {
  Shape shape;
  shape.backport = true;
  shape.backport_signature = "(Ljava/lang/Object;Ljava/lang/Object;)V";
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterDirectCallOfClose) {
  Shape shape;
  shape.close_opcode = dex::OP_INVOKE_DIRECT;
  ExpectNotFiltered(shape);
}

// A null resource must go straight to the throw.
TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCheckThatSkipsToTheBlock) {
  Shape shape;
  shape.check_skips_to_throw = false;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest,
       ShouldNotFilterThrowOfNullResourceOnOtherLine) {
  Shape shape;
  shape.null_throw_line = 30;
  ExpectNotFiltered(shape);
}

// Without a check for null, only the path after the close leads to its throw.
TEST_F(TryWithResourcesFilterTest, ShouldNotFilterThrowOfOtherException) {
  Shape shape;
  shape.checks_null = false;
  shape.block_end_checks_null = false;
  shape.thrown = 2;
  ExpectNotFiltered(shape);
}

TEST_F(TryWithResourcesFilterTest, ShouldNotFilterRethrowOfOtherException) {
  Shape shape;
  shape.thrown_after_suppression = 2;
  ExpectNotFiltered(shape);
}

// The close of the handler must be in a try block of its own.
TEST_F(TryWithResourcesFilterTest, ShouldNotFilterCloseInTryBlockOfHandler) {
  TestMethod m("read", "(Ljava/lang/String;)I");
  lir::Label* after = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* rethrow = m.NewLabel();
  m.AddLine(20);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({3}), m.MethodRef(kTestClassName, "open", kOpen)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
  m.AddTryBlockEnd(block, handler);
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.Place(after);
  m.Add(dex::OP_RETURN, {m.Reg(1)});
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
  lir::TryBlockBegin* closing = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
  m.AddTryBlockEnd(closing, handler);
  m.Place(rethrow);
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// A move of a pair of registers that overwrites the caught exception in v3,
// with the second or the first register of the pair, ends its copy.
TEST_F(TryWithResourcesFilterTest, ShouldNotFilterThrowAfterWideMove) {
  for (dex::u4 pair : {2u, 3u}) {
    SCOPED_TRACE(pair);
    TestMethod m("read", "(Ljava/lang/String;)I");
    lir::Label* handler = m.NewLabel();
    lir::Label* suppress = m.NewLabel();
    lir::Label* rethrow = m.NewLabel();
    m.AddLine(20);
    m.Add(dex::OP_NEW_INSTANCE,
          {m.Reg(0), m.TypeRef("Ljava/io/FileInputStream;")});
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({0, 3}), m.MethodRef("Ljava/io/FileInputStream;", "<init>",
                                       "(Ljava/lang/String;)V")});
    lir::TryBlockBegin* block = m.AddTryBlockBegin();
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
    m.AddTryBlockEnd(block, handler);
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
    m.Add(dex::OP_RETURN, {m.Reg(1)});
    m.Place(handler);
    m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(3)});
    lir::TryBlockBegin* closing = m.AddTryBlockBegin();
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
    m.AddTryBlockEnd(closing, suppress);
    m.Add(dex::OP_GOTO, {m.Target(rethrow)});
    m.Place(suppress);
    m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(4)});
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({3, 4}), m.MethodRef(kThrowable, "addSuppressed",
                                       "(Ljava/lang/Throwable;)V")});
    m.Place(rethrow);
    m.Add(dex::OP_MOVE_WIDE, {m.code_ir().Alloc<lir::VRegPair>(pair),
                              m.code_ir().Alloc<lir::VRegPair>(6)});
    m.Add(dex::OP_THROW, {m.Reg(3)});

    Output output;
    filter_.Filter(context_, m.ir_method(), m.code_ir(), output);

    EXPECT_TRUE(output.ignored().empty());
  }
}

// Gotos that loop end the walk through the handler.
TEST_F(TryWithResourcesFilterTest, ShouldNotLoopOnGotosOfHandler) {
  TestMethod m("loops", "()V");
  lir::Label* handler = m.NewLabel();
  lir::Label* back = m.NewLabel();
  m.AddLine(20);
  lir::TryBlockBegin* block = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "user", "()V")});
  m.AddTryBlockEnd(block, handler);
  m.Add(dex::OP_RETURN_VOID);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Place(back);
  m.Add(dex::OP_GOTO, {m.Target(back)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// ---------------------------------------------------------------------------
// javac 7 to 10.

// The code of a try-with-resources statement of javac 7 to 10, as D8 lays it
// out, and what to change in it.
struct OldShape {
  // Whether the resource can be null: it comes from a call instead of new.
  bool checks_null = true;
  // Whether the statement closes the resource through $closeResource, and the
  // name and the signature of the method that it calls.
  bool close_resource = false;
  const char* close_resource_name = "$closeResource";
  const char* close_resource_signature =
      "(Ljava/lang/Throwable;Ljava/lang/AutoCloseable;)V";
  // Where the block loads the null that it passes to $closeResource, and the
  // value that it loads. With kOtherRegister, it loads it on the line of the
  // try statement and another null right before the call.
  enum class Null { kBeforeCall, kBeforeCheck, kOtherLine, kOtherRegister };
  Null null = Null::kBeforeCall;
  dex::u4 null_value = 0;
  // Whether the first handler throws its exception, rather than returning it,
  // the register that it throws, and whether its throw has a try block that
  // goes to the second handler.
  bool primary_throws = true;
  dex::u4 primary_thrown = 2;
  bool primary_in_try_block = true;
  // The exception that the second handler adds the exception of the close to,
  // or passes to $closeResource.
  dex::u4 primary_used = 2;
  // The line of the throw of the second handler.
  int throw_line = 22;
};

// The bytecodes of a statement of javac 7 to 10 that the filter ignores.
struct OldStatement {
  lir::Instruction* null_load = nullptr;
  lir::Instruction* check = nullptr;
  lir::Instruction* close = nullptr;
  lir::Instruction* primary = nullptr;
  lir::Instruction* primary_throw = nullptr;
  lir::Instruction* caught = nullptr;
  lir::Instruction* handler_check = nullptr;
  lir::Instruction* handler_close = nullptr;
  lir::Instruction* to_throw = nullptr;
  lir::Instruction* suppressed = nullptr;
  lir::Instruction* suppression = nullptr;
  lir::Instruction* rethrow = nullptr;
};

class TryWithResourcesFilterOldJavacTest : public TryWithResourcesFilterTest {
 protected:
  // Returns one range for each bytecode of `s` that isn't null, in code order.
  static std::vector<Range> Ignored(const TestMethod& m,
                                    const OldStatement& s) {
    std::vector<lir::Instruction*> instrs;
    for (lir::Instruction* instr :
         {s.null_load, s.check, s.close, s.primary, s.primary_throw, s.caught,
          s.handler_check, s.handler_close, s.to_throw, s.suppressed,
          s.suppression, s.rethrow}) {
      if (instr != nullptr) {
        instrs.push_back(instr);
      }
    }
    std::sort(instrs.begin(), instrs.end(),
              [&](lir::Instruction* a, lir::Instruction* b) {
                return m.IndexOf(a) < m.IndexOf(b);
              });
    std::vector<Range> ranges;
    for (lir::Instruction* instr : instrs) {
      ranges.push_back(Range{instr, instr});
    }
    return ranges;
  }

  // Appends the code of `int read(String path)` with `try (InputStream in =
  // open(path)) { return in.read(); }` as javac 8 compiles it and D8 lays it
  // out, changed as `shape` says. The resource is in v0, the result in v1, the
  // exception of the first handler in v2, the one of the second handler in v3
  // and the one of the close in v4:
  //     invoke-static {v5}, LFoo;->open(...)Ljava/io/InputStream;  (line 20)
  //     move-result-object v0
  //     invoke-virtual {v0}, Ljava/io/InputStream;->read()I  (line 21, in a
  //         try block that goes to :primary)
  //     move-result v1
  //     if-eqz v0, :after  (line 22, the end of the block)
  //     invoke-virtual {v0}, Ljava/io/InputStream;->close()V
  //   :after
  //     return v1  (line 21)
  //   :primary
  //     move-exception v2  (line 20)
  //     throw v2  (in a try block that goes to :handler)
  //   :handler
  //     move-exception v3  (line 22)
  //     if-eqz v0, :throw
  //     invoke-virtual {v0}, Ljava/io/InputStream;->close()V  (in a try
  //         block that goes to :suppress)
  //     goto :throw
  //   :suppress
  //     move-exception v4
  //     invoke-virtual {v2, v4},
  //         Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V
  //   :throw
  //     throw v3
  // With $closeResource, the block ends with const/4 v4, 0 and invoke-static
  // {v4, v0}, LFoo;->$closeResource(...)V, and the second handler calls it
  // with {v2, v0} and then throws v3.
  static OldStatement AddOldStatement(TestMethod& m, const OldShape& shape) {
    OldStatement s;
    lir::Label* after = m.NewLabel();
    lir::Label* primary = m.NewLabel();
    lir::Label* handler = m.NewLabel();
    lir::Label* suppress = m.NewLabel();
    lir::Label* rethrow = m.NewLabel();
    auto close = [&]() {
      return m.Add(dex::OP_INVOKE_VIRTUAL,
                   {m.Regs({0}), m.MethodRef(kInputStream, "close", "()V")});
    };
    auto close_resource = [&](dex::u4 passed) {
      return m.Add(dex::OP_INVOKE_STATIC,
                   {m.Regs({passed, 0}),
                    m.MethodRef(kTestClassName, shape.close_resource_name,
                                shape.close_resource_signature)});
    };
    auto load_null = [&]() {
      return m.Add(dex::OP_CONST_4, {m.Reg(4), m.Literal(shape.null_value)});
    };
    m.AddLine(20);
    if (shape.checks_null) {
      m.Add(dex::OP_INVOKE_STATIC,
            {m.Regs({5}), m.MethodRef(kTestClassName, "open", kOpen)});
      m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    } else {
      m.Add(dex::OP_NEW_INSTANCE,
            {m.Reg(0), m.TypeRef("Ljava/io/FileInputStream;")});
      m.Add(dex::OP_INVOKE_DIRECT,
            {m.Regs({0, 5}), m.MethodRef("Ljava/io/FileInputStream;", "<init>",
                                         "(Ljava/lang/String;)V")});
    }
    if (shape.close_resource &&
        (shape.null == OldShape::Null::kOtherLine ||
         shape.null == OldShape::Null::kOtherRegister)) {
      load_null();
    }
    lir::TryBlockBegin* block = m.AddTryBlockBegin();
    m.AddLine(21);
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({0}), m.MethodRef(kInputStream, "read", "()I")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
    m.AddTryBlockEnd(block, primary);
    m.AddLine(22);
    if (shape.close_resource && shape.null == OldShape::Null::kBeforeCheck) {
      s.null_load = load_null();
    } else if (shape.close_resource &&
               shape.null == OldShape::Null::kOtherLine) {
      // A null in another register.
      m.Add(dex::OP_CONST_4, {m.Reg(6), m.Literal(0)});
    }
    if (shape.checks_null) {
      s.check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(after)});
    }
    if (shape.close_resource) {
      if (shape.null == OldShape::Null::kBeforeCall) {
        s.null_load = load_null();
      } else if (shape.null == OldShape::Null::kOtherRegister) {
        m.Add(dex::OP_CONST_4, {m.Reg(6), m.Literal(0)});
      }
      s.close = close_resource(4);
    } else {
      s.close = close();
    }
    m.Place(after);
    m.AddLine(21);
    m.Add(dex::OP_RETURN, {m.Reg(1)});
    m.Place(primary);
    m.AddLine(20);
    s.primary = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
    lir::TryBlockBegin* rethrown =
        shape.primary_in_try_block ? m.AddTryBlockBegin() : nullptr;
    s.primary_throw =
        m.Add(shape.primary_throws ? dex::OP_THROW : dex::OP_RETURN_OBJECT,
              {m.Reg(shape.primary_thrown)});
    if (rethrown != nullptr) {
      m.AddTryBlockEnd(rethrown, handler);
    }
    m.Place(handler);
    m.AddLine(22);
    s.caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(3)});
    if (shape.checks_null) {
      s.handler_check = m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(rethrow)});
    }
    if (shape.close_resource) {
      s.handler_close = close_resource(shape.primary_used);
    } else {
      lir::TryBlockBegin* closing = m.AddTryBlockBegin();
      s.handler_close = close();
      m.AddTryBlockEnd(closing, suppress);
      s.to_throw = m.Add(dex::OP_GOTO, {m.Target(rethrow)});
      m.Place(suppress);
      s.suppressed = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(4)});
      s.suppression = m.Add(dex::OP_INVOKE_VIRTUAL,
                            {m.Regs({shape.primary_used, 4}),
                             m.MethodRef(kThrowable, "addSuppressed",
                                         "(Ljava/lang/Throwable;)V")});
    }
    m.Place(rethrow);
    m.AddLine(shape.throw_line);
    s.rethrow = m.Add(dex::OP_THROW, {m.Reg(3)});
    return s;
  }

  // Runs the filter on a method with the statement of `shape` and expects it
  // to ignore nothing.
  void ExpectOldNotFiltered(const OldShape& shape) {
    TestMethod m("read", "(Ljava/lang/String;)I");
    AddOldStatement(m, shape);

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

    ExpectIgnored(m, {});
  }
};

// try (InputStream in = open(path)) { return in.read(); } with javac 8: the
// first handler catches Throwable and throws it again to the handler of any
// exception, which closes the resource and adds the exception of the close to
// the first exception. D8 removes the checks of that exception for null.
TEST_F(TryWithResourcesFilterOldJavacTest, ShouldFilterStatementOfJavac8) {
  TestMethod m("read", "(Ljava/lang/String;)I");
  OldStatement s = AddOldStatement(m, OldShape());

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(m, s));
}

// try (InputStream in = new FileInputStream(path)) { ... }: javac 9 and 10
// leave out the checks of the resource for null, and D8 removes those of
// javac 7 and 8.
TEST_F(TryWithResourcesFilterOldJavacTest,
       ShouldFilterStatementOfJavac8WithResourceCreatedWithNew) {
  OldShape shape;
  shape.checks_null = false;
  TestMethod m("read", "(Ljava/lang/String;)I");
  OldStatement s = AddOldStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(m, s));
}

// javac 9 and 10 can close the resource through $closeResource, which takes
// the first exception, or null when the block ends.
TEST_F(TryWithResourcesFilterOldJavacTest, ShouldFilterCloseResource) {
  OldShape shape;
  shape.close_resource = true;
  TestMethod m("read", "(Ljava/lang/String;)I");
  OldStatement s = AddOldStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(m, s));
}

TEST_F(TryWithResourcesFilterOldJavacTest,
       ShouldFilterCloseResourceWithResourceCreatedWithNew) {
  OldShape shape;
  shape.close_resource = true;
  shape.checks_null = false;
  TestMethod m("read", "(Ljava/lang/String;)I");
  OldStatement s = AddOldStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(m, s));
}

// D8 can load the null before the check, as when statements that end
// together share it.
TEST_F(TryWithResourcesFilterOldJavacTest, ShouldFilterNullLoadedBeforeCheck) {
  OldShape shape;
  shape.close_resource = true;
  shape.null = OldShape::Null::kBeforeCheck;
  TestMethod m("read", "(Ljava/lang/String;)I");
  OldStatement s = AddOldStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(m, s));
}

// A null loaded on another line is kept, and so is a null in another register
// on the same line.
TEST_F(TryWithResourcesFilterOldJavacTest, ShouldKeepNullLoadedOnOtherLine) {
  OldShape shape;
  shape.close_resource = true;
  shape.null = OldShape::Null::kOtherLine;
  TestMethod m("read", "(Ljava/lang/String;)I");
  OldStatement s = AddOldStatement(m, shape);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(m, s));
}

// The block passes $closeResource the null of the primary exception, which D8
// loads for it. A load of another value is kept.
TEST_F(TryWithResourcesFilterOldJavacTest, ShouldKeepLoadOfOtherValue) {
  OldShape shape;
  shape.close_resource = true;
  shape.checks_null = false;
  shape.null_value = 1;
  TestMethod m("read", "(Ljava/lang/String;)I");
  OldStatement s = AddOldStatement(m, shape);
  s.null_load = nullptr;

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Ignored(m, s));
}

TEST_F(TryWithResourcesFilterOldJavacTest, ShouldNotFilterOtherShapes) {
  {
    SCOPED_TRACE("first handler returns its exception");
    OldShape shape;
    shape.primary_throws = false;
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("first handler throws another value");
    output_ = Output();
    OldShape shape;
    shape.primary_thrown = 1;
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("first handler throws outside a try block");
    output_ = Output();
    OldShape shape;
    shape.primary_in_try_block = false;
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("suppressed by the exception of the second handler");
    output_ = Output();
    OldShape shape;
    shape.primary_used = 3;
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("suppressed by another value");
    output_ = Output();
    OldShape shape;
    shape.primary_used = 1;
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("throw of the second handler on another line");
    output_ = Output();
    OldShape shape;
    shape.throw_line = 30;
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("method with another name than $closeResource");
    output_ = Output();
    OldShape shape;
    shape.close_resource = true;
    shape.close_resource_name = "closeResource";
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("$closeResource with another signature");
    output_ = Output();
    OldShape shape;
    shape.close_resource = true;
    shape.close_resource_signature =
        "(Ljava/lang/Throwable;Ljava/io/Closeable;)V";
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("$closeResource passed the exception of the second handler");
    output_ = Output();
    OldShape shape;
    shape.close_resource = true;
    shape.primary_used = 3;
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("other value loaded between the check and $closeResource");
    output_ = Output();
    OldShape shape;
    shape.close_resource = true;
    shape.null_value = 1;
    ExpectOldNotFiltered(shape);
  }
  {
    SCOPED_TRACE("other register loaded between the check and $closeResource");
    output_ = Output();
    OldShape shape;
    shape.close_resource = true;
    shape.null = OldShape::Null::kOtherRegister;
    ExpectOldNotFiltered(shape);
  }
}

}  // namespace
}  // namespace coverage
