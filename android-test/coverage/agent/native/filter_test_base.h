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

#ifndef COVERAGE_AGENT_FILTER_TEST_BASE_H_
#define COVERAGE_AGENT_FILTER_TEST_BASE_H_

#include <memory>
#include <string>
#include <vector>

#include <gtest/gtest.h>
#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_ir.h"
#include "slicer/dex_ir_builder.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

// Descriptor of the class that the methods under test belong to.
inline constexpr char kTestClassName[] = "LFoo;";

// A method of kTestClassName whose code is built instruction by instruction,
// to test filters on exact bytecode shapes.
class TestMethod {
 public:
  // Creates an empty method with the given name and JNI signature, for example
  // "(Ljava/lang/String;)V".
  TestMethod(const char* name, const char* signature);

  TestMethod(const TestMethod&) = delete;
  TestMethod& operator=(const TestMethod&) = delete;

  // Appends a bytecode and returns it.
  lir::Bytecode* Add(dex::Opcode opcode,
                     std::vector<lir::Operand*> operands = {});

  // Returns a new label, which Place() appends.
  lir::Label* NewLabel();

  // Appends `label`.
  void Place(lir::Label* label);

  // Appends the payload of a packed-switch: the cases from `first_key` on jump
  // to `targets`. Place a label right before it for the switch to refer to.
  void AddPackedSwitchPayload(dex::s4 first_key,
                              std::vector<lir::Label*> targets);

  // Returns the last instruction appended.
  lir::Instruction* Last() const { return last_; }

  // Returns the position of `instr` in the code, counting labels, or -1.
  int IndexOf(const lir::Instruction* instr) const;

  // Operands, in the order that the bytecode format takes them.
  lir::VReg* Reg(dex::u4 reg);
  lir::VRegList* Regs(const std::vector<dex::u4>& regs);
  lir::VRegRange* RegRange(dex::u4 first, int count);
  lir::Const32* Literal(dex::u4 value);
  lir::CodeLocation* Target(lir::Label* label);
  lir::String* StringRef(const char* value);
  lir::Type* TypeRef(const char* descriptor);
  lir::Field* FieldRef(const char* owner, const char* name, const char* type);
  lir::Method* MethodRef(const char* owner, const char* name,
                         const char* signature);

  ir::EncodedMethod* ir_method() const { return ir_method_; }
  lir::CodeIr& code_ir() const { return *code_ir_; }

 private:
  // Returns the prototype of the JNI `signature`.
  ir::Proto* GetProto(const std::string& signature);

  std::shared_ptr<ir::DexFile> dex_ir_;
  ir::Builder builder_;
  ir::EncodedMethod* ir_method_;
  std::unique_ptr<lir::CodeIr> code_ir_;
  lir::Instruction* last_ = nullptr;
};

// Base class for tests of IFilter implementations. Tests build a TestMethod,
// run the filter on it with context_ and output_, and check what it reported.
class FilterTestBase : public ::testing::Test {
 protected:
  // The instructions from `from` to `to`, both inclusive.
  struct Range {
    lir::Instruction* from;
    lir::Instruction* to;
  };

  // Records what a filter reports.
  class Output : public FilterOutput {
   public:
    void Ignore(lir::Instruction* from, lir::Instruction* to) override;
    void ReplaceBranches(lir::Instruction* source,
                         std::vector<lir::Instruction*> targets) override;

    const std::vector<Range>& ignored() const { return ignored_; }

   private:
    std::vector<Range> ignored_;
  };

  FilterTestBase();

  // Expects the filter to have ignored exactly the `expected` ranges of `m`,
  // in this order.
  void ExpectIgnored(const TestMethod& m,
                     const std::vector<Range>& expected) const;

  // Describes kTestClassName: a subclass of java.lang.Object without
  // annotations.
  FilterContext context_;
  Output output_;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_FILTER_TEST_BASE_H_
