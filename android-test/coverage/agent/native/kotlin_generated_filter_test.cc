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

#include "tools/base/android-test/coverage/agent/native/kotlin_generated_filter.h"

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

// Unit tests for KotlinGeneratedFilter.
class KotlinGeneratedFilterTest : public FilterTestBase {
 protected:
  KotlinGeneratedFilterTest() { context_.source_file = "Example.kt"; }

  KotlinGeneratedFilter filter_;
};

// data class Point(val x: Int)
// Its hashCode() has no line numbers, so D8 gives it no debug information.
TEST_F(KotlinGeneratedFilterTest, ShouldFilterMethodWithoutLines) {
  TestMethod m("hashCode", "()I");
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  lir::Instruction* first = m.Last();
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// Debug information without line numbers still has a start line, which slicer
// adds as the annotation of a line.
TEST_F(KotlinGeneratedFilterTest, ShouldFilterMethodWithOnlyStartLine) {
  TestMethod m("hashCode", "()I");
  m.AddLine(12);
  lir::Instruction* first = m.Last();
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

TEST_F(KotlinGeneratedFilterTest, ShouldNotFilterMethodWithLines) {
  TestMethod m("hashCode", "()I");
  m.AddLine(12);  // The start line.
  m.AddLine(12);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Without a source file, the class was probably compiled without debug
// information.
TEST_F(KotlinGeneratedFilterTest,
       ShouldNotFilterMethodWithoutLinesWithoutSourceFile) {
  context_.source_file.clear();
  TestMethod m("hashCode", "()I");
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

TEST_F(KotlinGeneratedFilterTest,
       ShouldNotFilterCodeBeforeFirstLineWithoutSourceFile) {
  context_.source_file.clear();
  TestMethod m("hashCode", "()I");
  m.AddLine(12);  // The start line.
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.AddLine(12);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// inline fun f() {}
// The marker of the inline function comes before its first line number.
TEST_F(KotlinGeneratedFilterTest, ShouldFilterCodeBeforeFirstLine) {
  TestMethod m("f", "()V");
  m.AddLine(1);  // The start line.
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  lir::Instruction* marker = m.Last();
  m.AddLine(1);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{marker, marker}});
}

// The same method, disassembled by slicer from its debug information.
TEST_F(KotlinGeneratedFilterTest, ShouldSkipStartLineAddedBySlicer) {
  // Adds a line number one code unit further, on the same line.
  constexpr dex::u1 kNextCodeUnit =
      dex::DBG_FIRST_SPECIAL - dex::DBG_LINE_BASE + dex::DBG_LINE_RANGE;
  TestMethod m("f", "()V",
               /*bytecode=*/
               {
                   0x0012,  // const/4 v0, #0
                   0x000e,  // return-void
               },
               /*line_start=*/1,
               /*debug_info=*/{kNextCodeUnit, dex::DBG_END_SEQUENCE});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  lir::Instruction* marker = nullptr;
  int lines = 0;
  for (auto* instr : m.code_ir().instructions) {
    if (marker == nullptr && dynamic_cast<lir::Bytecode*>(instr) != nullptr) {
      marker = instr;
    }
    auto* annotation = dynamic_cast<lir::DbgInfoAnnotation*>(instr);
    if (annotation != nullptr &&
        annotation->dbg_opcode == dex::DBG_ADVANCE_LINE) {
      ++lines;
    }
  }
  // The start line and the line number.
  EXPECT_EQ(lines, 2);
  ExpectIgnored(m, {Range{marker, marker}});
}

}  // namespace
}  // namespace coverage
