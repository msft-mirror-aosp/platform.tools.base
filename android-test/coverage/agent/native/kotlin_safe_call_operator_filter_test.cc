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

#include "tools/base/android-test/coverage/agent/native/kotlin_safe_call_operator_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kNode[] = "Lcom/example/Node;";
constexpr char kString[] = "Ljava/lang/String;";
constexpr char kGetNext[] = "()Lcom/example/Node;";
constexpr char kGetValue[] = "()Ljava/lang/String;";
// The signature of fun f(n: Node?): String.
constexpr char kSignature[] = "(Lcom/example/Node;)Ljava/lang/String;";

// Unit tests for KotlinSafeCallOperatorFilter, on the code that the Kotlin
// compiler and D8 generate for safe calls on
//   class Node(val next: Node?, val value: String?)
class KotlinSafeCallOperatorFilterTest : public FilterTestBase {
 protected:
  // Appends a call to getNext() on v<from>, and moves its result to v<to>.
  static void AddGetNext(TestMethod& m, dex::u4 from, dex::u4 to) {
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({from}), m.MethodRef(kNode, "getNext", kGetNext)});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(to)});
  }

  // Appends a call to getValue() on v<from>, and moves its result to v<to>.
  static void AddGetValue(TestMethod& m, dex::u4 from, dex::u4 to) {
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({from}), m.MethodRef(kNode, "getValue", kGetValue)});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(to)});
  }

  // Appends the checks of node?.next?.value, with the node in v<node>, which
  // jump to `null_code`. Leaves the value in v<value>.
  static void AddChain(TestMethod& m, dex::u4 node, dex::u4 value,
                       lir::Label* null_code) {
    m.Add(dex::OP_IF_EQZ, {m.Reg(node), m.Target(null_code)});
    AddGetNext(m, node, value);
    m.Add(dex::OP_IF_EQZ, {m.Reg(value), m.Target(null_code)});
    AddGetValue(m, value, value);
  }

  // The check of an elvis operator (?:), and the goto that follows it.
  struct Elvis {
    lir::Instruction* check;
    lir::Instruction* jump;
  };

  // Appends the check of an elvis operator on v<value>, which jumps to
  // `not_null`, followed by the goto to `null_code` that D8 can put after it.
  static Elvis AddElvis(TestMethod& m, dex::u4 value, lir::Label* not_null,
                        lir::Label* null_code) {
    lir::Instruction* check =
        m.Add(dex::OP_IF_NEZ, {m.Reg(value), m.Target(not_null)});
    return {check, m.Add(dex::OP_GOTO, {m.Target(null_code)})};
  }

  // Appends the code of `return v` at `not_null`, with v in v0, and of
  // `return "none"` at `null_code`.
  static void AddReturns(TestMethod& m, lir::Label* not_null,
                         lir::Label* null_code) {
    m.Place(not_null);
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
    m.Place(null_code);
    m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  }

  // Appends the code of
  //   var x = start
  //   var count = 0
  //   while (...) {      // line 4
  //     val next = ...   // line 5
  //     count++
  //     x = next
  //   }
  //   return count
  // with start in v3, in which the check of x, on line `check_line`, and the
  // elvis operator on next jump to the return at `end`. The elvis operator
  // jumps to `not_null` otherwise. Returns the elvis operator.
  static Elvis AddLoop(TestMethod& m, int check_line, lir::Label* not_null,
                       lir::Label* end) {
    lir::Label* loop = m.NewLabel();
    m.AddLine(2);
    m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(3)});
    m.AddLine(3);
    m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
    m.Place(loop);
    m.AddLine(check_line);
    m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(end)});
    m.AddLine(5);
    AddGetNext(m, /*from=*/0, /*to=*/2);
    Elvis elvis = AddElvis(m, /*value=*/2, not_null, end);
    m.Place(not_null);
    m.AddLine(6);
    m.Add(dex::OP_ADD_INT_LIT8, {m.Reg(1), m.Reg(1), m.Literal(1)});
    m.AddLine(7);
    m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(2)});
    m.Add(dex::OP_GOTO, {m.Target(loop)});
    m.Place(end);
    m.AddLine(9);
    m.Add(dex::OP_RETURN, {m.Reg(1)});
    return elvis;
  }

  KotlinSafeCallOperatorFilter filter_;
};

// fun elvisReturn(n: Node?): String {
//   val v = n?.next?.value ?: return "none"
//   return v
// }
// D8 follows the check of the elvis operator with a goto to the code of
// `return "none"`, which only runs when one of the values is null.
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldReplaceNullBranchOfElvis) {
  TestMethod m("elvisReturn", kSignature);
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.AddLine(2);
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  Elvis elvis = AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.AddLine(3);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(null_code);
  m.AddLine(2);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{elvis.check, {null_code, not_null}}});
  ExpectIgnored(m, {Range{elvis.jump, elvis.jump}});
}

// val v = n?.value ?: return "none"
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldReplaceNullBranchOfSingleCall) {
  TestMethod m("singleElvisReturn", kSignature);
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(null_code)});
  AddGetValue(m, /*from=*/1, /*to=*/0);
  Elvis elvis = AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{elvis.check, {null_code, not_null}}});
  ExpectIgnored(m, {Range{elvis.jump, elvis.jump}});
}

// fun twoElvisReturns(a: Node?, b: Node?): String {
//   val x = a?.next?.value ?: return "none"
//   val y = b?.next?.value ?: return "none"
//   return x + y
// }
// D8 loads "none" first, and gives each chain its own return. The value of the
// first elvis operator is used after the second chain.
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldReplaceNullBranchOfEachChain) {
  TestMethod m("twoElvisReturns",
               "(Lcom/example/Node;Lcom/example/Node;)Ljava/lang/String;");
  lir::Label* first_not_null = m.NewLabel();
  lir::Label* first_null_code = m.NewLabel();
  lir::Label* second_not_null = m.NewLabel();
  lir::Label* second_null_code = m.NewLabel();
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
  AddChain(m, /*node=*/3, /*value=*/1, first_null_code);
  Elvis first_elvis = AddElvis(m, /*value=*/1, first_not_null, first_null_code);
  m.Place(first_not_null);
  AddChain(m, /*node=*/4, /*value=*/2, second_null_code);
  Elvis second_elvis =
      AddElvis(m, /*value=*/2, second_not_null, second_null_code);
  m.Place(second_not_null);
  m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 2}), m.MethodRef(kString, "concat",
                                   "(Ljava/lang/String;)Ljava/lang/String;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(second_null_code);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(first_null_code);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(
      m,
      {Replacement{first_elvis.check, {first_null_code, first_not_null}},
       Replacement{second_elvis.check, {second_null_code, second_not_null}}});
  ExpectIgnored(m, {Range{first_elvis.jump, first_elvis.jump},
                    Range{second_elvis.jump, second_elvis.jump}});
}

// try {
//   val v = n?.next?.value ?: return "none"
//   return v
// } catch (e: IllegalStateException) {
//   return "error"
// }
// The try block starts after the first check, and ends inside the code of
// `return "none"`.
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldReplaceNullBranchInTryBlock) {
  TestMethod m("elvisReturnTry", kSignature);
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(null_code)});
  lir::TryBlockBegin* try_begin = m.AddTryBlockBegin();
  AddGetNext(m, /*from=*/1, /*to=*/0);
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(null_code)});
  AddGetValue(m, /*from=*/0, /*to=*/0);
  Elvis elvis = AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
  m.AddTryBlockEnd(try_begin, handler, "Ljava/lang/IllegalStateException;");
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef("error")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{elvis.check, {null_code, not_null}}});
  ExpectIgnored(m, {Range{elvis.jump, elvis.jump}});
}

// val v = n?.next?.value ?: return other
// The agent probes the code of `return other` at the return, after the move.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldReplaceNullBranchWhenNullCodeStartsWithMove) {
  TestMethod m("elvisReturnOther",
               "(Lcom/example/Node;Ljava/lang/String;)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  Elvis elvis = AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(2)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{elvis.check, {null_code, not_null}}});
  ExpectIgnored(m, {Range{elvis.jump, elvis.jump}});
}

// val v = x?.let { (it as Node).next }?.value ?: return "none"
// The cast uses the value of the first check.
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldReplaceWhenCheckedValueIsCast) {
  TestMethod m("castElvisReturn", "(Ljava/lang/Object;)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(null_code)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(1), m.TypeRef(kNode)});
  AddGetNext(m, /*from=*/1, /*to=*/0);
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(null_code)});
  AddGetValue(m, /*from=*/0, /*to=*/0);
  Elvis elvis = AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{elvis.check, {null_code, not_null}}});
  ExpectIgnored(m, {Range{elvis.jump, elvis.jump}});
}

// val v = n?.next?.value ?: return "none"
// with a field read of `next` and a range call of getValue().
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldReplaceWhenValuesAreReadByFieldsAndRangeCalls) {
  TestMethod m("fieldElvisReturn", kSignature);
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(null_code)});
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kNode, "next", kNode)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(null_code)});
  m.Add(dex::OP_INVOKE_VIRTUAL_RANGE,
        {m.RegRange(0, 1), m.MethodRef(kNode, "getValue", kGetValue)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  Elvis elvis = AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{elvis.check, {null_code, not_null}}});
  ExpectIgnored(m, {Range{elvis.jump, elvis.jump}});
}

// fun chainElvis(n: Node?): String = n?.next?.value ?: "none"
// The check of the elvis operator falls through to the code of "none", which
// already is its null branch.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceNullBranchOfElvisThatFallsThrough) {
  TestMethod m("chainElvis", kSignature);
  lir::Label* null_code = m.NewLabel();
  lir::Label* end = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(end)});
  m.Place(null_code);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
  m.Place(end);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun chain(n: Node?): String? = n?.next?.value
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldNotReplaceChainWithoutElvis) {
  TestMethod m("chain", kSignature);
  lir::Label* null_code = m.NewLabel();
  lir::Label* end = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(null_code);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Place(end);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun elvisThrow(n: Node?): String =
//     n?.next?.value ?: throw IllegalStateException()
// D8 checks the value with a third jump to the code of the throw.
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldNotReplaceChecksOfElvisThrow) {
  constexpr char kError[] = "Ljava/lang/IllegalStateException;";
  TestMethod m("elvisThrow", kSignature);
  lir::Label* null_code = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(null_code)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kError)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0}), m.MethodRef(kError, "<init>", "()V")});
  m.Add(dex::OP_THROW, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun elvisDefault(n: Node?): String {
//   val v = n?.next?.value ?: ""
//   return v
// }
// D8 loads "" first, so the code that the checks jump to also runs when no
// value is null.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenOtherCodeFallsThroughToNullCode) {
  TestMethod m("elvisDefault", kSignature);
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_CONST_STRING, {m.Reg(2), m.StringRef("")});
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(2), m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(2)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun elvisOrFlag(n: Node?, flag: Boolean): String {
//   if (flag) return "none"
//   log(flag)
//   val v = n?.next?.value ?: return "none"
//   return v
// }
// with the code of the two returns of "none" shared.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenOtherCodeJumpsToNullCode) {
  TestMethod m("elvisOrFlag", "(Lcom/example/Node;Z)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_NEZ, {m.Reg(2), m.Target(null_code)});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({2}), m.MethodRef(kTestClassName, "log", "(Z)V")});
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun elvisContinue(list: List<Node?>): Int {
//   var count = 0
//   for (n in list) {
//     val v = n?.next?.value ?: continue
//     count += v.length
//   }
//   return count
// }
// The checks jump to the start of the loop.
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldNotReplaceWhenNullCodeIsLoop) {
  constexpr char kIterator[] = "Ljava/util/Iterator;";
  TestMethod m("elvisContinue", "(Ljava/util/List;)I");
  lir::Label* loop = m.NewLabel();
  lir::Label* not_null = m.NewLabel();
  lir::Label* end = m.NewLabel();
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_INVOKE_INTERFACE,
        {m.Regs({5}), m.MethodRef("Ljava/util/List;", "iterator",
                                  "()Ljava/util/Iterator;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(1)});
  m.Place(loop);
  m.Add(dex::OP_INVOKE_INTERFACE,
        {m.Regs({1}), m.MethodRef(kIterator, "hasNext", "()Z")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(2)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(2), m.Target(end)});
  m.Add(dex::OP_INVOKE_INTERFACE,
        {m.Regs({1}), m.MethodRef(kIterator, "next", "()Ljava/lang/Object;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(2), m.TypeRef(kNode)});
  AddChain(m, /*node=*/2, /*value=*/3, loop);
  AddElvis(m, /*value=*/3, not_null, loop);
  m.Place(not_null);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({3}), m.MethodRef(kString, "length", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(4)});
  m.Add(dex::OP_ADD_INT_2ADDR, {m.Reg(0), m.Reg(4)});
  m.Add(dex::OP_GOTO, {m.Target(loop)});
  m.Place(end);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// A case of a switch also returns "none".
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenPackedSwitchJumpsToNullCode) {
  TestMethod m("elvisOrPackedSwitch",
               "(Lcom/example/Node;I)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  lir::Label* table = m.NewLabel();
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(2), m.Target(table)});
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);
  m.Place(table);
  m.AddPackedSwitchPayload(/*first_key=*/0, {null_code});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenSparseSwitchJumpsToNullCode) {
  TestMethod m("elvisOrSparseSwitch",
               "(Lcom/example/Node;I)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  lir::Label* table = m.NewLabel();
  m.Add(dex::OP_SPARSE_SWITCH, {m.Reg(2), m.Target(table)});
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);
  m.Place(table);
  m.AddSparseSwitchPayload({{/*key=*/100, null_code}});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// The code of `return "none"` also handles the exceptions of the calls.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenNullCodeHandlesExceptions) {
  TestMethod m("elvisOrCatch", kSignature);
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  lir::TryBlockBegin* try_begin = m.AddTryBlockBegin();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.AddTryBlockEnd(try_begin, null_code, "Ljava/lang/IllegalStateException;");
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenNullCodeHandlesAllExceptions) {
  TestMethod m("elvisOrCatchAll", kSignature);
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  lir::TryBlockBegin* try_begin = m.AddTryBlockBegin();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.AddTryBlockEnd(try_begin, null_code);
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// val v = n?.next?.value ?: other as String
// return v
// The agent doesn't probe code with only nop, move and check-cast
// instructions, so it can't see whether that code ran.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenNullCodeIsNotProbed) {
  TestMethod m("elvisOther",
               "(Lcom/example/Node;Ljava/lang/Object;)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  lir::Label* end = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(null_code);
  m.Add(dex::OP_NOP);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(2)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef(kString)});
  m.Place(end);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// The code of `?: return other` starts with a move, and a try block starts
// before its first instruction that the agent probes.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenNullCodeEndsBeforeTryBlock) {
  TestMethod m("elvisReturnLogged",
               "(Lcom/example/Node;Ljava/lang/String;)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_MOVE_OBJECT, {m.Reg(0), m.Reg(2)});
  lir::TryBlockBegin* try_begin = m.AddTryBlockBegin();
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0}),
         m.MethodRef(kTestClassName, "log", "(Ljava/lang/String;)V")});
  m.AddTryBlockEnd(try_begin, handler, "Ljava/lang/Exception;");
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(1)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// D8 can put the code that the checks jump to before them. In constructors,
// the agent doesn't probe the code before the call to the super constructor,
// so the filter only replaces the null branch with code after the goto.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenNullCodeComesFirst) {
  TestMethod m("elvisReturnFirst", kSignature);
  lir::Label* start = m.NewLabel();
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_GOTO, {m.Target(start)});
  m.Place(null_code);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(start);
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// Other code jumps to the goto after the check of the elvis operator, so the
// goto also runs when the value isn't null.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenOtherCodeJumpsToGoto) {
  TestMethod m("elvisOrFlagShared", "(Lcom/example/Node;Z)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  lir::Label* shared_goto = m.NewLabel();
  m.Add(dex::OP_IF_NEZ, {m.Reg(2), m.Target(shared_goto)});
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(not_null)});
  m.Place(shared_goto);
  m.Add(dex::OP_GOTO, {m.Target(null_code)});
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// The goto follows a check that jumps elsewhere when the value is null, so
// the code that the chain jumps to also runs when no value is null.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenGotoFollowsOtherCheck) {
  TestMethod m("nullOrNone", kSignature);
  lir::Label* is_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(is_null)});
  m.Add(dex::OP_GOTO, {m.Target(null_code)});
  m.Place(is_null);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// The check of the elvis operator falls through to another jump to the null
// code, which only jumps if a flag is set, so that code doesn't run every time
// the value is null.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenElvisFallsThroughToOtherJump) {
  TestMethod m("elvisThenFlag", "(Lcom/example/Node;Z)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  m.Add(dex::OP_IF_NEZ, {m.Reg(0), m.Target(not_null)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(2), m.Target(null_code)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("default")});
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// A check before the chain jumps to the code of the chain through a goto. It
// isn't the check of an elvis operator on the value of the chain, although the
// code it jumps to uses the value that it checks.
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldNotReplaceCheckBeforeChain) {
  TestMethod m("checkBeforeChain",
               "(Lcom/example/Node;Ljava/lang/String;)Ljava/lang/String;");
  lir::Label* start = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_NEZ, {m.Reg(2), m.Target(start)});
  m.Add(dex::OP_GOTO, {m.Target(null_code)});
  m.Place(start);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({2}),
         m.MethodRef(kTestClassName, "log", "(Ljava/lang/String;)V")});
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun f(a: Node?, b: Node): String {
//   if (a == null) return "none"
//   val v = b.value ?: return "none"
//   return v
// }
// with the code of the two returns of "none" shared. The code after the first
// jump doesn't use the value that it checks.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenCheckedValueIsNotUsed) {
  TestMethod m("notUsed",
               "(Lcom/example/Node;Lcom/example/Node;)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(null_code)});
  AddGetValue(m, /*from=*/2, /*to=*/0);
  AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// The code after the first jump overwrites the value that it checks before
// using the register.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenCheckedValueIsOverwritten) {
  TestMethod m("overwritten", kSignature);
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(null_code)});
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(1), m.Reg(2), m.FieldRef(kTestClassName, "node", kNode)});
  AddGetValue(m, /*from=*/1, /*to=*/0);
  AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun f(a: Node?, b: Node?): String {
//   if (a == null || b == null) return "none"
//   val v = a.combine(b) ?: return "none"
//   return v
// }
// with the code of the returns of "none" shared. The first jump is followed by
// the second one, before the code that uses both values.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenCheckIsFollowedByOtherCheck) {
  TestMethod m("twoChecks",
               "(Lcom/example/Node;Lcom/example/Node;)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(null_code)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(2), m.Target(null_code)});
  m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({1, 2}), m.MethodRef(kNode, "combine",
                                   "(Lcom/example/Node;)Ljava/lang/String;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// Other code joins the code after the first jump before it uses the register
// that the jump checks, so the register may hold another value.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenOtherCodeJoinsBeforeUse) {
  TestMethod m("joined", "(Lcom/example/Node;Z)Ljava/lang/String;");
  lir::Label* join = m.NewLabel();
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_NEZ, {m.Reg(2), m.Target(join)});
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(null_code)});
  m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(1)});
  m.Place(join);
  AddGetValue(m, /*from=*/1, /*to=*/0);
  AddElvis(m, /*value=*/0, not_null, null_code);
  AddReturns(m, not_null, null_code);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun whileTrue(start: Node?): Int {
//   var x = start
//   var count = 0
//   while (true) {
//     val next = x?.next ?: break
//     count++
//     x = next
//   }
//   return count
// }
// The check of x is a safe call on the line of the elvis operator.
TEST_F(KotlinSafeCallOperatorFilterTest, ShouldReplaceNullBranchOfChainInLoop) {
  TestMethod m("whileTrue", "(Lcom/example/Node;)I");
  lir::Label* not_null = m.NewLabel();
  lir::Label* end = m.NewLabel();
  Elvis elvis = AddLoop(m, /*check_line=*/5, not_null, end);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{elvis.check, {end, not_null}}});
  ExpectIgnored(m, {Range{elvis.jump, elvis.jump}});
}

// fun whileNotNull(start: Node?): Int {
//   var x = start
//   var count = 0
//   while (x != null) {
//     val next = x.next ?: break
//     count++
//     x = next
//   }
//   return count
// }
// D8 compiles this like the loop above, but the condition of the loop, which
// also jumps to the end of the loop, is on another line than the elvis
// operator.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenLoopConditionIsOnOtherLine) {
  TestMethod m("whileNotNull", "(Lcom/example/Node;)I");
  lir::Label* not_null = m.NewLabel();
  lir::Label* end = m.NewLabel();
  AddLoop(m, /*check_line=*/4, not_null, end);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun orValue(a: Node?): Int {
//   if (a == null || a.value == null) return 0
//   return 1
// }
// D8 compiles this condition like a?.value ?: return 0, but the code doesn't
// use the value of a.value after checking it.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceNullChecksWrittenByHand) {
  TestMethod m("orValue", "(Lcom/example/Node;)I");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(null_code)});
  AddGetValue(m, /*from=*/1, /*to=*/0);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun elvisStatement(n: Node?) {
//   n?.next?.value ?: return
//   log("has value")
// }
// The value of the elvis operator isn't used, as in null checks written by
// hand.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenValueOfElvisIsUnused) {
  TestMethod m("elvisStatement", "(Lcom/example/Node;)V");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("has value")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0}),
         m.MethodRef(kTestClassName, "log", "(Ljava/lang/String;)V")});
  m.Add(dex::OP_RETURN_VOID);
  m.Place(null_code);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// fun elvisThenWait(n: Node?): String {
//   val v = n?.next?.value ?: return "none"
//   while (poll()) {}
//   return "done"
// }
// The loop after the elvis operator doesn't use its value either.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenValueIsUnusedInLoop) {
  TestMethod m("elvisThenWait", kSignature);
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({}), m.MethodRef(kTestClassName, "poll", "()Z")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(2)});
  m.Add(dex::OP_IF_NEZ, {m.Reg(2), m.Target(not_null)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("done")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

// A check after the elvis operator also jumps to the null code, as if D8
// shared it, so that code doesn't only run for the values of the chain.
TEST_F(KotlinSafeCallOperatorFilterTest,
       ShouldNotReplaceWhenCheckFollowsElvis) {
  TestMethod m("checkAfterElvis",
               "(Lcom/example/Node;Lcom/example/Node;)Ljava/lang/String;");
  lir::Label* not_null = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  AddChain(m, /*node=*/1, /*value=*/0, null_code);
  AddElvis(m, /*value=*/0, not_null, null_code);
  m.Place(not_null);
  m.Add(dex::OP_IF_EQZ, {m.Reg(2), m.Target(null_code)});
  m.Add(
      dex::OP_INVOKE_VIRTUAL,
      {m.Regs({2, 0}),
       m.MethodRef(kNode, "append", "(Ljava/lang/String;)Ljava/lang/String;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(null_code);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("none")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {});
}

}  // namespace
}  // namespace coverage
