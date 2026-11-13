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

#include "tools/base/android-test/coverage/agent/native/synchronized_filter.h"

#include <gtest/gtest.h>

#include <initializer_list>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kObject[] = "Ljava/lang/Object;";

// Unit tests for SynchronizedFilter.
class SynchronizedFilterTest : public FilterTestBase {
 protected:
  // Returns one range for each of `instrs`, in this order.
  static std::vector<Range> Each(
      std::initializer_list<lir::Instruction*> instrs) {
    std::vector<Range> ranges;
    for (lir::Instruction* instr : instrs) {
      ranges.push_back(Range{instr, instr});
    }
    return ranges;
  }

  // Appends a synchronized block on the lock in v0 up to its normal exit: the
  // monitor enter, then a try block whose code calls user() on `this` in v2 and
  // exits the monitor. Returns the try block.
  static lir::TryBlockBegin* AddSynchronizedCode(TestMethod& m) {
    m.Add(dex::OP_MONITOR_ENTER, {m.Reg(0)});
    lir::TryBlockBegin* begin = m.AddTryBlockBegin();
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({2}), m.MethodRef(kTestClassName, "user", "()V")});
    m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
    return begin;
  }

  SynchronizedFilter filter_;
};

// synchronized (lock) {
//   user();
// }
// The handler catches any exception of the block, exits the monitor and
// throws the exception again. The try block covers the handler up to its exit.
TEST_F(SynchronizedFilterTest, ShouldFilterHandlerOfSynchronizedBlock) {
  TestMethod m("block", "()V");
  m.AddLine(10);
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* handler = m.NewLabel();
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* exit = m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  m.AddTryBlockEnd(begin, handler);
  lir::Instruction* rethrow = m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({caught, exit, rethrow}));
}

// D8 can put the exit and the throw of the handler before the handler, inside
// the try block, and jump there, as in this componentManager() that Hilt
// generates. The exit on the normal path stays.
TEST_F(SynchronizedFilterTest, ShouldFilterHandlerThatJumpsToItsExit) {
  constexpr char kManager[] =
      "Ldagger/hilt/android/internal/managers/ActivityComponentManager;";
  TestMethod m("componentManager",
               "()Ldagger/hilt/android/internal/managers/"
               "ActivityComponentManager;");
  lir::Label* stored = m.NewLabel();
  lir::Label* tail = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* end = m.NewLabel();
  m.AddLine(75);
  m.AddLine(75);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(2),
         m.FieldRef(kTestClassName, "componentManager", kManager)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(end)});
  m.AddLine(76);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(2),
         m.FieldRef(kTestClassName, "componentManagerLock", kObject)});
  m.Add(dex::OP_MONITOR_ENTER, {m.Reg(0)});
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  m.AddLine(77);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(1), m.Reg(2),
         m.FieldRef(kTestClassName, "componentManager", kManager)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(1), m.Target(stored)});
  m.AddLine(78);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kTestClassName, "createComponentManager",
                                  "()Ldagger/hilt/android/internal/managers/"
                                  "ActivityComponentManager;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(1)});
  m.Add(dex::OP_IPUT_OBJECT,
        {m.Reg(1), m.Reg(2),
         m.FieldRef(kTestClassName, "componentManager", kManager)});
  m.Place(stored);
  m.AddLine(80);
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(tail);
  lir::Instruction* exit = m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  m.AddTryBlockEnd(begin, handler);
  lir::Instruction* rethrow = m.Add(dex::OP_THROW, {m.Reg(1)});
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(tail)});
  m.Place(end);
  m.AddLine(82);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(2),
         m.FieldRef(kTestClassName, "componentManager", kManager)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({caught, jump, exit, rethrow}));
}

// D8 can move other registers in the handler before the exit, and jump there
// with goto/16, as in CompositionImpl.invalidateChecked() of Compose.
TEST_F(SynchronizedFilterTest, ShouldFilterHandlerWithMovesOfD8) {
  TestMethod m("invalidateChecked", "()V");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* tail = m.NewLabel();
  m.Place(tail);
  lir::Instruction* exit = m.Add(dex::OP_MONITOR_EXIT, {m.Reg(5)});
  lir::Instruction* rethrow = m.Add(dex::OP_THROW, {m.Reg(1)});
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* move = m.Add(dex::OP_MOVE_FROM16, {m.Reg(19), m.Reg(6)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO_16, {m.Target(tail)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({caught, move, jump, exit, rethrow}));
}

// D8 can also jump from the exit to the throw, as in
// CancellationSignal.cancel() of AndroidX, where the throw is the end of
// another handler too.
TEST_F(SynchronizedFilterTest, ShouldFilterHandlerThatJumpsToItsThrow) {
  TestMethod m("cancel", "()V");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* exit_label = m.NewLabel();
  lir::Label* throw_label = m.NewLabel();
  m.Place(exit_label);
  lir::Instruction* exit = m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  lir::Instruction* to_throw = m.Add(dex::OP_GOTO, {m.Target(throw_label)});
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  lir::Instruction* to_exit = m.Add(dex::OP_GOTO, {m.Target(exit_label)});
  m.Place(throw_label);
  lir::Instruction* rethrow = m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({caught, to_exit, exit, to_throw, rethrow}));
}

// The exception can be thrown from a copy.
TEST_F(SynchronizedFilterTest, ShouldFilterHandlerThatThrowsCopyOfException) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* handler = m.NewLabel();
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.AddTryBlockEnd(begin, handler);
  lir::Instruction* copy = m.Add(dex::OP_MOVE_OBJECT, {m.Reg(3), m.Reg(1)});
  lir::Instruction* exit = m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  lir::Instruction* rethrow = m.Add(dex::OP_THROW, {m.Reg(3)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({caught, copy, exit, rethrow}));
}

// synchronized void increment() { user(); }
// D8 exits the monitor of a synchronized method itself, with the same handler,
// which two try blocks share here. The monitor enter and the exit on the
// normal path stay.
TEST_F(SynchronizedFilterTest, ShouldFilterHandlerOfSynchronizedMethod) {
  TestMethod m("increment", "()V");
  m.ir_method()->access_flags = dex::kAccPublic | dex::kAccDeclaredSynchronized;
  lir::Label* tail = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  m.Add(dex::OP_MONITOR_ENTER, {m.Reg(2)});
  lir::TryBlockBegin* body = m.AddTryBlockBegin();
  m.AddLine(30);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kTestClassName, "user", "()V")});
  m.AddLine(31);
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(2)});
  m.AddTryBlockEnd(body, handler);
  m.Add(dex::OP_RETURN_VOID);
  m.Place(tail);
  lir::TryBlockBegin* exit_block = m.AddTryBlockBegin();
  m.AddLine(29);
  lir::Instruction* exit = m.Add(dex::OP_MONITOR_EXIT, {m.Reg(2)});
  m.AddTryBlockEnd(exit_block, handler);
  lir::Instruction* rethrow = m.Add(dex::OP_THROW, {m.Reg(0)});
  m.Place(handler);
  lir::Instruction* caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  lir::Instruction* jump = m.Add(dex::OP_GOTO, {m.Target(tail)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({caught, jump, exit, rethrow}));
}

// synchronized (outer) {
//   synchronized (inner) {
//     user();
//   }
// }
// Each block has its handler. The one of the inner block is in the try block of
// the outer one.
TEST_F(SynchronizedFilterTest, ShouldFilterHandlersOfNestedBlocks) {
  TestMethod m("nested", "()V");
  lir::Label* inner_handler = m.NewLabel();
  lir::Label* outer_handler = m.NewLabel();
  lir::Label* after_inner = m.NewLabel();
  m.Add(dex::OP_MONITOR_ENTER, {m.Reg(0)});
  lir::TryBlockBegin* outer = m.AddTryBlockBegin();
  m.Add(dex::OP_MONITOR_ENTER, {m.Reg(1)});
  lir::TryBlockBegin* inner = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({4}), m.MethodRef(kTestClassName, "user", "()V")});
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(1)});
  m.AddTryBlockEnd(inner, inner_handler);
  m.Add(dex::OP_GOTO, {m.Target(after_inner)});
  m.Place(inner_handler);
  lir::Instruction* inner_caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(2)});
  lir::Instruction* inner_exit = m.Add(dex::OP_MONITOR_EXIT, {m.Reg(1)});
  lir::Instruction* inner_rethrow = m.Add(dex::OP_THROW, {m.Reg(2)});
  m.Place(after_inner);
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  m.AddTryBlockEnd(outer, outer_handler);
  m.Add(dex::OP_RETURN_VOID);
  m.Place(outer_handler);
  lir::Instruction* outer_caught = m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(3)});
  lir::Instruction* outer_exit = m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  lir::Instruction* outer_rethrow = m.Add(dex::OP_THROW, {m.Reg(3)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, Each({inner_caught, inner_exit, inner_rethrow, outer_caught,
                         outer_exit, outer_rethrow}));
}

// try { user(); } finally { close(); }
// The handler of a finally block runs its code, not a monitor exit.
TEST_F(SynchronizedFilterTest, ShouldNotFilterHandlerOfFinallyBlock) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kTestClassName, "user", "()V")});
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kTestClassName, "close", "()V")});
  m.Add(dex::OP_RETURN_VOID);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kTestClassName, "close", "()V")});
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// try { user(); } finally { synchronized (lock) { lock.notifyAll(); } }
// A handler that runs its own synchronized block before the exit isn't the one
// of a block.
TEST_F(SynchronizedFilterTest, ShouldNotFilterHandlerWithCodeBeforeExit) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kTestClassName, "user", "()V")});
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Add(dex::OP_RETURN_VOID);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_MONITOR_ENTER, {m.Reg(0)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kObject, "notifyAll", "()V")});
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Throwable failure;
// while (true) {
//   synchronized (lock) {
//     try {
//       user();
//     } catch (Throwable t) {
//       failure = t;
//       break;
//     }
//   }
// }
// throw failure;
// The catch of Throwable leaves the block with the exception, so it exits the
// monitor, and the code after the loop throws the exception, on another line.
TEST_F(SynchronizedFilterTest, ShouldNotFilterCatchThatThrowsAfterBlock) {
  TestMethod m("breakOut", "()V");
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  m.AddLine(27);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({3}), m.MethodRef(kTestClassName, "user", "()V")});
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.AddLine(32);
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(2)});
  m.Add(dex::OP_RETURN_VOID);
  m.Place(handler);
  m.AddLine(28);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.AddLine(29);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(0)});
  m.AddLine(30);
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(2)});
  m.AddLine(34);
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// try { user(); } catch (Throwable t) { throw t; }
// A catch of Throwable is a catch of any exception in dex too.
TEST_F(SynchronizedFilterTest, ShouldNotFilterRethrowWithoutExit) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({2}), m.MethodRef(kTestClassName, "user", "()V")});
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Add(dex::OP_RETURN_VOID);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// synchronized (lock) {
//   try { user(); } catch (Throwable t) { return t; }
// }
// A catch of Throwable that leaves the block also exits the monitor, but then
// returns the exception.
TEST_F(SynchronizedFilterTest, ShouldNotFilterHandlerThatReturnsException) {
  TestMethod m("block", "()Ljava/lang/Throwable;");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(1)});
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The handler must catch any exception.
TEST_F(SynchronizedFilterTest, ShouldNotFilterHandlerOfException) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler, "Ljava/lang/Exception;");
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The handler must throw the exception that it caught.
TEST_F(SynchronizedFilterTest, ShouldNotFilterThrowOfOtherRegister) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  m.Add(dex::OP_THROW, {m.Reg(3)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Nor after overwriting it.
TEST_F(SynchronizedFilterTest, ShouldNotFilterThrowOfOverwrittenException) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(1), m.Reg(3)});
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(0)});
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// A wide move into the register after the exception overwrites it too.
TEST_F(SynchronizedFilterTest,
       ShouldNotFilterThrowOfExceptionOverwrittenByWideMove) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_MOVE_WIDE, {m.code_ir().Alloc<lir::VRegPair>(0),
                            m.code_ir().Alloc<lir::VRegPair>(4)});
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(5)});
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The handler must exit the monitor of another register than the exception.
TEST_F(SynchronizedFilterTest, ShouldNotFilterExitOfException) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* handler = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_MONITOR_EXIT, {m.Reg(1)});
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// A handler that loops ends the search.
TEST_F(SynchronizedFilterTest, ShouldNotFilterHandlerThatLoops) {
  TestMethod m("block", "()V");
  lir::TryBlockBegin* begin = AddSynchronizedCode(m);
  m.Add(dex::OP_RETURN_VOID);
  lir::Label* handler = m.NewLabel();
  lir::Label* loop = m.NewLabel();
  m.AddTryBlockEnd(begin, handler);
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Place(loop);
  m.Add(dex::OP_GOTO, {m.Target(loop)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
