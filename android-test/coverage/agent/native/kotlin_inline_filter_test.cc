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

#include "tools/base/android-test/coverage/agent/native/kotlin_inline_filter.h"

#include <gtest/gtest.h>

#include <optional>
#include <string>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

// The SMAP that the Kotlin compiler writes for a file Inline.kt of 42 lines:
// an inline function of the same file is inlined on lines 43-47, 60-64 and
// 66-70, one of Helpers.kt on lines 48-49, collection functions on lines
// 51-59, and the marker of an inlined lambda on line 50.
constexpr char kCompilerSmap[] =
    "SMAP\n"
    "Inline.kt\n"
    "Kotlin\n"
    "*S Kotlin\n"
    "*F\n"
    "+ 1 Inline.kt\n"
    "com/example/InlineKt\n"
    "+ 2 Helpers.kt\n"
    "com/example/HelpersKt\n"
    "+ 3 fake.kt\n"
    "kotlin/jvm/internal/FakeKt\n"
    "+ 4 _Collections.kt\n"
    "kotlin/collections/CollectionsKt___CollectionsKt\n"
    "*L\n"
    "1#1,42:1\n"
    "4#1,5:43\n"
    "4#1,5:60\n"
    "24#1:65\n"
    "4#1,5:66\n"
    "28#1:71\n"
    "4#2,2:48\n"
    "1#3:50\n"
    "1586#4:51\n"
    "1661#4,3:52\n"
    "777#4:55\n"
    "873#4,2:56\n"
    "1915#4,2:58\n"
    "*S KotlinDebug\n"
    "*F\n"
    "+ 1 Inline.kt\n"
    "com/example/InlineKt\n"
    "*L\n"
    "11#1:43,5\n"
    "24#1:60,5\n"
    "26#1:65\n"
    "26#1:66,5\n"
    "30#1:71\n"
    "13#1:48,2\n"
    "18#1:51\n"
    "18#1:52,3\n"
    "18#1:55\n"
    "18#1:56,2\n"
    "19#1:58,2\n"
    "*E\n";

// The SMAP that the Kotlin compiler writes for an anonymous object of a file
// Classes.kt of 23 lines, which calls forEach().
constexpr char kAnonymousObjectSmap[] =
    "SMAP\n"
    "Classes.kt\n"
    "Kotlin\n"
    "*S Kotlin\n"
    "*F\n"
    "+ 1 Classes.kt\n"
    "com/example/RenamedFile$anonymousObject$1\n"
    "+ 2 _Collections.kt\n"
    "kotlin/collections/CollectionsKt___CollectionsKt\n"
    "*L\n"
    "1#1,23:1\n"
    "1915#2,2:24\n"
    "*S KotlinDebug\n"
    "*F\n"
    "+ 1 Classes.kt\n"
    "com/example/RenamedFile$anonymousObject$1\n"
    "*L\n"
    "18#1:24,2\n"
    "*E\n";

// The header and file section of the SMAP of com/example/AKt, compiled from
// A.kt, with Standard.kt as the second file. Tests append the line section.
constexpr char kSmapStart[] =
    "SMAP\n"
    "A.kt\n"
    "Kotlin\n"
    "*S Kotlin\n"
    "*F\n"
    "+ 1 A.kt\n"
    "com/example/AKt\n"
    "+ 2 Standard.kt\n"
    "kotlin/StandardKt__StandardKt\n"
    "*L\n";

std::optional<int32_t> FirstInlinedLineOfA(const std::string& line_section) {
  return KotlinInlineFilter::FirstInlinedLine(
      "com/example/AKt", "A.kt", std::string(kSmapStart) + line_section);
}

TEST(KotlinInlineFilterSmapTest, FirstInlinedLineOfCompilerOutput) {
  EXPECT_EQ(KotlinInlineFilter::FirstInlinedLine("com/example/InlineKt",
                                                 "Inline.kt", kCompilerSmap),
            43);
}

// The class's own lines are mapped under its own name, also for inner classes,
// anonymous objects and companion objects.
TEST(KotlinInlineFilterSmapTest, FirstInlinedLineOfAnonymousObject) {
  EXPECT_EQ(KotlinInlineFilter::FirstInlinedLine(
                "com/example/RenamedFile$anonymousObject$1", "Classes.kt",
                kAnonymousObjectSmap),
            24);
}

TEST(KotlinInlineFilterSmapTest, NoInlinedLineWithoutSmap) {
  EXPECT_EQ(KotlinInlineFilter::FirstInlinedLine("com/example/AKt", "A.kt", ""),
            std::nullopt);
}

TEST(KotlinInlineFilterSmapTest, NoInlinedLineWithOnlyOwnLines) {
  EXPECT_EQ(FirstInlinedLineOfA("1#1,10:1\n*E\n"), std::nullopt);
}

TEST(KotlinInlineFilterSmapTest, InlinedLineFromOtherFile) {
  EXPECT_EQ(FirstInlinedLineOfA("1#1,10:1\n70#2:11\n*E\n"), 11);
}

// An inline function of the same file keeps its own line numbers in the
// mapping, but is moved to other output lines.
TEST(KotlinInlineFilterSmapTest, InlinedLineFromSameFile) {
  EXPECT_EQ(FirstInlinedLineOfA("1#1,10:1\n4#1,3:11\n*E\n"), 11);
}

TEST(KotlinInlineFilterSmapTest, SmallestInlinedLine) {
  EXPECT_EQ(FirstInlinedLineOfA("1#1,10:1\n70#2:20\n4#1,3:11\n71#2:15\n*E\n"),
            11);
}

// Any mapping of the class to the same lines is its own, not just the first.
TEST(KotlinInlineFilterSmapTest, SkipsAllOwnLines) {
  EXPECT_EQ(FirstInlinedLineOfA("1#1,10:1\n12#1:12\n70#2:30\n*E\n"), 30);
}

// Lines mapped to the same lines of another class are inlined code.
TEST(KotlinInlineFilterSmapTest, OwnLinesNeedClassName) {
  EXPECT_EQ(KotlinInlineFilter::FirstInlinedLine(
                "com/example/BKt", "A.kt",
                std::string(kSmapStart) + "1#1,10:1\n*E\n"),
            1);
}

TEST(KotlinInlineFilterSmapTest, MappingOfUnknownFileIsInlined) {
  EXPECT_EQ(FirstInlinedLineOfA("1#1,10:1\n7#9:11\n*E\n"), 11);
}

// The KotlinDebug stratum maps the inlined lines to their call sites.
TEST(KotlinInlineFilterSmapTest, IgnoresKotlinDebugStratum) {
  EXPECT_EQ(FirstInlinedLineOfA("1#1,10:1\n"
                                "*S KotlinDebug\n"
                                "*F\n"
                                "+ 1 A.kt\n"
                                "com/example/AKt\n"
                                "*L\n"
                                "5#1:2\n"
                                "*E\n"),
            std::nullopt);
}

TEST(KotlinInlineFilterSmapTest, ReadsOptionalCountsOfLineInfo) {
  EXPECT_EQ(FirstInlinedLineOfA("1#1,10:1\n70#2,3:11,2\n*E\n"), 11);
  // The output increment isn't needed, so it may be any number of digits.
  EXPECT_EQ(FirstInlinedLineOfA("1#1,10:1\n70#2:11,99999999999\n*E\n"), 11);
}

TEST(KotlinInlineFilterSmapTest, ReadsFileNameOfOneCharacter) {
  EXPECT_EQ(KotlinInlineFilter::FirstInlinedLine(
                "com/example/AKt", "A",
                "SMAP\nA\nKotlin\n*S Kotlin\n*F\n+ 1 A\ncom/example/AKt\n*L\n"
                "1#1,10:1\n4#1:11\n*E\n"),
            11);
}

TEST(KotlinInlineFilterSmapTest, AcceptsOtherLineEnds) {
  EXPECT_EQ(KotlinInlineFilter::FirstInlinedLine(
                "com/example/AKt", "A.kt",
                "SMAP\r\nA.kt\r\nKotlin\r\n*S Kotlin\r\n*F\r\n+ 1 A.kt\r\n"
                "com/example/AKt\r\n*L\r\n1#1,10:1\r\n4#1:11\r\n*E\r\n"),
            11);
  EXPECT_EQ(KotlinInlineFilter::FirstInlinedLine(
                "com/example/AKt", "A.kt",
                "SMAP\rA.kt\rKotlin\r*S Kotlin\r*F\r+ 1 A.kt\r"
                "com/example/AKt\r*L\r1#1,10:1\r4#1:11\r*E"),
            11);
}

TEST(KotlinInlineFilterSmapTest, NoInlinedLineWithMalformedSmap) {
  const std::string inlined = "1#1,10:1\n4#1:11\n*E\n";
  const std::string file_section =
      "*F\n+ 1 A.kt\ncom/example/AKt\n*L\n" + inlined;
  const std::string malformed[] = {
      // The header.
      "SMAPX\nA.kt\nKotlin\n*S Kotlin\n" + file_section,
      "SMAP\nB.kt\nKotlin\n*S Kotlin\n" + file_section,
      "SMAP\nA.kt\nJava\n*S Kotlin\n" + file_section,
      "SMAP\nA.kt\nKotlin\n*S KotlinDebug\n" + file_section,
      "SMAP\nA.kt\nKotlin\n*S Kotlin\n*L\n" + inlined,
      // The file section.
      "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ x A.kt\ncom/example/AKt\n*L\n" +
          inlined,
      "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1\ncom/example/AKt\n*L\n" + inlined,
      "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 \ncom/example/AKt\n*L\n" +
          inlined,
      "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n1 A.kt\ncom/example/AKt\n*L\n" +
          inlined,
      "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 A.kt\n",
      "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 A.kt\ncom/example/AKt\n",
      // The line section.
      std::string(kSmapStart) + "1,10:1\n*E\n",
      std::string(kSmapStart) + "1#1,10\n*E\n",
      std::string(kSmapStart) + "1#1:x\n*E\n",
      std::string(kSmapStart) + "1#1:5,\n*E\n",
      std::string(kSmapStart) + "a#1:5\n*E\n",
      std::string(kSmapStart) + "1#1:5 \n*E\n",
      std::string(kSmapStart) + "1#1:2147483648\n*E\n",
      std::string(kSmapStart) + "1#1,10:1\n4#1:11\n",
  };
  for (const std::string& smap : malformed) {
    SCOPED_TRACE(smap);
    EXPECT_EQ(
        KotlinInlineFilter::FirstInlinedLine("com/example/AKt", "A.kt", smap),
        std::nullopt);
  }
}

// Unit tests for KotlinInlineFilter on methods.
class KotlinInlineFilterTest : public FilterTestBase {
 protected:
  KotlinInlineFilterTest() {
    // A.kt has 10 lines. Its inline function on lines 4-6 is inlined on lines
    // 11-13, and Standard.kt's let() on line 14.
    context_.class_name = "Lcom/example/AKt;";
    context_.source_file = "A.kt";
    context_.smap =
        std::string(kSmapStart) + "1#1,10:1\n4#1,3:11\n70#2:14\n*E\n";
  }

  KotlinInlineFilter filter_;
};

// fun callsTwice(a: Int) = twice(a) { it + 1 }
TEST_F(KotlinInlineFilterTest, ShouldFilterInlinedCode) {
  TestMethod m("callsTwice", "(I)I");
  m.AddLine(8);  // The start line.
  m.AddLine(8);
  m.Add(dex::OP_MOVE, {m.Reg(0), m.Reg(1)});
  m.AddLine(11);
  m.Add(dex::OP_MUL_INT_LIT8, {m.Reg(0), m.Reg(0), m.Literal(2)});
  lir::Instruction* first = m.Last();
  m.AddLine(12);
  m.Add(dex::OP_ADD_INT_LIT8, {m.Reg(0), m.Reg(0), m.Literal(1)});
  lir::Instruction* last = m.Last();
  m.AddLine(8);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, last}});
}

// The lambda passed to an inline function stays on the lines of the caller.
TEST_F(KotlinInlineFilterTest, ShouldKeepLambdaOfInlinedCode) {
  TestMethod m("callsTwice", "(I)I");
  m.AddLine(8);  // The start line.
  m.AddLine(11);
  m.Add(dex::OP_MUL_INT_LIT8, {m.Reg(0), m.Reg(1), m.Literal(2)});
  lir::Instruction* body = m.Last();
  m.AddLine(8);
  m.Add(dex::OP_ADD_INT_LIT8, {m.Reg(0), m.Reg(0), m.Literal(1)});
  m.AddLine(12);
  m.Add(dex::OP_MUL_INT_LIT8, {m.Reg(0), m.Reg(0), m.Literal(2)});
  lir::Instruction* rest = m.Last();
  m.AddLine(8);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{body, body}, Range{rest, rest}});
}

TEST_F(KotlinInlineFilterTest, ShouldNotFilterLastLineOfFile) {
  TestMethod m("last", "()I");
  m.AddLine(10);  // The start line.
  m.AddLine(10);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.AddLine(11);
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  lir::Instruction* inlined = m.Last();

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{inlined, inlined}});
}

// Non-instructions between inlined instructions don't split their range, and
// the branches of inlined code are ignored with it.
TEST_F(KotlinInlineFilterTest, ShouldFilterInlinedBranch) {
  TestMethod m("callsLet", "(Ljava/lang/String;)I");
  lir::Label* otherwise = m.NewLabel();
  m.AddLine(8);  // The start line.
  m.AddLine(14);
  m.Add(dex::OP_IF_EQZ, {m.Reg(1), m.Target(otherwise)});
  lir::Instruction* first = m.Last();
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Place(otherwise);
  m.AddLine(13);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  lir::Instruction* last = m.Last();
  m.AddLine(8);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, last}});
}

TEST_F(KotlinInlineFilterTest, ShouldFilterInlinedCodeAtEndOfMethod) {
  TestMethod m("callsTwice", "(I)I");
  m.AddLine(8);  // The start line.
  m.AddLine(8);
  m.Add(dex::OP_MOVE, {m.Reg(0), m.Reg(1)});
  m.AddLine(13);
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  lir::Instruction* inlined = m.Last();

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{inlined, inlined}});
}

// Slicer adds the start line of the debug information as the annotation of a
// line, which is not a line number of the method. The code before the first
// line number is on line 0.
TEST_F(KotlinInlineFilterTest, ShouldSkipStartLine) {
  TestMethod m("f", "()V");
  m.AddLine(14);  // The start line.
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.AddLine(8);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinInlineFilterTest, ShouldNotFilterMethodWithoutLines) {
  TestMethod m("f", "()V");
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinInlineFilterTest, ShouldNotFilterWithoutSmap) {
  context_.smap.clear();
  TestMethod m("callsTwice", "(I)I");
  m.AddLine(8);  // The start line.
  m.AddLine(11);
  m.Add(dex::OP_MUL_INT_LIT8, {m.Reg(0), m.Reg(1), m.Literal(2)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinInlineFilterTest, ShouldNotFilterWithMalformedSmap) {
  context_.source_file = "B.kt";
  TestMethod m("callsTwice", "(I)I");
  m.AddLine(8);  // The start line.
  m.AddLine(11);
  m.Add(dex::OP_MUL_INT_LIT8, {m.Reg(0), m.Reg(1), m.Literal(2)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinInlineFilterTest, ShouldNotFilterWithoutInlinedCode) {
  context_.smap = std::string(kSmapStart) + "1#1,10:1\n*E\n";
  TestMethod m("f", "()I");
  m.AddLine(8);  // The start line.
  m.AddLine(11);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The SMAP maps the lines of the class by its name, without the "L" and ";" of
// its descriptor.
TEST_F(KotlinInlineFilterTest, ShouldMatchClassByName) {
  context_.class_name = "Lcom/example/BKt;";
  TestMethod m("f", "()I");
  m.AddLine(8);  // The start line.
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.AddLine(8);
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  lir::Instruction* on_line = m.Last();

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  // Lines 1-10 are then inlined code of AKt. The code before the first line
  // number is on line 0, before them.
  ExpectIgnored(m, {Range{on_line, on_line}});
}

}  // namespace
}  // namespace coverage
