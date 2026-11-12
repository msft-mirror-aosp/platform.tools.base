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

#include "tools/base/android-test/coverage/agent/native/kotlin_when_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kException[] = "Lkotlin/NoWhenBranchMatchedException;";
constexpr char kCircle[] = "Lcom/example/Shape$Circle;";
constexpr char kSquare[] = "Lcom/example/Shape$Square;";
constexpr char kColor[] = "Lcom/example/Color;";
constexpr char kMappings[] = "Lcom/example/FooKt$WhenMappings;";
constexpr char kMapping[] = "$EnumSwitchMapping$0";
// The signatures of fun f(s: Shape): Int and fun f(c: Color?): Int.
constexpr char kShapeSignature[] = "(Lcom/example/Shape;)I";
constexpr char kColorSignature[] = "(Lcom/example/Color;)I";

// How KotlinWhenFilterTest::AddNullCheck() lays out the null check of the
// nullable enum in v2. The defaults are the code that the Kotlin compiler and
// D8 generate. Tests change one of them to get code that only looks like it.
struct NullCheckShape {
  // Where the code loads the case of null: right after the check, before the
  // check into v4 and then moved after the check, or before the check, right
  // into its register. D8 loads -1 before the check when the method uses it
  // more than once.
  enum class Load { kAfterCheck, kMovedAfterCheck, kBeforeCheck };
  Load load = Load::kAfterCheck;
  // Whether the check is inverted: an if-eqz that jumps to the switch when the
  // subject is null, followed by the code that maps its ordinal.
  bool inverted = false;
  // The check of the subject, which jumps to the code that maps its ordinal,
  // or past the read of the array in that code.
  dex::Opcode check = dex::OP_IF_NEZ;
  bool check_skips_array = false;
  // The case of null, and the register that it ends up in.
  dex::s4 null_case = -1;
  dex::u4 null_register = 0;
  // How the code of null goes on: a goto to the switch, an if-eqz on v3 to the
  // switch, or a return.
  dex::Opcode jump = dex::OP_GOTO;
  // The array that maps the ordinals to cases.
  const char* array_owner = kMappings;
  const char* array_name = kMapping;
  // The method that the code calls on `receiver` for the ordinal, and whether
  // it moves the result to v1, or loads 0 into v1 instead.
  const char* method = "ordinal";
  dex::u4 receiver = 2;
  bool moves_result = true;
  // The registers of the aget: the case, the array and the index.
  dex::u4 case_register = 0;
  dex::u4 array_register = 0;
  dex::u4 index_register = 1;
  // Whether the code loads a constant before the code of null joins it.
  bool constant_before_join = false;
  // The register that the switch after the null check reads.
  dex::u4 switch_register = 0;
};

// Unit tests for KotlinWhenFilter, on the code that the Kotlin compiler and D8
// generate for `when` on
//   sealed class Shape { class Circle : Shape(); class Square : Shape() }
//   enum class Color { RED, GREEN, BLUE }
class KotlinWhenFilterTest : public FilterTestBase {
 protected:
  // Appends the code that throws NoWhenBranchMatchedException from v<reg>.
  static Range AddThrow(TestMethod& m, dex::u4 reg) {
    lir::Instruction* created =
        m.Add(dex::OP_NEW_INSTANCE, {m.Reg(reg), m.TypeRef(kException)});
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({reg}), m.MethodRef(kException, "<init>", "()V")});
    return {created, m.Add(dex::OP_THROW, {m.Reg(reg)})};
  }

  // Appends the check of `is <type>` on the shape in v1, which jumps to
  // `otherwise` when it is false.
  static lir::Bytecode* AddIsCheck(TestMethod& m, const char* type,
                                   lir::Label* otherwise) {
    m.Add(dex::OP_INSTANCE_OF, {m.Reg(0), m.Reg(1), m.TypeRef(type)});
    return m.Add(dex::OP_IF_EQZ, {m.Reg(0), m.Target(otherwise)});
  }

  // Appends `return <value>`, with the value in v0.
  static void AddReturn(TestMethod& m, dex::u4 value) {
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(value)});
    m.Add(dex::OP_RETURN, {m.Reg(0)});
  }

  // Appends the code that maps the ordinal of the enum in v2 to its case in
  // the `when`, in v0. Returns the aget.
  static lir::Bytecode* AddCase(TestMethod& m) {
    m.Add(dex::OP_SGET_OBJECT,
          {m.Reg(0), m.FieldRef(kMappings, kMapping, "[I")});
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({2}), m.MethodRef(kColor, "ordinal", "()I")});
    m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
    return m.Add(dex::OP_AGET, {m.Reg(0), m.Reg(0), m.Reg(1)});
  }

  // Appends the switch on the case of the enum in v2, with the payload at
  // `cases`.
  static lir::Bytecode* AddCaseSwitch(TestMethod& m, lir::Label* cases) {
    AddCase(m);
    return m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
  }

  // Appends the load of `value` into v<reg>. Registers above v15 take a
  // const/16.
  static void AddConstant(TestMethod& m, dex::u4 reg, dex::s4 value) {
    m.Add(reg < 16 ? dex::OP_CONST_4 : dex::OP_CONST_16,
          {m.Reg(reg), m.Literal(static_cast<dex::u4>(value))});
  }

  // The null check of a nullable enum subject.
  struct NullCheck {
    lir::Instruction* check;
    lir::Instruction* load;
  };

  // Appends the null check of the nullable enum in v2 laid out as `shape`. By
  // default, it leaves case -1 in v0 when the subject is null, and its case
  // from the `when` mapping otherwise. The code that follows runs in both
  // cases.
  static NullCheck AddNullCheck(TestMethod& m,
                                const NullCheckShape& shape = {}) {
    using Load = NullCheckShape::Load;
    lir::Label* not_null = m.NewLabel();
    lir::Label* known = m.NewLabel();
    if (shape.load == Load::kMovedAfterCheck) {
      AddConstant(m, 4, shape.null_case);
    } else if (shape.load == Load::kBeforeCheck) {
      AddConstant(m, shape.null_register, shape.null_case);
    }
    lir::Instruction* check;
    if (shape.inverted) {
      check = m.Add(dex::OP_IF_EQZ, {m.Reg(2), m.Target(known)});
    } else {
      check = m.Add(shape.check, {m.Reg(2), m.Target(not_null)});
      if (shape.load == Load::kAfterCheck) {
        AddConstant(m, shape.null_register, shape.null_case);
      } else if (shape.load == Load::kMovedAfterCheck) {
        m.Add(dex::OP_MOVE, {m.Reg(shape.null_register), m.Reg(4)});
      }
      if (shape.jump == dex::OP_RETURN) {
        m.Add(dex::OP_RETURN, {m.Reg(0)});
      } else if (shape.jump == dex::OP_GOTO) {
        m.Add(dex::OP_GOTO, {m.Target(known)});
      } else {
        m.Add(shape.jump, {m.Reg(3), m.Target(known)});
      }
      if (!shape.check_skips_array) {
        m.Place(not_null);
      }
    }
    m.Add(dex::OP_SGET_OBJECT,
          {m.Reg(0), m.FieldRef(shape.array_owner, shape.array_name, "[I")});
    if (shape.check_skips_array && !shape.inverted) {
      m.Place(not_null);
    }
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({shape.receiver}), m.MethodRef(kColor, shape.method, "()I")});
    if (shape.moves_result) {
      m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
    } else {
      m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
    }
    lir::Instruction* load = m.Add(
        dex::OP_AGET, {m.Reg(shape.case_register), m.Reg(shape.array_register),
                       m.Reg(shape.index_register)});
    if (shape.constant_before_join) {
      m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
    }
    m.Place(known);
    return {check, load};
  }

  KotlinWhenFilter filter_;
};

// fun sides(s: Shape): Int =
//     when (s) {
//       is Shape.Circle -> 0
//       is Shape.Square -> 4
//     }
// The check of the last case jumps to the throw, which D8 moves to the end of
// the method.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreLastCheckAndThrow) {
  TestMethod m("sides", kShapeSignature);
  lir::Label* square = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  AddIsCheck(m, kCircle, square);
  AddReturn(m, 0);
  m.Place(square);
  lir::Bytecode* last_check = AddIsCheck(m, kSquare, no_match);
  AddReturn(m, 4);
  m.Place(no_match);
  Range thrown = AddThrow(m, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{last_check, last_check}, thrown});
}

// D8 can start the throw with moves, which keep the values of local variables
// whose registers it reuses.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreMovesBeforeThrow) {
  TestMethod m("sides", kShapeSignature);
  lir::Label* square = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  AddIsCheck(m, kCircle, square);
  AddReturn(m, 0);
  m.Place(square);
  lir::Bytecode* last_check = AddIsCheck(m, kSquare, no_match);
  AddReturn(m, 4);
  m.Place(no_match);
  lir::Bytecode* move = m.Add(dex::OP_MOVE_OBJECT, {m.Reg(2), m.Reg(1)});
  Range thrown = AddThrow(m, 1);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{last_check, last_check}, Range{move, thrown.to}});
}

// Two checks jump to the throw, which the compiler doesn't generate. Only the
// nearest one before the throw counts as the check of the last case.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreOnlyNearestCheck) {
  TestMethod m("sides", kShapeSignature);
  lir::Label* no_match = m.NewLabel();
  AddIsCheck(m, kCircle, no_match);
  lir::Bytecode* last_check = AddIsCheck(m, kSquare, no_match);
  AddReturn(m, 4);
  m.Place(no_match);
  Range thrown = AddThrow(m, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{last_check, last_check}, thrown});
}

// A method with many registers can keep the exception in a register above
// v15, which it passes to the constructor with invoke-direct/range.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreThrowWithRangeCall) {
  TestMethod m("sides", kShapeSignature);
  lir::Label* square = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  AddIsCheck(m, kCircle, square);
  AddReturn(m, 0);
  m.Place(square);
  lir::Bytecode* last_check = AddIsCheck(m, kSquare, no_match);
  AddReturn(m, 4);
  m.Place(no_match);
  lir::Bytecode* created =
      m.Add(dex::OP_NEW_INSTANCE, {m.Reg(16), m.TypeRef(kException)});
  m.Add(dex::OP_INVOKE_DIRECT_RANGE,
        {m.RegRange(16, 1), m.MethodRef(kException, "<init>", "()V")});
  lir::Bytecode* thrown = m.Add(dex::OP_THROW, {m.Reg(16)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{last_check, last_check}, Range{created, thrown}});
}

// fun level(b: Boolean): Int =
//     when (b) {
//       true -> 1
//       false -> 0
//     }
// The last check jumps to the throw with an if-nez, which the cases of sealed
// classes don't compile to.
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreIfNezToThrow) {
  TestMethod m("level", "(Z)I");
  lir::Label* is_false = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(is_false)});
  AddReturn(m, 1);
  m.Place(is_false);
  m.Add(dex::OP_IF_NEZ, {m.Reg(1), m.Target(no_match)});
  AddReturn(m, 0);
  m.Place(no_match);
  AddThrow(m, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// In composable functions, the Compose compiler ends a group right before the
// throw, so the check of the last case jumps to that call instead.
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreThrowAfterOtherCode) {
  constexpr char kComposer[] = "Landroidx/compose/runtime/Composer;";
  TestMethod m("sides", kShapeSignature);
  lir::Label* square = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  AddIsCheck(m, kCircle, square);
  AddReturn(m, 0);
  m.Place(square);
  AddIsCheck(m, kSquare, no_match);
  AddReturn(m, 4);
  m.Place(no_match);
  m.Add(dex::OP_INVOKE_INTERFACE,
        {m.Regs({2}), m.MethodRef(kComposer, "endReplaceGroup", "()V")});
  AddThrow(m, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Only a check before the throw counts as the check of the last case. This
// one comes after it.
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreCheckAfterThrow) {
  TestMethod m("sides", kShapeSignature);
  lir::Label* start = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  m.Add(dex::OP_GOTO, {m.Target(start)});
  m.Place(no_match);
  AddThrow(m, 0);
  m.Place(start);
  AddIsCheck(m, kCircle, no_match);
  AddReturn(m, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// fun sides(s: Shape): Int =
//     if (s is Shape.Circle) 0 else throw IllegalStateException()
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreOtherException) {
  constexpr char kError[] = "Ljava/lang/IllegalStateException;";
  TestMethod m("sides", kShapeSignature);
  lir::Label* fail = m.NewLabel();
  AddIsCheck(m, kCircle, fail);
  AddReturn(m, 0);
  m.Place(fail);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kError)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0}), m.MethodRef(kError, "<init>", "()V")});
  m.Add(dex::OP_THROW, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The exception that the code throws must be the one that it creates.
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreThrowOfOtherRegister) {
  TestMethod m("sides", kShapeSignature);
  lir::Label* no_match = m.NewLabel();
  AddIsCheck(m, kCircle, no_match);
  AddReturn(m, 0);
  m.Place(no_match);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kException)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0}), m.MethodRef(kException, "<init>", "()V")});
  m.Add(dex::OP_THROW, {m.Reg(1)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// fun create(s: Shape): Any? =
//     if (s is Shape.Circle) null else NoWhenBranchMatchedException()
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreExceptionThatIsReturned) {
  TestMethod m("create", "(Lcom/example/Shape;)Ljava/lang/Object;");
  lir::Label* no_match = m.NewLabel();
  AddIsCheck(m, kCircle, no_match);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  m.Place(no_match);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kException)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0}), m.MethodRef(kException, "<init>", "()V")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// fun sides(s: Shape): Int =
//     if (s is Shape.Circle) 0 else throw NoWhenBranchMatchedException("x")
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreThrowWithMessage) {
  TestMethod m("sides", kShapeSignature);
  lir::Label* no_match = m.NewLabel();
  m.Add(dex::OP_CONST_STRING, {m.Reg(2), m.StringRef("x")});
  AddIsCheck(m, kCircle, no_match);
  AddReturn(m, 0);
  m.Place(no_match);
  m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kException)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0, 2}),
         m.MethodRef(kException, "<init>", "(Ljava/lang/String;)V")});
  m.Add(dex::OP_THROW, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// fun code(c: Color): Int =
//     when (c) {
//       Color.RED -> 1
//       Color.GREEN -> 2
//       Color.BLUE -> 3
//     }
// The switch on the case of the subject falls through to the throw, its
// default.
TEST_F(KotlinWhenFilterTest, ShouldReplaceBranchesOfSwitchWithDefaultThrow) {
  TestMethod m("code", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* blue = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  lir::Bytecode* on_case = AddCaseSwitch(m, cases);
  Range thrown = AddThrow(m, 0);
  m.Place(blue);
  AddReturn(m, 3);
  m.Place(green);
  AddReturn(m, 2);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red, green, blue});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{on_case, {red, green, blue}}});
  ExpectIgnored(m, {thrown});
}

// A sparse switch is handled like a packed one.
TEST_F(KotlinWhenFilterTest, ShouldReplaceBranchesOfSparseSwitch) {
  TestMethod m("code", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* blue = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  AddCase(m);
  lir::Bytecode* on_case =
      m.Add(dex::OP_SPARSE_SWITCH, {m.Reg(0), m.Target(cases)});
  Range thrown = AddThrow(m, 0);
  m.Place(blue);
  AddReturn(m, 3);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddSparseSwitchPayload({{1, red}, {3, blue}});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{on_case, {red, blue}}});
  ExpectIgnored(m, {thrown});
}

// The switch of
//   fun warm(c: Color?): Boolean =
//       when (c) {
//         Color.RED, null -> true
//         Color.GREEN -> true
//         Color.BLUE -> false
//       }
// after its null check, which this test leaves out. Case 0, which no constant
// maps to, goes to the default, and RED shares the code of null. The branches
// count each case once, without the default.
TEST_F(KotlinWhenFilterTest, ShouldLeaveOutDefaultAndDuplicateCases) {
  TestMethod m("warm", "(Lcom/example/Color;)Z");
  lir::Label* no_match = m.NewLabel();
  lir::Label* warm = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* cold = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  lir::Bytecode* on_case = AddCaseSwitch(m, cases);
  m.Place(no_match);
  Range thrown = AddThrow(m, 0);
  m.Place(cold);
  AddReturn(m, 0);
  m.Place(green);
  AddReturn(m, 1);
  m.Place(warm);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(-1, {warm, no_match, warm, green, cold});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{on_case, {warm, green, cold}}});
  ExpectIgnored(m, {thrown});
}

// D8 can start the default code of the switch with moves. A case that goes
// there goes to the default.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreMovesAfterSwitch) {
  TestMethod m("code", kColorSignature);
  lir::Label* no_match = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  lir::Bytecode* on_case = AddCaseSwitch(m, cases);
  m.Place(no_match);
  lir::Bytecode* move = m.Add(dex::OP_MOVE_OBJECT, {m.Reg(3), m.Reg(2)});
  Range thrown = AddThrow(m, 2);
  m.Place(green);
  AddReturn(m, 2);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(0, {no_match, red, green});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{on_case, {red, green}}});
  ExpectIgnored(m, {Range{move, thrown.to}});
}

// fun inTry(c: Color): Int =
//     try {
//       when (c) {
//         Color.RED -> 1
//         Color.GREEN -> 2
//       }
//     } catch (e: IllegalStateException) {
//       -1
//     }
// In a try block, the switch falls through to the new-instance only, which D8
// follows with a goto to the rest of the throw.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreThrowSplitByGoto) {
  TestMethod m("inTry", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* rest = m.NewLabel();
  lir::Label* handler = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  lir::TryBlockBegin* begin = m.AddTryBlockBegin();
  lir::Bytecode* on_case = AddCaseSwitch(m, cases);
  lir::Bytecode* created =
      m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kException)});
  lir::Bytecode* jump = m.Add(dex::OP_GOTO, {m.Target(rest)});
  m.Place(green);
  AddReturn(m, 2);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(rest);
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0}), m.MethodRef(kException, "<init>", "()V")});
  lir::Bytecode* thrown = m.Add(dex::OP_THROW, {m.Reg(0)});
  m.AddTryBlockEnd(begin, handler, "Ljava/lang/IllegalStateException;");
  m.Place(handler);
  m.Add(dex::OP_MOVE_EXCEPTION, {m.Reg(0)});
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(static_cast<dex::u4>(-1))});
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red, green});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{on_case, {red, green}}});
  ExpectIgnored(m, {Range{created, jump}, Range{rest, thrown}});
}

// D8 can also put the goto after the call to the constructor.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreThrowSplitAfterCall) {
  TestMethod m("sides", kShapeSignature);
  lir::Label* no_match = m.NewLabel();
  lir::Label* circle = m.NewLabel();
  lir::Label* rest = m.NewLabel();
  lir::Bytecode* last_check = AddIsCheck(m, kCircle, no_match);
  m.Add(dex::OP_GOTO, {m.Target(circle)});
  m.Place(no_match);
  lir::Bytecode* created =
      m.Add(dex::OP_NEW_INSTANCE, {m.Reg(0), m.TypeRef(kException)});
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({0}), m.MethodRef(kException, "<init>", "()V")});
  lir::Bytecode* jump = m.Add(dex::OP_GOTO, {m.Target(rest)});
  m.Place(circle);
  AddReturn(m, 0);
  m.Place(rest);
  lir::Bytecode* thrown = m.Add(dex::OP_THROW, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{last_check, last_check}, Range{created, jump},
                    Range{rest, thrown}});
}

// A switch whose default runs other code keeps its branches, even when one of
// its cases throws the exception.
TEST_F(KotlinWhenFilterTest, ShouldNotReplaceBranchesOfSwitchWithOtherDefault) {
  TestMethod m("code", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* no_match = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  AddCaseSwitch(m, cases);
  AddReturn(m, 0);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(no_match);
  AddThrow(m, 0);
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red, no_match});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// fun nullableElse(c: Color?): Int =
//     when (c) {
//       Color.RED -> 1
//       Color.GREEN -> 2
//       else -> 0
//     }
// The subject is checked for null before the switch on its case, which takes
// null as case -1, here through the default.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreNullCheckOfNullableEnum) {
  TestMethod m("nullableElse", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  NullCheck null_check = AddNullCheck(m);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
  AddReturn(m, 0);
  m.Place(green);
  AddReturn(m, 2);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red, green});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{null_check.check, null_check.load}});
}

// fun nullableCode(c: Color?): Int =
//     when (c) {
//       Color.RED -> 1
//       Color.GREEN -> 2
//       Color.BLUE -> 3
//       null -> 0
//     }
TEST_F(KotlinWhenFilterTest, ShouldIgnoreNullCheckAndThrowOfNullableEnum) {
  TestMethod m("nullableCode", kColorSignature);
  lir::Label* no_match = m.NewLabel();
  lir::Label* null_code = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* blue = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  NullCheck null_check = AddNullCheck(m);
  lir::Bytecode* on_case =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
  m.Place(no_match);
  Range thrown = AddThrow(m, 0);
  m.Place(blue);
  AddReturn(m, 3);
  m.Place(green);
  AddReturn(m, 2);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(null_code);
  AddReturn(m, 0);
  m.Place(cases);
  m.AddPackedSwitchPayload(-1, {null_code, no_match, red, green, blue});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{on_case, {null_code, red, green, blue}}});
  ExpectIgnored(m, {Range{null_check.check, null_check.load}, thrown});
}

// fun nullableWarm(c: Color?): Boolean =
//     when (c) {
//       Color.RED, null -> true
//       Color.GREEN -> true
//       Color.BLUE -> false
//     }
// D8 loads the value that two branches return before the switch.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreNullCheckWithConstantBeforeSwitch) {
  TestMethod m("nullableWarm", "(Lcom/example/Color;)Z");
  lir::Label* no_match = m.NewLabel();
  lir::Label* warm = m.NewLabel();
  lir::Label* green = m.NewLabel();
  lir::Label* cold = m.NewLabel();
  lir::Label* end = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  NullCheck null_check = AddNullCheck(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  lir::Bytecode* on_case =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
  m.Place(no_match);
  Range thrown = AddThrow(m, 0);
  m.Place(cold);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(0)});
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(green);
  m.Add(dex::OP_GOTO, {m.Target(end)});
  m.Place(warm);
  m.Add(dex::OP_NOP);
  m.Place(end);
  m.Add(dex::OP_RETURN, {m.Reg(1)});
  m.Place(cases);
  m.AddPackedSwitchPayload(-1, {warm, no_match, warm, green, cold});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{on_case, {warm, green, cold}}});
  ExpectIgnored(m, {Range{null_check.check, null_check.load}, thrown});
}

// A method with many registers can keep the case in a register above v15,
// where only const/16 can load -1.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreNullCheckOfCaseInHighRegister) {
  TestMethod m("nullableElse", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  NullCheckShape shape;
  shape.null_register = 16;
  shape.case_register = 16;
  NullCheck null_check = AddNullCheck(m, shape);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(16), m.Target(cases)});
  AddReturn(m, 0);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{null_check.check, null_check.load}});
}

// When a method uses -1 more than once, D8 loads it once, before the check,
// and moves it into the case register when the subject is null, as in the
// first `when` of
//   fun twoNullable(a: Color?, b: Color?): Int {
//     val x = when (a) { Color.RED -> 1; ...; null -> 0 }
//     val y = when (b) { Color.RED -> 10; ...; null -> 0 }
//     return x + y
//   }
TEST_F(KotlinWhenFilterTest, ShouldIgnoreNullCheckWithMovedMinusOne) {
  TestMethod m("twoNullable", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  NullCheckShape shape;
  shape.load = NullCheckShape::Load::kMovedAfterCheck;
  NullCheck null_check = AddNullCheck(m, shape);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
  AddReturn(m, 0);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{null_check.check, null_check.load}});
}

// When the case register already holds -1, the code for null is only the
// goto, as in the second `when` of twoNullable(), or next to a `return -1`.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreNullCheckWithMinusOneLoadedBefore) {
  TestMethod m("withMinusOne", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  NullCheckShape shape;
  shape.load = NullCheckShape::Load::kBeforeCheck;
  NullCheck null_check = AddNullCheck(m, shape);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
  AddReturn(m, 0);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{null_check.check, null_check.load}});
}

// D8 can then also invert the check: it jumps to the switch when the subject
// is null, and the code that maps the ordinal follows it.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreInvertedNullCheck) {
  TestMethod m("withMinusOne", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  NullCheckShape shape;
  shape.load = NullCheckShape::Load::kBeforeCheck;
  shape.inverted = true;
  NullCheck null_check = AddNullCheck(m, shape);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
  AddReturn(m, 0);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{null_check.check, null_check.load}});
}

// Two nullable enum `when`s that cover every case, in a method with many
// registers:
//   val brand = when (preferences.themeBrand) { null, ... -> DEFAULT; ... }
//   val config = when (preferences.darkThemeConfig) { null, ... -> ...; ... }
// D8 loads -1 into v17 once. The first null check moves it into the case
// register. The second one is inverted and keeps it in v17, which is also its
// case register.
TEST_F(KotlinWhenFilterTest, ShouldIgnoreNullChecksThatShareMinusOne) {
  constexpr char kProto[] = "Lcom/example/ThemeBrandProto;";
  constexpr char kPreferences[] = "Lcom/example/Preferences;";
  TestMethod m("emit", "(Lcom/example/Preferences;)V");
  lir::Label* first_not_null = m.NewLabel();
  lir::Label* first_known = m.NewLabel();
  lir::Label* first_no_match = m.NewLabel();
  lir::Label* first_cases = m.NewLabel();
  lir::Label* second = m.NewLabel();
  lir::Label* second_known = m.NewLabel();
  lir::Label* second_no_match = m.NewLabel();
  lir::Label* second_cases = m.NewLabel();
  lir::Label* end = m.NewLabel();
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({10}), m.MethodRef(kPreferences, "getThemeBrand",
                                   "()Lcom/example/ThemeBrandProto;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(16)});
  m.Add(dex::OP_CONST_16, {m.Reg(17), m.Literal(static_cast<dex::u4>(-1))});
  lir::Bytecode* first_check =
      m.Add(dex::OP_IF_NEZ, {m.Reg(16), m.Target(first_not_null)});
  m.Add(dex::OP_MOVE_FROM16, {m.Reg(16), m.Reg(17)});
  m.Add(dex::OP_GOTO, {m.Target(first_known)});
  m.Place(first_not_null);
  m.Add(dex::OP_SGET_OBJECT,
        {m.Reg(18), m.FieldRef(kMappings, kMapping, "[I")});
  m.Add(dex::OP_INVOKE_VIRTUAL_RANGE,
        {m.RegRange(16, 1), m.MethodRef(kProto, "ordinal", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(16)});
  lir::Bytecode* first_load =
      m.Add(dex::OP_AGET, {m.Reg(16), m.Reg(18), m.Reg(16)});
  m.Place(first_known);
  lir::Bytecode* first_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(16), m.Target(first_cases)});
  m.Place(first_no_match);
  Range first_thrown = AddThrow(m, 4);
  m.Place(second);
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({10}), m.MethodRef(kPreferences, "getDarkThemeConfig",
                                   "()Lcom/example/ThemeBrandProto;")});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(18)});
  lir::Bytecode* second_check =
      m.Add(dex::OP_IF_EQZ, {m.Reg(18), m.Target(second_known)});
  m.Add(dex::OP_SGET_OBJECT,
        {m.Reg(17), m.FieldRef(kMappings, "$EnumSwitchMapping$1", "[I")});
  m.Add(dex::OP_INVOKE_VIRTUAL_RANGE,
        {m.RegRange(18, 1), m.MethodRef(kProto, "ordinal", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(18)});
  lir::Bytecode* second_load =
      m.Add(dex::OP_AGET, {m.Reg(17), m.Reg(17), m.Reg(18)});
  m.Place(second_known);
  lir::Bytecode* second_switch =
      m.Add(dex::OP_PACKED_SWITCH, {m.Reg(17), m.Target(second_cases)});
  m.Place(second_no_match);
  Range second_thrown = AddThrow(m, 4);
  m.Place(end);
  m.Add(dex::OP_RETURN_VOID);
  m.Place(first_cases);
  m.AddPackedSwitchPayload(-1, {second, first_no_match, second});
  m.Place(second_cases);
  m.AddPackedSwitchPayload(-1, {end, second_no_match, end});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectReplaced(m, {Replacement{first_switch, {second}},
                     Replacement{second_switch, {end}}});
  ExpectIgnored(m, {Range{first_check, first_load}, first_thrown,
                    Range{second_check, second_load}, second_thrown});
}

// fun nullableRed(c: Color?): Int =
//     when (c) {
//       Color.RED -> 1
//       else -> 0
//     }
// For a single case, the Kotlin compiler compares the case instead of
// switching on it.
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreNullCheckWithoutSwitch) {
  TestMethod m("nullableRed", kColorSignature);
  lir::Label* other = m.NewLabel();
  AddNullCheck(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  m.Add(dex::OP_IF_NE, {m.Reg(0), m.Reg(1), m.Target(other)});
  AddReturn(m, 1);
  m.Place(other);
  AddReturn(m, 0);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Code that differs from the null check in one part keeps its branches.
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreOtherCodeLikeNullCheck) {
  struct Variant {
    const char* description;
    void (*change)(NullCheckShape& shape);
  };
  const Variant variants[] = {
      {"checks with if-eqz",
       [](NullCheckShape& s) { s.check = dex::OP_IF_EQZ; }},
      {"takes null as case 0", [](NullCheckShape& s) { s.null_case = 0; }},
      {"returns when the subject is null",
       [](NullCheckShape& s) { s.jump = dex::OP_RETURN; }},
      {"jumps to the switch only on a condition",
       [](NullCheckShape& s) { s.jump = dex::OP_IF_EQZ; }},
      {"jumps past the read of the array",
       [](NullCheckShape& s) { s.check_skips_array = true; }},
      {"reads an array of another class",
       [](NullCheckShape& s) { s.array_owner = "Lcom/example/Tables;"; }},
      {"reads another array of the mappings",
       [](NullCheckShape& s) { s.array_name = "$Values"; }},
      {"calls hashCode()", [](NullCheckShape& s) { s.method = "hashCode"; }},
      {"calls ordinal() on another value",
       [](NullCheckShape& s) { s.receiver = 3; }},
      {"doesn't use the ordinal",
       [](NullCheckShape& s) { s.moves_result = false; }},
      {"leaves the case in another register",
       [](NullCheckShape& s) { s.case_register = 1; }},
      {"reads the array from another register",
       [](NullCheckShape& s) { s.array_register = 3; }},
      {"indexes the array with another value",
       [](NullCheckShape& s) { s.index_register = 3; }},
      {"loads a constant only when the subject isn't null",
       [](NullCheckShape& s) { s.constant_before_join = true; }},
      {"switches on another value",
       [](NullCheckShape& s) { s.switch_register = 1; }},
      {"moves -1 into another register than the case",
       [](NullCheckShape& s) {
         s.load = NullCheckShape::Load::kMovedAfterCheck;
         s.null_register = 1;
       }},
      {"checks another value with an inverted check",
       [](NullCheckShape& s) {
         s.load = NullCheckShape::Load::kBeforeCheck;
         s.inverted = true;
         s.receiver = 3;
       }},
      {"jumps past a constant with an inverted check",
       [](NullCheckShape& s) {
         s.load = NullCheckShape::Load::kBeforeCheck;
         s.inverted = true;
         s.constant_before_join = true;
       }},
  };
  for (const Variant& variant : variants) {
    SCOPED_TRACE(variant.description);
    NullCheckShape shape;
    variant.change(shape);
    TestMethod m("nullableElse", kColorSignature);
    lir::Label* red = m.NewLabel();
    lir::Label* cases = m.NewLabel();
    AddNullCheck(m, shape);
    m.Add(dex::OP_PACKED_SWITCH,
          {m.Reg(shape.switch_register), m.Target(cases)});
    AddReturn(m, 0);
    m.Place(red);
    AddReturn(m, 1);
    m.Place(cases);
    m.AddPackedSwitchPayload(1, {red});
    Output output;

    filter_.Filter(context_, m.ir_method(), m.code_ir(), output);

    EXPECT_TRUE(output.ignored().empty());
    EXPECT_TRUE(output.replaced().empty());
  }
}

// Other code can't jump between the null check and the switch.
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreNullCheckWhenOtherCodeJoins) {
  TestMethod m("nullableElse", kColorSignature);
  lir::Label* on_case = m.NewLabel();
  lir::Label* red = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  m.Add(dex::OP_IF_EQZ, {m.Reg(3), m.Target(on_case)});
  AddNullCheck(m);
  m.Add(dex::OP_CONST_4, {m.Reg(1), m.Literal(1)});
  m.Place(on_case);
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
  AddReturn(m, 0);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The code between the null check and the switch must leave the case as is.
TEST_F(KotlinWhenFilterTest, ShouldNotIgnoreNullCheckWhenCaseChanges) {
  TestMethod m("nullableElse", kColorSignature);
  lir::Label* red = m.NewLabel();
  lir::Label* cases = m.NewLabel();
  AddNullCheck(m);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Add(dex::OP_PACKED_SWITCH, {m.Reg(0), m.Target(cases)});
  AddReturn(m, 0);
  m.Place(red);
  AddReturn(m, 1);
  m.Place(cases);
  m.AddPackedSwitchPayload(1, {red});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
