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

#include "tools/base/android-test/coverage/agent/native/kotlin_serializable_filter.h"

#include <gtest/gtest.h>

#include <list>
#include <memory>
#include <optional>
#include <vector>

#include "slicer/arrayview.h"
#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "slicer/dex_ir_builder.h"
#include "slicer/memview.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"

namespace coverage {
namespace {

constexpr char kCompanion[] = "Lcom/example/Message$Companion;";
constexpr char kObject[] = "Lcom/example/Ping;";
constexpr char kSerializer[] = "()Lkotlinx/serialization/KSerializer;";
constexpr char kKSerializer[] = "Lkotlinx/serialization/KSerializer;";
constexpr char kGeneratedSerializer[] = "Lcom/example/Message$$serializer;";

// The code of the direct methods that tests add: only their debug information
// matters, so even constructors only return-void.
constexpr dex::u2 kMethodCode[] = {0x000e};

// Unit tests for KotlinSerializableFilter. The methods under test belong to
// kTestClassName, which each test names in context_.
class KotlinSerializableFilterTest : public FilterTestBase {
 protected:
  // Gives the class of `m` a private direct method `name` that takes `params`
  // and returns void, on `line`, or without line numbers if `line` is empty.
  // Its debug information starts on `start_line`, or on `line` if `start_line`
  // is empty. A special opcode encodes `line`, so it can be at most 4 lines
  // before and 10 lines after the start line.
  ir::EncodedMethod* AddDirectMethod(TestMethod& m, const char* name,
                                     const std::vector<const char*>& params,
                                     std::optional<dex::u4> line,
                                     std::optional<dex::u4> start_line = {}) {
    std::shared_ptr<ir::DexFile> dex_ir = m.code_ir().dex_ir;
    ir::Builder builder(dex_ir);
    ir::Type* type = m.ir_method()->decl->parent;
    if (type->class_def == nullptr) {
      type->class_def = dex_ir->Alloc<ir::Class>();
      type->class_def->type = type;
    }
    std::vector<ir::Type*> param_types;
    for (const char* param : params) {
      param_types.push_back(builder.GetType(param));
    }
    auto* method = dex_ir->Alloc<ir::EncodedMethod>();
    method->decl = builder.GetMethodDecl(
        builder.GetAsciiString(name),
        builder.GetProto(builder.GetType("V"),
                         builder.GetTypeList(param_types)),
        type);
    method->access_flags = dex::kAccPrivate;
    method->code = dex_ir->Alloc<ir::Code>();
    method->code->registers = static_cast<dex::u2>(params.size() + 1);
    method->code->ins_count = method->code->registers;
    method->code->instructions =
        slicer::ArrayView<const dex::u2>(kMethodCode, 1);
    if (line.has_value()) {
      // The line number of the first code unit, relative to the start line.
      const dex::u4 start = start_line.value_or(*line);
      const std::vector<dex::u1>& data =
          debug_infos_.emplace_back(std::vector<dex::u1>{
              static_cast<dex::u1>(dex::DBG_FIRST_SPECIAL + (*line - start) -
                                   dex::DBG_LINE_BASE),
              dex::DBG_END_SEQUENCE});
      method->code->debug_info = dex_ir->Alloc<ir::DebugInfo>();
      method->code->debug_info->line_start = start;
      method->code->debug_info->data =
          slicer::MemView(data.data(), data.size());
    }
    type->class_def->direct_methods.push_back(method);
    return method;
  }

  // Gives the class of `m` a constructor without parameters, on `line`, or
  // without line numbers if `line` is empty. Its debug information starts on
  // `start_line`, or on `line` if `start_line` is empty.
  void AddConstructor(TestMethod& m, std::optional<dex::u4> line,
                      std::optional<dex::u4> start_line = {}) {
    AddDirectMethod(m, "<init>", {}, line, start_line)->access_flags |=
        dex::kAccConstructor;
  }

  // Appends the code of serializer() of a companion object on `line`, as
  // slicer disassembles it: the start line and `line`, then the read of the
  // INSTANCE of the generated serializer, its cast to `cast` and the return.
  static void AddSerializerCode(TestMethod& m, int line,
                                const char* cast = kKSerializer) {
    m.AddLine(line);
    m.AddLine(line);
    m.Add(dex::OP_SGET_OBJECT,
          {m.Reg(0),
           m.FieldRef(kGeneratedSerializer, "INSTANCE", kGeneratedSerializer)});
    m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef(cast)});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  }

  // Appends the code of a serializer() that returns get$cachedSerializer() of
  // `owner`: the call on `this` in v<reg>, the move of its result and the
  // return.
  static void AddCachedSerializerCode(TestMethod& m, dex::u4 reg,
                                      const char* owner = kObject) {
    m.Add(dex::OP_INVOKE_DIRECT,
          {m.Regs({reg}),
           m.MethodRef(owner, "get$cachedSerializer", kSerializer)});
    m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});
  }

  // Makes `m` synthetic and gives it a line and return-void, then runs the
  // filter on it and returns whether it ignored the whole method.
  bool IgnoresSynthetic(TestMethod& m) {
    m.ir_method()->access_flags |= dex::kAccSynthetic;
    m.AddLine(1);
    lir::Instruction* first = m.Last();
    m.Add(dex::OP_RETURN_VOID);
    filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);
    return output_.ignored().size() == 1 &&
           output_.ignored()[0].from == first &&
           output_.ignored()[0].to == m.Last();
  }

  KotlinSerializableFilter filter_;
  // The debug information of the direct methods that tests add.
  std::list<std::vector<dex::u1>> debug_infos_;
};

// @Serializable class Message(val text: String)
// The plugin gives the companion object of Message a serializer() that returns
// the generated serializer, on the line of the class, like the constructor of
// the companion object.
TEST_F(KotlinSerializableFilterTest, ShouldFilterSerializerOfCompanionObject) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/3);
  AddSerializerCode(m, /*line=*/3);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{*m.code_ir().instructions.begin(), m.Last()}});
}

// When the agent grows the frame of serializer(), it first copies `this` back
// to its register, which is ignored too.
TEST_F(KotlinSerializableFilterTest, ShouldFilterSerializerWithCopyOfAgent) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/3);
  lir::Instruction* first = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(1), m.Reg(2)});
  AddSerializerCode(m, /*line=*/3);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// The debug information of the constructor can start on another line than its
// first line number.
TEST_F(KotlinSerializableFilterTest,
       ShouldFilterSerializerOnFirstLineOfConstructor) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/3, /*start_line=*/1);
  AddSerializerCode(m, /*line=*/3);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{*m.code_ir().instructions.begin(), m.Last()}});
}

// When the constructor of the companion object has no line numbers, the line
// of serializer() isn't checked.
TEST_F(KotlinSerializableFilterTest,
       ShouldFilterSerializerWhenConstructorHasNoLines) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/std::nullopt);
  AddSerializerCode(m, /*line=*/7);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{*m.code_ir().instructions.begin(), m.Last()}});
}

// A serializer() on another line than the constructor of the companion object
// isn't the one that the plugin generates.
TEST_F(KotlinSerializableFilterTest, ShouldNotFilterSerializerOnOtherLine) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/3);
  AddSerializerCode(m, /*line=*/5);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The serializer() of a class that isn't a companion object, here the
// serializable class itself, is never the one that returns the INSTANCE of the
// generated serializer.
TEST_F(KotlinSerializableFilterTest,
       ShouldNotFilterSerializerOfClassThatIsNotCompanion) {
  context_.class_name = "Lcom/example/Message;";
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/3);
  AddSerializerCode(m, /*line=*/3);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Without the constructor of the companion object, the line of serializer()
// can't be checked.
TEST_F(KotlinSerializableFilterTest,
       ShouldNotFilterSerializerWithoutConstructor) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddDirectMethod(m, "helper", {}, /*line=*/std::nullopt);
  AddSerializerCode(m, /*line=*/3);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The synthetic constructor of the companion object, which takes a
// DefaultConstructorMarker and has no line numbers, isn't the one whose line
// counts. It comes first here, so that a lookup that doesn't check the
// parameters finds it.
TEST_F(KotlinSerializableFilterTest,
       ShouldNotFilterSerializerOnOtherLineWithSyntheticConstructor) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  ir::EncodedMethod* synthetic = AddDirectMethod(
      m, "<init>", {"Lkotlin/jvm/internal/DefaultConstructorMarker;"},
      /*line=*/std::nullopt);
  synthetic->access_flags =
      dex::kAccPublic | dex::kAccSynthetic | dex::kAccConstructor;
  AddConstructor(m, /*line=*/3);
  AddSerializerCode(m, /*line=*/5);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// Only the line of the constructor counts, not the one of another method of the
// companion object without parameters. It comes first here, so that a lookup
// that doesn't check the name finds it.
TEST_F(KotlinSerializableFilterTest,
       ShouldNotFilterSerializerOnLineOfOtherMethod) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddDirectMethod(m, "helper", {}, /*line=*/5);
  AddConstructor(m, /*line=*/3);
  AddSerializerCode(m, /*line=*/5);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The read of INSTANCE must start a line.
TEST_F(KotlinSerializableFilterTest, ShouldNotFilterSerializerWithoutLine) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/std::nullopt);
  m.AddLine(3);  // The start line.
  m.Add(dex::OP_SGET_OBJECT,
        {m.Reg(0),
         m.FieldRef(kGeneratedSerializer, "INSTANCE", kGeneratedSerializer)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef(kKSerializer)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The field must be named INSTANCE.
TEST_F(KotlinSerializableFilterTest, ShouldNotFilterSerializerOfOtherField) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/3);
  m.AddLine(3);
  m.AddLine(3);
  m.Add(dex::OP_SGET_OBJECT, {m.Reg(0), m.FieldRef("Lcom/example/Serializers;",
                                                   "MESSAGE", kKSerializer)});
  m.Add(dex::OP_CHECK_CAST, {m.Reg(0), m.TypeRef(kKSerializer)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// companion object {
//   fun serializer(): KSerializer<Message> = MessageSerializer
// }
// An object that is a KSerializer needs no cast.
TEST_F(KotlinSerializableFilterTest, ShouldNotFilterSerializerWithoutCast) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/3);
  m.AddLine(3);
  m.AddLine(3);
  m.Add(dex::OP_SGET_OBJECT,
        {m.Reg(0), m.FieldRef("Lcom/example/MessageSerializer;", "INSTANCE",
                              "Lcom/example/MessageSerializer;")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The INSTANCE must be cast to KSerializer.
TEST_F(KotlinSerializableFilterTest, ShouldNotFilterSerializerWithOtherCast) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/3);
  AddSerializerCode(m, /*line=*/3,
                    "Lkotlinx/serialization/internal/GeneratedSerializer;");

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// @Serializable class Box<T>(val value: T)
// The serializer() of a generic class takes the serializers of its type
// arguments.
TEST_F(KotlinSerializableFilterTest,
       ShouldNotFilterSerializerWithOtherSignature) {
  context_.class_name = kCompanion;
  TestMethod m("serializer",
               "(Lkotlinx/serialization/KSerializer;)"
               "Lkotlinx/serialization/KSerializer;");
  AddConstructor(m, /*line=*/3);
  AddSerializerCode(m, /*line=*/3);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// @Serializable object Ping
// The plugin gives the object a serializer() that returns
// get$cachedSerializer(), the serializer that a lazy property creates once.
TEST_F(KotlinSerializableFilterTest, ShouldFilterSerializerOfObject) {
  context_.class_name = kObject;
  TestMethod m("serializer", kSerializer);
  m.AddLine(4);
  m.AddLine(4);
  lir::Instruction* first = *m.code_ir().instructions.begin();
  AddCachedSerializerCode(m, /*reg=*/1);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// @Serializable sealed class Event
// The companion object of a sealed, abstract or enum class returns its
// get$cachedSerializer() too, whose line isn't checked.
TEST_F(KotlinSerializableFilterTest,
       ShouldFilterCachedSerializerOfCompanionObject) {
  context_.class_name = kCompanion;
  TestMethod m("serializer", kSerializer);
  AddConstructor(m, /*line=*/3);
  m.AddLine(5);
  m.AddLine(5);
  lir::Instruction* first = *m.code_ir().instructions.begin();
  AddCachedSerializerCode(m, /*reg=*/1, kCompanion);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {Range{first, m.Last()}});
}

// object Ping {
//   fun serializer(): KSerializer<Ping> = createSerializer()
// }
TEST_F(KotlinSerializableFilterTest, ShouldNotFilterSerializerThatCallsOther) {
  context_.class_name = kObject;
  TestMethod m("serializer", kSerializer);
  m.AddLine(4);
  m.AddLine(4);
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({1}), m.MethodRef(kObject, "createSerializer", kSerializer)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The call must be to get$cachedSerializer() of the class itself.
TEST_F(KotlinSerializableFilterTest,
       ShouldNotFilterCallToCachedSerializerOfOtherClass) {
  context_.class_name = kObject;
  TestMethod m("serializer", kSerializer);
  m.AddLine(4);
  m.AddLine(4);
  AddCachedSerializerCode(m, /*reg=*/1, "Lcom/example/Pong;");

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The plugin writes the properties of a serializable class in its synthetic
// write$Self$<module>.
TEST_F(KotlinSerializableFilterTest, ShouldFilterWriteSelf) {
  TestMethod m("write$Self$network",
               "(Lcom/example/Message;Lkotlinx/serialization/encoding/"
               "CompositeEncoder;Lkotlinx/serialization/descriptors/"
               "SerialDescriptor;)V");

  EXPECT_TRUE(IgnoresSynthetic(m));
}

// The synthetic methods are only the ones that the plugin generates.
TEST_F(KotlinSerializableFilterTest, ShouldNotFilterWriteSelfIfNotSynthetic) {
  TestMethod m("write$Self$network",
               "(Lcom/example/Message;Lkotlinx/serialization/encoding/"
               "CompositeEncoder;Lkotlinx/serialization/descriptors/"
               "SerialDescriptor;)V");
  m.AddLine(1);
  m.Add(dex::OP_RETURN_VOID);

  filter_.Filter(context_, m.ir_method(), m.code_ir(), output_);

  ExpectIgnored(m, {});
}

// The synthetic get$cachedSerializer() of an object gets its serializer from a
// lazy property.
TEST_F(KotlinSerializableFilterTest, ShouldFilterCachedSerializerGetter) {
  TestMethod m("get$cachedSerializer", kSerializer);

  EXPECT_TRUE(IgnoresSynthetic(m));
}

// The lambda of that lazy property creates the serializer of the object.
TEST_F(KotlinSerializableFilterTest, ShouldFilterLambdaThatCreatesSerializer) {
  TestMethod m("_init_$_anonymous_", kSerializer);

  EXPECT_TRUE(IgnoresSynthetic(m));
}

// @Serializable class NewsResource(val topics: List<String>)
// The serializers of some properties, such as lists, are created by a lambda.
TEST_F(KotlinSerializableFilterTest,
       ShouldFilterLambdaThatCreatesChildSerializer) {
  TestMethod m("_childSerializers$_anonymous_", kSerializer);

  EXPECT_TRUE(IgnoresSynthetic(m));
}

// Those lambdas return a KSerializer.
TEST_F(KotlinSerializableFilterTest, ShouldNotFilterLambdaWithOtherSignature) {
  TestMethod m("_init_$_anonymous_", "()Ljava/lang/Object;");

  EXPECT_FALSE(IgnoresSynthetic(m));
}

// The plugin generates a synthetic constructor that the deserializer calls,
// which ends with a SerializationConstructorMarker.
TEST_F(KotlinSerializableFilterTest,
       ShouldFilterConstructorForDeserialization) {
  TestMethod m(
      "<init>",
      "(ILjava/lang/String;"
      "Lkotlinx/serialization/internal/SerializationConstructorMarker;)V");

  EXPECT_TRUE(IgnoresSynthetic(m));
}

// The marker must follow other parameters.
TEST_F(KotlinSerializableFilterTest, ShouldNotFilterConstructorWithOnlyMarker) {
  TestMethod m(
      "<init>",
      "(Lkotlinx/serialization/internal/SerializationConstructorMarker;)V");

  EXPECT_FALSE(IgnoresSynthetic(m));
}

// class Message(val text: String = "")
// The synthetic constructor for default arguments ends with another marker.
TEST_F(KotlinSerializableFilterTest,
       ShouldNotFilterConstructorForDefaultArguments) {
  TestMethod m("<init>",
               "(Ljava/lang/String;ILkotlin/jvm/internal/"
               "DefaultConstructorMarker;)V");

  EXPECT_FALSE(IgnoresSynthetic(m));
}

}  // namespace
}  // namespace coverage
