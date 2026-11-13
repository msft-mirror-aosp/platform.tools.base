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

#include "tools/base/android-test/coverage/agent/native/synthetic_class_filter.h"

#include <gtest/gtest.h>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kNode[] = "Lcom/example/Node;";
constexpr dex::u4 kSyntheticClass = dex::kAccFinal | dex::kAccSynthetic;

// Unit tests for SyntheticClassFilter. The class under test is kTestClassName,
// unless a test names a companion class in context_.
class SyntheticClassFilterTest : public FilterTestBase {
 protected:
  // Appends the call to the getter of the property `name` of the Node in v2,
  // and the return of its value.
  static void AddCallToGetter(TestMethod& m) {
    m.Add(dex::OP_INVOKE_VIRTUAL,
          {m.Regs({2}), m.MethodRef(kNode, "getName", "()Ljava/lang/String;")});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  }

  SyntheticClassFilter filter_;
};

// val nameOf: (Node) -> String? = Node::name
// Kotlin compiles the reference into a synthetic class, whose get() casts its
// parameter to Node and calls the getter on the line of the reference.
TEST_F(SyntheticClassFilterTest, ShouldFilterMethodOfSyntheticClass) {
  context_.class_access_flags = kSyntheticClass;
  TestMethod m("get", "(Ljava/lang/Object;)Ljava/lang/Object;");
  lir::Instruction* first = m.AddLine(3);
  m.Add(dex::OP_CHECK_CAST, {m.Reg(2), m.TypeRef(kNode)});
  AddCallToGetter(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// val nameOf = object : (Node) -> String? {
//   override fun invoke(node: Node) = node.name
// }
// The classes of objects that the source declares aren't synthetic.
TEST_F(SyntheticClassFilterTest,
       ShouldNotFilterMethodOfClassThatIsNotSynthetic) {
  context_.class_access_flags = dex::kAccFinal;
  TestMethod m("invoke", "(Lcom/example/Node;)Ljava/lang/String;");
  m.AddLine(3);
  AddCallToGetter(m);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// interface Labeled { fun label(): String = "label" }
// When D8 desugars the default method, it moves its code to the synthetic
// companion class Labeled$-CC.
TEST_F(SyntheticClassFilterTest, ShouldNotFilterMethodOfCompanionOfInterface) {
  context_.class_name = "Lcom/example/Labeled$-CC;";
  context_.class_access_flags = dex::kAccPublic | kSyntheticClass;
  TestMethod m("$default$label", "(Lcom/example/Labeled;)Ljava/lang/String;");
  m.AddLine(1);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("label")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

}  // namespace
}  // namespace coverage
