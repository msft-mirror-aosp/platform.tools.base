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

// testdata/com/example/InstrumenterTestKotlinTarget.kt, compiled to dex.
extern "C" const unsigned char instrumenter_test_kotlin_target_dex[];
extern "C" const long long instrumenter_test_kotlin_target_dex_len;

namespace coverage {
namespace {

constexpr char kTargetClass[] = "Lcom/example/InstrumenterTestTarget;";
constexpr char kKotlinTargetClass[] =
    "Lcom/example/InstrumenterTestKotlinTarget;";

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
// The closing brace of the synchronized block of synchronizedBlock(), and the
// declaration of synchronizedMethod().
constexpr int kSynchronizedBlockEndLine = 102;
constexpr int kSynchronizedMethodLine = 107;

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

// Instruments the target classes once and returns their metadata.
const proto::CoverageMetadata& Metadata() {
  static const proto::CoverageMetadata* const metadata = [] {
    Instrumenter instrumenter(/*jvmti=*/nullptr, "com/example");
    instrumenter.AddUnloadedClasses(instrumenter_test_target_dex,
                                    instrumenter_test_target_dex_len);
    instrumenter.AddUnloadedClasses(instrumenter_test_kotlin_target_dex,
                                    instrumenter_test_kotlin_target_dex_len);
    return new proto::CoverageMetadata(
        MetadataCollector::Instance().BuildMetadata());
  }();
  return *metadata;
}

// Returns whether the metadata has the class `class_name`, even without
// methods.
bool HasClass(std::string_view class_name) {
  for (const auto& class_meta : Metadata().classes()) {
    if (class_meta.class_name() == class_name) {
      return true;
    }
  }
  return false;
}

// Returns the metadata of the method named `name` of `class_name`, with
// `signature` if given, or nullptr if that method was not instrumented.
const proto::MethodMetadata* InstrumentedMethod(
    std::string_view name, std::string_view class_name = kTargetClass,
    std::string_view signature = {}) {
  for (const auto& class_meta : Metadata().classes()) {
    if (class_meta.class_name() != class_name) {
      continue;
    }
    for (const auto& method : class_meta.methods()) {
      if (method.name() == name &&
          (signature.empty() || method.signature() == signature)) {
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

TEST(InstrumenterTest, DefaultArgumentsMethodIsInstrumented) {
  // greet$default checks whether to use the default value of `name`. The jump
  // of that check is ignored, and the default value is counted.
  const proto::MethodMetadata* method =
      InstrumentedMethod("greet$default", kKotlinTargetClass);
  ASSERT_NE(method, nullptr);
  uint32_t instructions = 0;
  for (const auto& block : method->blocks()) {
    EXPECT_LE(block.branch_count(), 1u);
    for (const auto& line : block.lines()) {
      instructions += line.instruction_count();
    }
  }
  // and-int/lit8, const-string, invoke-virtual, move-result-object and
  // return-object.
  EXPECT_EQ(instructions, 5u);
}

TEST(InstrumenterTest, OtherSyntheticMethodsAreSkipped) {
  // The other methods of the class are instrumented.
  ASSERT_NE(InstrumentedMethod("greet", kKotlinTargetClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("access$getCount$p", kKotlinTargetClass),
            nullptr);
}

// Returns the lines that `method` reports, and adds their instructions to
// `instructions`.
std::set<int32_t> ReportedLines(const proto::MethodMetadata& method,
                                uint32_t* instructions) {
  std::set<int32_t> lines;
  for (const auto& block : method.blocks()) {
    for (const auto& line : block.lines()) {
      lines.insert(line.line_number());
      *instructions += line.instruction_count();
    }
  }
  return lines;
}

// Returns the number of instructions that `method` reports on `line`.
uint32_t InstructionsOnLine(const proto::MethodMetadata& method, int32_t line) {
  uint32_t instructions = 0;
  for (const auto& block : method.blocks()) {
    for (const auto& block_line : block.lines()) {
      if (block_line.line_number() == line) {
        instructions += block_line.instruction_count();
      }
    }
  }
  return instructions;
}

TEST(InstrumenterTest, InlinedCodeIsNotReported) {
  // twice() is inlined into callsTwice() on lines after the end of the file.
  // Only the line of callsTwice(), which also holds the lambda, is reported.
  const proto::MethodMetadata* method =
      InstrumentedMethod("callsTwice", kKotlinTargetClass);
  ASSERT_NE(method, nullptr);
  uint32_t instructions = 0;
  std::set<int32_t> lines = ReportedLines(*method, &instructions);
  EXPECT_EQ(lines.size(), 1u);
  // iget, the const/4 that marks the start of the inlined function,
  // add-int/lit8 of the lambda and return.
  EXPECT_EQ(instructions, 4u);
}

TEST(InstrumenterTest, InlineFunctionIsInstrumented) {
  // The inline function itself is reported on its own line, before the line of
  // callsTwice().
  const proto::MethodMetadata* twice =
      InstrumentedMethod("twice", kKotlinTargetClass);
  const proto::MethodMetadata* calls_twice =
      InstrumentedMethod("callsTwice", kKotlinTargetClass);
  ASSERT_NE(twice, nullptr);
  ASSERT_NE(calls_twice, nullptr);
  uint32_t instructions = 0;
  std::set<int32_t> lines = ReportedLines(*twice, &instructions);
  std::set<int32_t> caller_lines = ReportedLines(*calls_twice, &instructions);
  ASSERT_EQ(lines.size(), 1u);
  ASSERT_EQ(caller_lines.size(), 1u);
  EXPECT_EQ(*lines.begin() + 2, *caller_lines.begin());
}

TEST(InstrumenterTest, ChecksOfSafeCallsShareNullBranch) {
  // The two checks of node?.next?.name and the check of the elvis operator all
  // have the code of `return 0` as their null branch, although D8 makes the
  // check of the elvis operator jump there through a goto.
  const proto::MethodMetadata* method =
      InstrumentedMethod("nameLength", kKotlinTargetClass);
  ASSERT_NE(method, nullptr);
  int checks = 0;
  std::set<uint32_t> shared;
  for (const auto& block : method->blocks()) {
    if (block.branch_count() != 2) {
      continue;
    }
    std::set<uint32_t> successors = Successors(block);
    EXPECT_EQ(successors.size(), 2u);
    if (checks++ == 0) {
      shared = successors;
      continue;
    }
    // Keep the successors that this check shares with the previous ones.
    for (auto it = shared.begin(); it != shared.end();) {
      if (successors.count(*it) == 0) {
        it = shared.erase(it);
      } else {
        ++it;
      }
    }
  }
  EXPECT_EQ(checks, 3);
  ASSERT_EQ(shared.size(), 1u);
  // The shared successor is the code of `return 0`.
  bool found = false;
  for (const auto& block : method->blocks()) {
    if (block.block_id() == *shared.begin()) {
      found = true;
      EXPECT_EQ(block.successor_block_ids_size(), 0);
    }
  }
  EXPECT_TRUE(found);
  // The goto after the check of the elvis operator only ran when the name was
  // null, which that branch now covers, so it isn't counted: 12 instructions
  // are left.
  uint32_t instructions = 0;
  ReportedLines(*method, &instructions);
  EXPECT_EQ(instructions, 12u);
}

TEST(InstrumenterTest, PrivateEmptyConstructorIsNotReported) {
  // The constructor has only one register, so the agent grows its frame and
  // copies `this` back after the call to the constructor of Object.
  constexpr char kDefaultsClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Defaults;";
  // The other methods of the object are instrumented.
  ASSERT_NE(InstrumentedMethod("name", kDefaultsClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("<init>", kDefaultsClass), nullptr);
}

// Returns the branch counts of the blocks of `method` that have branches.
std::vector<uint32_t> BranchCounts(const proto::MethodMetadata& method) {
  std::vector<uint32_t> counts;
  for (const auto& block : method.blocks()) {
    if (block.branch_count() > 1) {
      counts.push_back(block.branch_count());
    }
  }
  return counts;
}

TEST(InstrumenterTest, ImpossibleWhenBranchesAreNotReported) {
  // The check of the last case of sides() jumps to the throw of
  // NoWhenBranchMatchedException, which can't run, so only the check of the
  // first case has branches.
  const proto::MethodMetadata* sides =
      InstrumentedMethod("sides", kKotlinTargetClass);
  ASSERT_NE(sides, nullptr);
  EXPECT_EQ(BranchCounts(*sides), std::vector<uint32_t>{2});
  // The switch of severity() has one branch per case, null included, and the
  // null check before it has none. The successors of the switch are the
  // blocks of the cases, which are reported, unlike the block of the throw.
  const proto::MethodMetadata* severity =
      InstrumentedMethod("severity", kKotlinTargetClass);
  ASSERT_NE(severity, nullptr);
  EXPECT_EQ(BranchCounts(*severity), std::vector<uint32_t>{4});
  std::set<uint32_t> reported;
  for (const auto& block : severity->blocks()) {
    reported.insert(block.block_id());
  }
  for (const auto& block : severity->blocks()) {
    if (block.branch_count() != 4) {
      continue;
    }
    std::set<uint32_t> successors = Successors(block);
    EXPECT_EQ(successors.size(), 4u);
    for (uint32_t id : successors) {
      EXPECT_EQ(reported.count(id), 1u);
    }
  }
}

TEST(InstrumenterTest, NullChecksThatShareMinusOneHaveNoBranches) {
  // D8 loads the -1 of both null checks of severities() only once. Each switch
  // has one branch per case, null included, and the null checks have none.
  const proto::MethodMetadata* method =
      InstrumentedMethod("severities", kKotlinTargetClass);
  ASSERT_NE(method, nullptr);
  EXPECT_EQ(BranchCounts(*method), (std::vector<uint32_t>{4, 4}));
}

TEST(InstrumenterTest, InlinedWhenHasNoBranches) {
  // The inlined switch ends the block of the read of `count`. Its branches,
  // which the Kotlin when filter replaces, aren't reported, since the Kotlin
  // inline filter ignores it.
  const proto::MethodMetadata* method =
      InstrumentedMethod("callsLevel", kKotlinTargetClass);
  ASSERT_NE(method, nullptr);
  EXPECT_TRUE(BranchCounts(*method).empty());
  // The inline function itself has one branch per case.
  const proto::MethodMetadata* level =
      InstrumentedMethod("level", kKotlinTargetClass);
  ASSERT_NE(level, nullptr);
  EXPECT_EQ(BranchCounts(*level), std::vector<uint32_t>{3});
}

TEST(InstrumenterTest, SwitchCountsEachTargetOnce) {
  // The switch of sharedCases() has a branch for its default and one for each
  // other code that its cases go to: cases that share their code or go to the
  // default add none.
  const proto::MethodMetadata* method = InstrumentedMethod("sharedCases");
  ASSERT_NE(method, nullptr);
  EXPECT_EQ(BranchCounts(*method), std::vector<uint32_t>{3});
}

TEST(InstrumenterTest, SyntheticBridgeIsNotReported) {
  // The method that the bridge of `increment` calls is instrumented, but not
  // the bridge, although the agent instruments synthetic methods whose names
  // contain invoke.
  constexpr char kIncrementClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$increment$1;";
  ASSERT_NE(
      InstrumentedMethod("invoke", kIncrementClass, "(I)Ljava/lang/Integer;"),
      nullptr);
  EXPECT_EQ(InstrumentedMethod("invoke", kIncrementClass,
                               "(Ljava/lang/Object;)Ljava/lang/Object;"),
            nullptr);
}

TEST(InstrumenterTest, BridgeThatIsNotSyntheticIsNotReported) {
  constexpr char kTopicClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Topic;";
  // The other method of the class, its constructor, is instrumented.
  ASSERT_NE(InstrumentedMethod("<init>", kTopicClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("label", kTopicClass), nullptr);
}

TEST(InstrumenterTest, BridgeOfJavaClassIsNotReported) {
  // Bridges are filtered in Java classes too, such as the synthetic bridge
  // named invoke of Constant.
  constexpr char kConstantClass[] =
      "Lcom/example/InstrumenterTestTarget$Constant;";
  ASSERT_NE(
      InstrumentedMethod("invoke", kConstantClass, "()Ljava/lang/String;"),
      nullptr);
  EXPECT_EQ(
      InstrumentedMethod("invoke", kConstantClass, "()Ljava/lang/Object;"),
      nullptr);
}

TEST(InstrumenterTest, SyntheticAccessorIsNotReported) {
  // The private invoke() is instrumented, but not its accessor, although the
  // agent instruments synthetic methods whose names contain invoke.
  ASSERT_NE(InstrumentedMethod("invoke", kKotlinTargetClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("access$invoke", kKotlinTargetClass), nullptr);
}

TEST(InstrumenterTest, MethodOfDefaultImplsIsNotReported) {
  constexpr char kCompanionClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Labeled$-CC;";
  constexpr char kDefaultImplsClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Labeled$DefaultImpls;";
  // The default code of label(), which D8 moves to a companion class when it
  // desugars default methods, is instrumented.
  ASSERT_NE(InstrumentedMethod("$default$label", kCompanionClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("label", kDefaultImplsClass), nullptr);
}

TEST(InstrumenterTest, SerializerOfCompanionObjectIsNotReported) {
  constexpr char kMessageClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Message;";
  constexpr char kMessageCompanionClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Message$Companion;";
  // The code of the class itself is instrumented, and only the plugin gives it
  // a companion object.
  ASSERT_NE(InstrumentedMethod("getText", kMessageClass), nullptr);
  ASSERT_TRUE(HasClass(kMessageCompanionClass));
  EXPECT_EQ(InstrumentedMethod("serializer", kMessageCompanionClass), nullptr);
}

TEST(InstrumenterTest, SerializerOfCompanionObjectOfSealedClassIsNotReported) {
  // Only the plugin gives the sealed class a companion object, whose
  // serializer() returns get$cachedSerializer().
  constexpr char kEventCompanionClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Event$Companion;";
  ASSERT_TRUE(HasClass(kEventCompanionClass));
  EXPECT_EQ(InstrumentedMethod("serializer", kEventCompanionClass), nullptr);
}

TEST(InstrumenterTest, SerializerOfObjectIsNotReported) {
  constexpr char kPingClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Ping;";
  // The other code of the object is instrumented.
  ASSERT_NE(InstrumentedMethod("<clinit>", kPingClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("serializer", kPingClass), nullptr);
}

TEST(InstrumenterTest, EmptyEnumConstructorIsNotReported) {
  // The constructor uses all its registers for `this`, the name and the
  // ordinal, so the agent grows its frame and copies them back after the call
  // to the constructor of Enum.
  constexpr char kSizeClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Size;";
  // The other code of the enum is instrumented.
  ASSERT_NE(InstrumentedMethod("<clinit>", kSizeClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("<init>", kSizeClass), nullptr);
}

TEST(InstrumenterTest, EmptyConstructorOfJavaEnumIsNotReported) {
  // Empty enum constructors are filtered in Java classes too.
  constexpr char kColorClass[] = "Lcom/example/InstrumenterTestTarget$Color;";
  ASSERT_NE(InstrumentedMethod("<clinit>", kColorClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("<init>", kColorClass), nullptr);
}

TEST(InstrumenterTest, HandlerOfSynchronizedBlockIsNotReported) {
  // javac puts the handler on the closing brace of the block, which then only
  // reports the monitor exit of the normal path.
  const proto::MethodMetadata* method = InstrumentedMethod("synchronizedBlock");
  ASSERT_NE(method, nullptr);
  EXPECT_EQ(InstructionsOnLine(*method, kSynchronizedBlockEndLine), 1u);
}

TEST(InstrumenterTest, HandlerOfSynchronizedMethodIsNotReported) {
  // D8 puts the handler of a synchronized method on the line before its first
  // statement, here its declaration, which has no other code.
  const proto::MethodMetadata* method =
      InstrumentedMethod("synchronizedMethod");
  ASSERT_NE(method, nullptr);
  uint32_t instructions = 0;
  std::set<int32_t> lines = ReportedLines(*method, &instructions);
  EXPECT_FALSE(lines.empty());
  EXPECT_EQ(lines.count(kSynchronizedMethodLine), 0u);
}

TEST(InstrumenterTest, HandlerOfKotlinSynchronizedBlockIsNotReported) {
  // Kotlin puts the handler on the line of the call to synchronized(), which
  // then reports the monitor enter, the read of `count`, the monitor exit and
  // the return. The check of the parameter comes before the first line, and
  // the marker of the inlined block is on a line after the end of the file,
  // so other filters ignore them.
  const proto::MethodMetadata* method =
      InstrumentedMethod("synchronizedBlock", kKotlinTargetClass);
  ASSERT_NE(method, nullptr);
  uint32_t instructions = 0;
  EXPECT_EQ(ReportedLines(*method, &instructions).size(), 1u);
  EXPECT_EQ(instructions, 4u);
}

TEST(InstrumenterTest, InstanceMethodsOfValueClassAreNotReported) {
  constexpr char kMetersClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$Meters;";
  // The static version of toString() is instrumented.
  ASSERT_NE(InstrumentedMethod("toString-impl", kMetersClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("getValue", kMetersClass), nullptr);
  EXPECT_EQ(InstrumentedMethod("toString", kMetersClass), nullptr);
}

TEST(InstrumenterTest, MethodOfSyntheticClassIsNotReported) {
  // Kotlin compiles the reference to a property into a synthetic class, whose
  // get() calls the getter of the property.
  constexpr char kReferenceClass[] =
      "Lcom/example/InstrumenterTestKotlinTarget$nameOf$1;";
  ASSERT_TRUE(HasClass(kReferenceClass));
  EXPECT_EQ(InstrumentedMethod("get", kReferenceClass), nullptr);
}

TEST(InstrumenterTest, MethodOfJavaSyntheticClassIsNotReported) {
  // javac fills the table of the switch on TimeUnit in the static initializer
  // of a synthetic class.
  constexpr char kSwitchMapClass[] = "Lcom/example/InstrumenterTestTarget$1;";
  ASSERT_TRUE(HasClass(kSwitchMapClass));
  EXPECT_EQ(InstrumentedMethod("<clinit>", kSwitchMapClass), nullptr);
}

TEST(InstrumenterTest, SyntheticMethodOfJavaClassIsNotReported) {
  constexpr char kCompanionClass[] =
      "Lcom/example/InstrumenterTestTarget$Command$-CC;";
  constexpr char kDefaultCommandClass[] =
      "Lcom/example/InstrumenterTestTarget$DefaultCommand;";
  // The code of the default method is instrumented in the companion class,
  // but not the method that D8 adds to DefaultCommand to call it, although the
  // agent instruments synthetic methods whose names contain invoke.
  ASSERT_NE(InstrumentedMethod("$default$invoke", kCompanionClass), nullptr);
  ASSERT_TRUE(HasClass(kDefaultCommandClass));
  EXPECT_EQ(InstrumentedMethod("invoke", kDefaultCommandClass), nullptr);
}

TEST(InstrumenterTest, BodiesOfJavaLambdasAreInstrumented) {
  constexpr char kCompanionClass[] =
      "Lcom/example/InstrumenterTestTarget$Command$-CC;";
  // The body of a lambda is a synthetic method of its class. D8 renames the
  // body of the lambda of a default method when it moves it to the companion
  // class.
  EXPECT_NE(InstrumentedMethod("lambda$userAction$0"), nullptr);
  EXPECT_NE(InstrumentedMethod("$private$lambda$later$0", kCompanionClass),
            nullptr);
}

}  // namespace
}  // namespace coverage
