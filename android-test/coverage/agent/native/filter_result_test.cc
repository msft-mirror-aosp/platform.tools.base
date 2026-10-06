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

#include <string>
#include <vector>

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/control_flow_graph.h"
#include "slicer/dex_ir.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

namespace coverage {
namespace {

// Links `instrs` into a doubly linked chain, in order.
void Link(const std::vector<lir::Bytecode*>& instrs) {
  for (size_t i = 0; i < instrs.size(); ++i) {
    instrs[i]->prev = (i == 0) ? nullptr : instrs[i - 1];
    instrs[i]->next = (i + 1 < instrs.size()) ? instrs[i + 1] : nullptr;
  }
}

TEST(FilterResultTest, IgnoreMarksInclusiveRange) {
  lir::Bytecode a, b, c, d;
  Link({&a, &b, &c, &d});

  FilterResult result;
  result.Ignore(&b, &c);

  EXPECT_FALSE(result.IsIgnored(&a));
  EXPECT_TRUE(result.IsIgnored(&b));
  EXPECT_TRUE(result.IsIgnored(&c));
  EXPECT_FALSE(result.IsIgnored(&d));
}

TEST(FilterResultTest, IgnoreSingleInstruction) {
  lir::Bytecode a, b, c;
  Link({&a, &b, &c});

  FilterResult result;
  result.Ignore(&b, &b);

  EXPECT_FALSE(result.IsIgnored(&a));
  EXPECT_TRUE(result.IsIgnored(&b));
  EXPECT_FALSE(result.IsIgnored(&c));
}

TEST(FilterResultTest, IgnoreStopsAtEndOfCode) {
  // `to` precedes `from`, so the walk terminates at the end of the code
  // instead of looping.
  lir::Bytecode a, b, c;
  Link({&a, &b, &c});

  FilterResult result;
  result.Ignore(&b, &a);

  EXPECT_FALSE(result.IsIgnored(&a));
  EXPECT_TRUE(result.IsIgnored(&b));
  EXPECT_TRUE(result.IsIgnored(&c));
}

TEST(FilterResultTest, ReplaceBranchesIsLookedUpBySource) {
  lir::Bytecode source, target1, target2, other;

  FilterResult result;
  result.ReplaceBranches(&source, {&target1, &target2});

  const auto* targets = result.GetReplacedBranches(&source);
  ASSERT_NE(targets, nullptr);
  ASSERT_EQ(targets->size(), 2u);
  EXPECT_EQ((*targets)[0], &target1);
  EXPECT_EQ((*targets)[1], &target2);
  EXPECT_EQ(result.GetReplacedBranches(&other), nullptr);
}

TEST(FilterResultTest, SuppressBranchIsLookedUpByInstruction) {
  lir::Bytecode branch, other;

  FilterResult result;
  result.SuppressBranch(&branch);

  EXPECT_TRUE(result.IsBranchSuppressed(&branch));
  EXPECT_FALSE(result.IsBranchSuppressed(&other));
}

TEST(FilterContextTest, KotlinClassIsDetectedByMetadataAnnotation) {
  FilterContext context;
  EXPECT_FALSE(context.IsKotlinClass());

  context.class_annotations.insert("Lkotlin/Metadata;");
  EXPECT_TRUE(context.IsKotlinClass());
}

TEST(SyntheticFilterTest, SelectFilterSetsDependsOnKotlinMetadata) {
  FilterContext java_context;
  EXPECT_EQ(
      SyntheticFilter::SelectFilterSets(java_context),
      (std::vector<FilterSet>{FilterSet::kCommon, FilterSet::kNonKotlin}));

  FilterContext kotlin_context;
  kotlin_context.class_annotations.insert(kKotlinMetadataDescriptor);
  EXPECT_EQ(SyntheticFilter::SelectFilterSets(kotlin_context),
            (std::vector<FilterSet>{FilterSet::kCommon, FilterSet::kKotlin}));
}

// Ignores one captured instruction through Filter() and suppresses every
// OP_IF_NEZ through the legacy FilterBranch() hook.
class TestFilter : public IFilter {
 public:
  explicit TestFilter(lir::Instruction* to_ignore) : to_ignore_(to_ignore) {}

  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override {
    seen_class_name_ = context.class_name;
    output.Ignore(to_ignore_, to_ignore_);
  }

  bool FilterBranch(ir::EncodedMethod* ir_method,
                    lir::Instruction* branch_instr) override {
    auto* bytecode = dynamic_cast<lir::Bytecode*>(branch_instr);
    return bytecode != nullptr && bytecode->opcode == dex::OP_IF_NEZ;
  }

  const std::string& seen_class_name() const { return seen_class_name_; }

 private:
  lir::Instruction* to_ignore_;
  std::string seen_class_name_;
};

TEST(SyntheticFilterTest, ApplyCombinesFilterAndLegacyFilterBranch) {
  ir::EncodedMethod method;
  method.code = nullptr;
  lir::CodeIr code_ir(&method, nullptr);

  lir::Bytecode first, middle, branch;
  first.opcode = dex::OP_CONST;
  middle.opcode = dex::OP_INVOKE_STATIC;
  branch.opcode = dex::OP_IF_NEZ;
  Link({&first, &middle, &branch});

  std::vector<lir::BasicBlock> blocks(1);
  blocks[0].id = 1;
  blocks[0].region.first = &first;
  blocks[0].region.last = &branch;

  FilterContext context;
  context.class_name = "Lcom/example/Foo;";

  TestFilter filter(&middle);
  std::vector<IFilter*> filters = {&filter};
  FilterResult result =
      SyntheticFilter::Apply(filters, context, &method, code_ir, blocks);

  EXPECT_EQ(filter.seen_class_name(), "Lcom/example/Foo;");
  EXPECT_FALSE(result.IsIgnored(&first));
  EXPECT_TRUE(result.IsIgnored(&middle));
  EXPECT_FALSE(result.IsIgnored(&branch));
  EXPECT_TRUE(result.IsBranchSuppressed(&branch));
  EXPECT_FALSE(result.IsBranchSuppressed(&first));
  EXPECT_EQ(result.GetReplacedBranches(&branch), nullptr);
}

TEST(SyntheticFilterTest, ApplySkipsLegacyHookForNonBranchTerminator) {
  ir::EncodedMethod method;
  method.code = nullptr;
  lir::CodeIr code_ir(&method, nullptr);

  lir::Bytecode first, last;
  first.opcode = dex::OP_IF_NEZ;  // Not a terminator: must not be consulted.
  last.opcode = dex::OP_RETURN_VOID;
  Link({&first, &last});

  std::vector<lir::BasicBlock> blocks(1);
  blocks[0].id = 1;
  blocks[0].region.first = &first;
  blocks[0].region.last = &last;

  TestFilter filter(&last);
  std::vector<IFilter*> filters = {&filter};
  FilterResult result = SyntheticFilter::Apply(filters, FilterContext(),
                                               &method, code_ir, blocks);

  EXPECT_TRUE(result.IsIgnored(&last));
  EXPECT_FALSE(result.IsBranchSuppressed(&first));
  EXPECT_FALSE(result.IsBranchSuppressed(&last));
}

TEST(SyntheticFilterTest, ApplyWithNullMethodReturnsEmptyResult) {
  ir::EncodedMethod method;
  method.code = nullptr;
  lir::CodeIr code_ir(&method, nullptr);

  lir::Bytecode branch;
  branch.opcode = dex::OP_IF_NEZ;

  std::vector<lir::BasicBlock> blocks(1);
  blocks[0].id = 1;
  blocks[0].region.first = &branch;
  blocks[0].region.last = &branch;

  TestFilter filter(&branch);
  std::vector<IFilter*> filters = {&filter};
  FilterResult result = SyntheticFilter::Apply(filters, FilterContext(),
                                               nullptr, code_ir, blocks);

  EXPECT_FALSE(result.IsIgnored(&branch));
  EXPECT_FALSE(result.IsBranchSuppressed(&branch));
}

// Builds `invoke-static getCOROUTINE_SUSPENDED; move-result-object v0;
// if-nez v0` and runs the registered filters through the context-driven
// entry point, exercising the registry path end to end.
TEST(SyntheticFilterTest, ApplyWithContextRunsRegisteredFilters) {
  ir::EncodedMethod method;
  ir::Code code;
  method.code = &code;
  method.decl = nullptr;
  // Disassemble() must not run on the fake ir::Code, so construct CodeIr with
  // a null code pointer and restore it afterwards.
  method.code = nullptr;
  lir::CodeIr code_ir(&method, nullptr);
  method.code = &code;

  ir::String parent_descriptor;
  parent_descriptor.data = slicer::MemView(
      "\x2bLkotlin/coroutines/intrinsics/IntrinsicsKt;",
      sizeof("\x2bLkotlin/coroutines/intrinsics/IntrinsicsKt;"));
  ir::Type parent_type;
  parent_type.descriptor = &parent_descriptor;

  ir::String method_name;
  method_name.data = slicer::MemView("\x16getCOROUTINE_SUSPENDED",
                                     sizeof("\x16getCOROUTINE_SUSPENDED"));
  ir::MethodDecl callee;
  callee.parent = &parent_type;
  callee.name = &method_name;
  callee.prototype = nullptr;
  lir::Method method_operand(&callee, 0);

  lir::Bytecode invoke;
  invoke.opcode = dex::OP_INVOKE_STATIC;
  invoke.operands.push_back(&method_operand);

  lir::VReg dest_vreg(0);
  lir::Bytecode move_result;
  move_result.opcode = dex::OP_MOVE_RESULT_OBJECT;
  move_result.operands.push_back(&dest_vreg);

  lir::VReg cmp_vreg(0);
  lir::Bytecode branch;
  branch.opcode = dex::OP_IF_NEZ;
  branch.operands.push_back(&cmp_vreg);

  Link({&invoke, &move_result, &branch});

  std::vector<lir::BasicBlock> blocks(1);
  blocks[0].id = 1;
  blocks[0].region.first = &invoke;
  blocks[0].region.last = &branch;

  FilterContext context;
  context.class_name = "Lcom/example/Foo$1;";
  context.class_annotations.insert(kKotlinMetadataDescriptor);

  FilterResult result =
      SyntheticFilter::Apply(context, &method, code_ir, blocks);

  EXPECT_TRUE(result.IsBranchSuppressed(&branch));
  EXPECT_FALSE(result.IsIgnored(&invoke));
}

}  // namespace
}  // namespace coverage
