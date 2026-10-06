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

#include "tools/base/android-test/coverage/agent/native/instrumenter.h"

#include <cstdint>
#include <set>
#include <string_view>
#include <vector>

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"
#include "tools/base/android-test/coverage/agent/native/metadata_collector.h"

// testdata/com/example/InstrumenterTestTarget.java, compiled to dex.
extern "C" const unsigned char instrumenter_test_target_dex[];
extern "C" const long long instrumenter_test_target_dex_len;

namespace coverage {
namespace {

constexpr char kTargetClass[] = "Lcom/example/InstrumenterTestTarget;";

// Lines of InstrumenterTestTarget.java.
constexpr int kIgnoredCallGeneratedLine = 31;
constexpr int kIgnoredCallUserLine = 32;
constexpr int kIgnoredBlockIfLine = 36;
constexpr int kIgnoredBlockGeneratedLine = 37;
constexpr int kIgnoredBlockUserLine = 39;
constexpr int kIgnoredBranchUserLine = 43;
constexpr int kReplacedBranchesIfLine = 50;
constexpr int kReplacedBranchesThenLine = 51;
constexpr int kReplacedBranchesElseLine = 53;
constexpr int kReplacedBranchesNextLine = 55;

// Returns the calls in `code_ir` to the static method named `name`.
std::vector<lir::Instruction*> CallsTo(const lir::CodeIr& code_ir,
                                       std::string_view name) {
  std::vector<lir::Instruction*> calls;
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    if (bytecode == nullptr || bytecode->opcode != dex::OP_INVOKE_STATIC) {
      continue;
    }
    for (auto* operand : bytecode->operands) {
      auto* method = dynamic_cast<lir::Method*>(operand);
      if (method != nullptr && name == method->ir_method->name->c_str()) {
        calls.push_back(bytecode);
      }
    }
  }
  return calls;
}

// Returns the first if-* instruction in `code_ir`, or nullptr.
lir::Instruction* FirstIf(const lir::CodeIr& code_ir) {
  for (auto* instr : code_ir.instructions) {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(instr);
    if (bytecode != nullptr && bytecode->opcode >= dex::OP_IF_EQ &&
        bytecode->opcode <= dex::OP_IF_LEZ) {
      return bytecode;
    }
  }
  return nullptr;
}

// Filters the methods of the target class as their names say.
class TestFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override {
    if (context.class_name != kTargetClass) {
      return;
    }
    std::string_view name = ir_method->decl->name->c_str();
    if (name == "ignoredCall" || name == "ignoredBlock") {
      for (auto* call : CallsTo(code_ir, "generated")) {
        output.Ignore(call, call);
      }
    } else if (name == "ignoredBranch") {
      lir::Instruction* branch = FirstIf(code_ir);
      output.Ignore(branch, branch);
    } else if (name == "replacedBranches") {
      output.ReplaceBranches(FirstIf(code_ir), CallsTo(code_ir, "user"));
    } else if (name == "allIgnored") {
      lir::Instruction* first = nullptr;
      lir::Instruction* last = nullptr;
      for (auto* instr : code_ir.instructions) {
        if (first == nullptr) {
          first = instr;
        }
        last = instr;
      }
      output.Ignore(first, last);
    }
  }
};

REGISTER_NON_KOTLIN_FILTER(TestFilter)

// Instruments the target class once. Returns the metadata of its method named
// `name`, or nullptr if that method was not instrumented.
const proto::MethodMetadata* InstrumentedMethod(std::string_view name) {
  static const proto::CoverageMetadata* const metadata = [] {
    Instrumenter instrumenter(/*jvmti=*/nullptr, "com/example");
    instrumenter.AddUnloadedClasses(instrumenter_test_target_dex,
                                    instrumenter_test_target_dex_len);
    return new proto::CoverageMetadata(
        MetadataCollector::Instance().BuildMetadata());
  }();
  for (const auto& class_meta : metadata->classes()) {
    if (class_meta.class_name() != kTargetClass) {
      continue;
    }
    for (const auto& method : class_meta.methods()) {
      if (method.name() == name) {
        return &method;
      }
    }
  }
  return nullptr;
}

// Returns the block of `method` that reports `line`, or nullptr.
const proto::BlockMetadata* BlockAt(const proto::MethodMetadata& method,
                                    int line) {
  for (const auto& block : method.blocks()) {
    for (const auto& line_meta : block.lines()) {
      if (line_meta.line_number() == line) {
        return &block;
      }
    }
  }
  return nullptr;
}

// Returns the successor block IDs of `block`.
std::set<uint32_t> Successors(const proto::BlockMetadata& block) {
  return {block.successor_block_ids().begin(),
          block.successor_block_ids().end()};
}

TEST(InstrumenterTest, IgnoredInstructionIsNotCounted) {
  const proto::MethodMetadata* method = InstrumentedMethod("ignoredCall");
  ASSERT_NE(method, nullptr);
  EXPECT_EQ(BlockAt(*method, kIgnoredCallGeneratedLine), nullptr);
  EXPECT_NE(BlockAt(*method, kIgnoredCallUserLine), nullptr);
}

TEST(InstrumenterTest, FullyIgnoredBlockIsProbedButNotReported) {
  const proto::MethodMetadata* method = InstrumentedMethod("ignoredBlock");
  ASSERT_NE(method, nullptr);
  const proto::BlockMetadata* condition = BlockAt(*method, kIgnoredBlockIfLine);
  const proto::BlockMetadata* next = BlockAt(*method, kIgnoredBlockUserLine);
  ASSERT_NE(condition, nullptr);
  ASSERT_NE(next, nullptr);

  // The block of the call to generated() is not reported...
  EXPECT_EQ(method->blocks_size(), 2);
  EXPECT_EQ(BlockAt(*method, kIgnoredBlockGeneratedLine), nullptr);

  // ...but it is still probed, so the if keeps its branch to it: the successor
  // that is neither `condition` nor `next`.
  EXPECT_EQ(condition->branch_count(), 2u);
  std::set<uint32_t> successors = Successors(*condition);
  EXPECT_EQ(successors.size(), 2u);
  EXPECT_EQ(successors.count(next->block_id()), 1u);
  EXPECT_EQ(successors.count(condition->block_id()), 0u);
}

TEST(InstrumenterTest, IgnoredBranchCountsAsOneBranch) {
  const proto::MethodMetadata* method = InstrumentedMethod("ignoredBranch");
  ASSERT_NE(method, nullptr);
  // The block of the first call to user() ends with the ignored if.
  const proto::BlockMetadata* block = BlockAt(*method, kIgnoredBranchUserLine);
  ASSERT_NE(block, nullptr);
  EXPECT_EQ(block->branch_count(), 1u);
}

TEST(InstrumenterTest, ReplacedBranchesSetBranchesAndSuccessors) {
  const proto::MethodMetadata* method = InstrumentedMethod("replacedBranches");
  ASSERT_NE(method, nullptr);
  const proto::BlockMetadata* condition =
      BlockAt(*method, kReplacedBranchesIfLine);
  const proto::BlockMetadata* then_block =
      BlockAt(*method, kReplacedBranchesThenLine);
  const proto::BlockMetadata* else_block =
      BlockAt(*method, kReplacedBranchesElseLine);
  const proto::BlockMetadata* next =
      BlockAt(*method, kReplacedBranchesNextLine);
  ASSERT_NE(condition, nullptr);
  ASSERT_NE(then_block, nullptr);
  ASSERT_NE(else_block, nullptr);
  ASSERT_NE(next, nullptr);

  // The two branches of the if are replaced with one to each call to user().
  EXPECT_EQ(condition->branch_count(), 3u);
  EXPECT_EQ(Successors(*condition),
            (std::set<uint32_t>{then_block->block_id(), else_block->block_id(),
                                next->block_id()}));
}

TEST(InstrumenterTest, MethodWithNothingToReportIsSkipped) {
  // The other methods of the class are instrumented.
  ASSERT_NE(InstrumentedMethod("user"), nullptr);
  EXPECT_EQ(InstrumentedMethod("allIgnored"), nullptr);
}

}  // namespace
}  // namespace coverage
