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

#include "tools/base/android-test/coverage/agent/native/records_filter.h"

#include <gtest/gtest.h>

#include <memory>
#include <string>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "slicer/dex_ir_builder.h"
#include "tools/base/android-test/coverage/agent/native/filter_test_base.h"
#include "tools/base/android-test/coverage/agent/native/synthetic_filter.h"

namespace coverage {
namespace {

constexpr char kRecordTag[] = "Lcom/android/tools/r8/RecordTag;";
constexpr char kRecord[] = "Ljava/lang/Record;";
constexpr char kString[] = "Ljava/lang/String;";
// The class to which D8 adds the methods that toString() and hashCode() call.
constexpr char kHelper[] = "LFoo$0;";
constexpr char kGetFieldsAsObjects[] = "$record$getFieldsAsObjects";
constexpr char kFieldsAsObjects[] = "()[Ljava/lang/Object;";
constexpr char kToString[] = "()Ljava/lang/String;";
constexpr char kToStringHelper[] =
    "([Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)"
    "Ljava/lang/String;";
constexpr char kRecordEquals[] = "$record$equals";
constexpr char kEquals[] = "(Ljava/lang/Object;)Z";
// The line of the declaration of the record, on which D8 puts the code of the
// methods that javac generates.
constexpr int kRecordLine = 4;

// How RecordsFilterTest::AddToString() lays out toString(): the code that D8
// generates, with `this` in v3, unless a test changes one of the fields.
struct ToStringShape {
  // How the code calls $record$getFieldsAsObjects() on v<receiver>.
  dex::Opcode values_call = dex::OP_INVOKE_DIRECT;
  const char* values_class = kTestClassName;
  const char* values_method = kGetFieldsAsObjects;
  const char* values_signature = kFieldsAsObjects;
  dex::u4 receiver = 3;
  // Whether the code moves the values into v0, or loads null instead.
  bool moves_values = true;
  // How the code loads the class into v1.
  dex::Opcode class_load = dex::OP_CONST_CLASS;
  const char* loaded_class = kTestClassName;
  // How the code loads the names of the components into v2: with
  // const-string, const-string/jumbo, or const/4 for null.
  dex::Opcode names_load = dex::OP_CONST_STRING;
  // How the code calls the method that D8 generates, and what it passes.
  dex::Opcode helper_call = dex::OP_INVOKE_STATIC;
  const char* helper_signature = kToStringHelper;
  std::vector<dex::u4> arguments = {0, 1, 2};
  // Whether the code moves the result into v0, or loads a string instead.
  bool moves_result = true;
  // The register that the code returns.
  dex::u4 returned = 0;
};

// How RecordsFilterTest::AddEquals() lays out equals(Object): the code that D8
// generates, with `this` in v1 and the other object in v2, unless a test
// changes one of the fields.
struct EqualsShape {
  // How the code calls $record$equals(Object), and what it passes.
  dex::Opcode call = dex::OP_INVOKE_DIRECT;
  const char* method_class = kTestClassName;
  const char* method = kRecordEquals;
  const char* signature = kEquals;
  std::vector<dex::u4> arguments = {1, 2};
  // Whether the code moves the result into v0, or loads true instead.
  bool moves_result = true;
  // The register that the code returns.
  dex::u4 returned = 0;
};

// How RecordsFilterTest::AddHashCode() lays out hashCode() of Foo(int x,
// int y): the code that D8 generates, with `this` in v2, unless a test changes
// one of the fields.
struct HashCodeShape {
  // The bytecode that reads x into v0.
  dex::Opcode read = dex::OP_IGET;
  // How the code calls the method that D8 generates, and what it passes.
  dex::Opcode call = dex::OP_INVOKE_STATIC;
  const char* helper_class = kHelper;
  const char* helper = "m";
  const char* helper_signature = "(II)I";
  std::vector<dex::u4> arguments = {0, 1};
  // Whether the code moves the result into v0, or loads 1 instead.
  bool moves_result = true;
  // The register that the code returns.
  dex::u4 returned = 0;
};

// Unit tests for RecordsFilter, on the code that javac and D8 generate for the
// methods of
//   record Foo(int x, int y) {}
// unless the comment of a test declares Foo with other components. Foo is
// kTestClassName, whose superclass D8 replaces with RecordTag.
class RecordsFilterTest : public FilterTestBase {
 protected:
  RecordsFilterTest() { context_.super_class_name = kRecordTag; }

  // Gives `m` a frame of `registers` registers, the last `ins` of which hold
  // its parameters, and the access flags that javac gives it: public final for
  // toString(), hashCode() and equals(), public for the accessors. Then appends
  // the line of the record as slicer disassembles it: the start line, then the
  // line of the first bytecode.
  static void Begin(TestMethod& m, dex::u2 registers, dex::u2 ins) {
    const std::string name = m.ir_method()->decl->name->c_str();
    m.ir_method()->access_flags =
        name == "toString" || name == "hashCode" || name == "equals"
            ? dex::kAccPublic | dex::kAccFinal
            : dex::kAccPublic;
    m.ir_method()->code->registers = registers;
    m.ir_method()->code->ins_count = ins;
    m.AddLine(kRecordLine);  // The start line.
    m.AddLine(kRecordLine);
  }

  // Gives Foo the class definition of a record with the components `names`,
  // all ints, which are its instance fields.
  static void DeclareFields(TestMethod& m,
                            const std::vector<const char*>& names) {
    std::shared_ptr<ir::DexFile> dex_ir = m.code_ir().dex_ir;
    ir::Builder builder(dex_ir);
    ir::Type* type = m.ir_method()->decl->parent;
    type->class_def = dex_ir->Alloc<ir::Class>();
    type->class_def->type = type;
    for (const char* name : names) {
      auto* field = dex_ir->Alloc<ir::EncodedField>();
      field->decl = builder.GetFieldDecl(builder.GetAsciiString(name),
                                         builder.GetType("I"), type);
      field->access_flags = dex::kAccPrivate | dex::kAccFinal;
      type->class_def->instance_fields.push_back(field);
    }
  }

  // Returns the pair of registers that starts with v<reg>.
  static lir::VRegPair* Pair(TestMethod& m, dex::u4 reg) {
    return m.code_ir().Alloc<lir::VRegPair>(reg);
  }

  // Appends toString(), laid out as `shape`.
  static void AddToString(TestMethod& m,
                          const ToStringShape& shape = ToStringShape()) {
    m.Add(shape.values_call,
          {m.Regs({shape.receiver}),
           m.MethodRef(shape.values_class, shape.values_method,
                       shape.values_signature)});
    if (shape.moves_values) {
      m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    } else {
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    }
    m.Add(shape.class_load, {m.Reg(1), m.TypeRef(shape.loaded_class)});
    if (shape.names_load == dex::OP_CONST_4) {
      m.Add(dex::OP_CONST_4, {m.Reg(2), m.Literal(0)});
    } else {
      m.Add(shape.names_load, {m.Reg(2), m.StringRef("x;y")});
    }
    m.Add(shape.helper_call,
          {m.Regs(shape.arguments),
           m.MethodRef(kHelper, "m", shape.helper_signature)});
    if (shape.moves_result) {
      m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
    } else {
      m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("Foo")});
    }
    m.Add(dex::OP_RETURN_OBJECT, {m.Reg(shape.returned)});
  }

  // Appends equals(Object), laid out as `shape`.
  static void AddEquals(TestMethod& m,
                        const EqualsShape& shape = EqualsShape()) {
    m.Add(shape.call,
          {m.Regs(shape.arguments),
           m.MethodRef(shape.method_class, shape.method, shape.signature)});
    if (shape.moves_result) {
      m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    } else {
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
    }
    m.Add(dex::OP_RETURN, {m.Reg(shape.returned)});
  }

  // Appends hashCode() of Foo(int x, int y), laid out as `shape`.
  static void AddHashCode(TestMethod& m,
                          const HashCodeShape& shape = HashCodeShape()) {
    m.Add(shape.read,
          {m.Reg(0), m.Reg(2), m.FieldRef(kTestClassName, "x", "I")});
    m.Add(dex::OP_IGET,
          {m.Reg(1), m.Reg(2), m.FieldRef(kTestClassName, "y", "I")});
    m.Add(shape.call, {m.Regs(shape.arguments),
                       m.MethodRef(shape.helper_class, shape.helper,
                                   shape.helper_signature)});
    if (shape.moves_result) {
      m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
    } else {
      m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
    }
    m.Add(dex::OP_RETURN, {m.Reg(shape.returned)});
  }

  // Appends hashCode() of a record without components.
  static void AddHashCodeOfRecordWithoutComponents(TestMethod& m) {
    m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
    m.Add(dex::OP_RETURN, {m.Reg(0)});
  }

  // Appends the accessor of the field `name` of type `type`, with `this` in
  // v<self>: `get`, an iget for example, reads the field into v0, or into v0
  // and v1 for a wide type, and `ret` returns it.
  static void AddAccessor(TestMethod& m, const char* name, const char* type,
                          dex::Opcode get, dex::Opcode ret, dex::u4 self) {
    const bool wide = get == dex::OP_IGET_WIDE;
    m.Add(get, {wide ? static_cast<lir::Operand*>(Pair(m, 0)) : m.Reg(0),
                m.Reg(self), m.FieldRef(kTestClassName, name, type)});
    m.Add(ret, {wide ? static_cast<lir::Operand*>(Pair(m, 0)) : m.Reg(0)});
  }

  void Filter(TestMethod& m) {
    RecordsFilter().Filter(context_, m.ir_method(), m.code_ir(), output_);
  }

  // Expects the filter to have ignored all the code of `m`.
  void ExpectAllIgnored(const TestMethod& m) const {
    ExpectIgnored(m, {{*m.code_ir().instructions.begin(), m.Last()}});
  }

  // Builds toString(), laid out as `shape`, and expects the filter to leave it
  // alone.
  void ExpectToStringNotFiltered(const ToStringShape& shape) {
    TestMethod m("toString", kToString);
    Begin(m, /*registers=*/4, /*ins=*/1);
    AddToString(m, shape);

    Filter(m);

    ExpectIgnored(m, {});
  }

  // Builds equals(Object), laid out as `shape`, and expects the filter to
  // leave it alone.
  void ExpectEqualsNotFiltered(const EqualsShape& shape) {
    TestMethod m("equals", kEquals);
    Begin(m, /*registers=*/3, /*ins=*/2);
    AddEquals(m, shape);

    Filter(m);

    ExpectIgnored(m, {});
  }

  // Builds hashCode(), laid out as `shape`, and expects the filter to leave it
  // alone.
  void ExpectHashCodeNotFiltered(const HashCodeShape& shape) {
    TestMethod m("hashCode", "()I");
    Begin(m, /*registers=*/3, /*ins=*/1);
    AddHashCode(m, shape);

    Filter(m);

    ExpectIgnored(m, {});
  }
};

// toString().

// D8 passes the values of the fields, the class and the names of the
// components to a method that it generates.
TEST_F(RecordsFilterTest, ShouldFilterToString) {
  TestMethod m("toString", kToString);
  Begin(m, /*registers=*/4, /*ins=*/1);
  AddToString(m);

  Filter(m);

  ExpectAllIgnored(m);
}

// In a dex file with more than 65536 strings, D8 loads the names with
// const-string/jumbo.
TEST_F(RecordsFilterTest, ShouldFilterToStringWithJumboString) {
  ToStringShape shape;
  shape.names_load = dex::OP_CONST_STRING_JUMBO;
  TestMethod m("toString", kToString);
  Begin(m, /*registers=*/4, /*ins=*/1);
  AddToString(m, shape);

  Filter(m);

  ExpectAllIgnored(m);
}

// The registers of toString() are all in use, so the agent grows its frame
// before the filters run. `this` then arrives in v4, and the agent first copies
// it back to v3, which is ignored too.
TEST_F(RecordsFilterTest, ShouldFilterToStringWithCopyOfAgent) {
  TestMethod m("toString", kToString);
  lir::Instruction* copy = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(3), m.Reg(4)});
  Begin(m, /*registers=*/5, /*ins=*/1);
  AddToString(m);

  Filter(m);

  ExpectIgnored(m, {{copy, m.Last()}});
}

// In release mode, D8 moves the values into the register of `this`, which it
// no longer needs, and loads the class and the names into v0 and v1.
TEST_F(RecordsFilterTest, ShouldFilterToStringOfReleaseMode) {
  TestMethod m("toString", kToString);
  Begin(m, /*registers=*/3, /*ins=*/1);
  m.Add(dex::OP_INVOKE_DIRECT,
        {m.Regs({2}),
         m.MethodRef(kTestClassName, kGetFieldsAsObjects, kFieldsAsObjects)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
  m.Add(dex::OP_CONST_CLASS, {m.Reg(0), m.TypeRef(kTestClassName)});
  m.Add(dex::OP_CONST_STRING, {m.Reg(1), m.StringRef("x;y")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({2, 0, 1}), m.MethodRef(kHelper, "m", kToStringHelper)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(2)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(2)});

  Filter(m);

  ExpectAllIgnored(m);
}

// With a recent enough minimum API level, D8 keeps the superclass Record.
TEST_F(RecordsFilterTest, ShouldFilterMethodsWhenD8KeepsRecord) {
  context_.super_class_name = kRecord;
  TestMethod m("toString", kToString);
  Begin(m, /*registers=*/4, /*ins=*/1);
  AddToString(m);

  Filter(m);

  ExpectAllIgnored(m);
}

// public String toString() { return "Foo"; }
TEST_F(RecordsFilterTest, ShouldNotFilterToStringWrittenInSource) {
  TestMethod m("toString", kToString);
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_CONST_STRING, {m.Reg(0), m.StringRef("Foo")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// The method must be exactly the code that D8 generates, even with code after
// the return that nothing reaches.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringWithMoreCode) {
  TestMethod m("toString", kToString);
  Begin(m, /*registers=*/4, /*ins=*/1);
  AddToString(m);
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// public String toString(int indent), with the code of toString().
TEST_F(RecordsFilterTest, ShouldNotFilterOverloadOfToString) {
  TestMethod m("toString", "(I)Ljava/lang/String;");
  Begin(m, /*registers=*/5, /*ins=*/2);
  AddToString(m);

  Filter(m);

  ExpectIgnored(m, {});
}

// The code calls $record$getFieldsAsObjects() with invoke-virtual.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringWithVirtualCallForValues) {
  ToStringShape shape;
  shape.values_call = dex::OP_INVOKE_VIRTUAL;
  ExpectToStringNotFiltered(shape);
}

// The values come from $record$getFieldsAsObjects() of another record.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringOfValuesOfOtherRecord) {
  ToStringShape shape;
  shape.values_class = "LBar;";
  ExpectToStringNotFiltered(shape);
}

// The values come from another method of Foo.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringOfValuesOfOtherMethod) {
  ToStringShape shape;
  shape.values_method = "fields";
  ExpectToStringNotFiltered(shape);
}

// The values come from a $record$getFieldsAsObjects() that returns strings.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringOfValuesWithOtherSignature) {
  ToStringShape shape;
  shape.values_signature = "()[Ljava/lang/String;";
  ExpectToStringNotFiltered(shape);
}

// The values come from another object than `this`.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringOfValuesOfOtherObject) {
  ToStringShape shape;
  shape.receiver = 2;
  ExpectToStringNotFiltered(shape);
}

// The code passes null instead of the values.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringThatDiscardsValues) {
  ToStringShape shape;
  shape.moves_values = false;
  ExpectToStringNotFiltered(shape);
}

// The code passes a new Foo instead of its class.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringThatCreatesObject) {
  ToStringShape shape;
  shape.class_load = dex::OP_NEW_INSTANCE;
  ExpectToStringNotFiltered(shape);
}

// The code passes the class of another record.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringOfOtherClass) {
  ToStringShape shape;
  shape.loaded_class = "LBar;";
  ExpectToStringNotFiltered(shape);
}

// The code passes null instead of the names of the components.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringWithoutNames) {
  ToStringShape shape;
  shape.names_load = dex::OP_CONST_4;
  ExpectToStringNotFiltered(shape);
}

// The code calls the method with invoke-virtual.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringWithVirtualCallOfMethod) {
  ToStringShape shape;
  shape.helper_call = dex::OP_INVOKE_VIRTUAL;
  ExpectToStringNotFiltered(shape);
}

// The code calls a method that returns an Object.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringThatCallsOtherMethod) {
  ToStringShape shape;
  shape.helper_signature =
      "([Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)"
      "Ljava/lang/Object;";
  ExpectToStringNotFiltered(shape);
}

// The code passes the names before the class.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringWithArgumentsInOtherOrder) {
  ToStringShape shape;
  shape.arguments = {0, 2, 1};
  ExpectToStringNotFiltered(shape);
}

// The code returns another string than the result.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringThatDiscardsResult) {
  ToStringShape shape;
  shape.moves_result = false;
  ExpectToStringNotFiltered(shape);
}

// The code returns the names of the components.
TEST_F(RecordsFilterTest, ShouldNotFilterToStringThatReturnsNames) {
  ToStringShape shape;
  shape.returned = 2;
  ExpectToStringNotFiltered(shape);
}

// equals(Object).

// D8 calls $record$equals(Object), which it generates.
TEST_F(RecordsFilterTest, ShouldFilterEquals) {
  TestMethod m("equals", kEquals);
  Begin(m, /*registers=*/3, /*ins=*/2);
  AddEquals(m);

  Filter(m);

  ExpectAllIgnored(m);
}

// When the agent grows the frame of equals(Object), `this` and the other
// object arrive in v2 and v3, and the agent first copies them back to v1 and
// v2.
TEST_F(RecordsFilterTest, ShouldFilterEqualsWithCopiesOfAgent) {
  TestMethod m("equals", kEquals);
  lir::Instruction* copy = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(1), m.Reg(2)});
  m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(2), m.Reg(3)});
  Begin(m, /*registers=*/4, /*ins=*/2);
  AddEquals(m);

  Filter(m);

  ExpectIgnored(m, {{copy, m.Last()}});
}

// public boolean equals(Object o) { return o == this; }
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsWrittenInSource) {
  TestMethod m("equals", kEquals);
  Begin(m, /*registers=*/3, /*ins=*/2);
  lir::Label* same = m.NewLabel();
  m.Add(dex::OP_IF_EQ, {m.Reg(2), m.Reg(1), m.Target(same)});
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});
  m.Place(same);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// The method must be exactly the code that D8 generates, even with code after
// the return that nothing reaches.
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsWithMoreCode) {
  TestMethod m("equals", kEquals);
  Begin(m, /*registers=*/3, /*ins=*/2);
  AddEquals(m);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// public boolean equals(Foo other), with the code of equals(Object).
TEST_F(RecordsFilterTest, ShouldNotFilterOverloadOfEquals) {
  TestMethod m("equals", "(LFoo;)Z");
  Begin(m, /*registers=*/3, /*ins=*/2);
  AddEquals(m);

  Filter(m);

  ExpectIgnored(m, {});
}

// The code calls $record$equals(Object) with invoke-virtual.
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsWithVirtualCall) {
  EqualsShape shape;
  shape.call = dex::OP_INVOKE_VIRTUAL;
  ExpectEqualsNotFiltered(shape);
}

// The code calls $record$equals(Object) of another record.
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsOfOtherRecord) {
  EqualsShape shape;
  shape.method_class = "LBar;";
  ExpectEqualsNotFiltered(shape);
}

// public boolean equals(Object o) { return same(o); }
// private boolean same(Object o) { ... }
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsThatCallsOtherMethod) {
  EqualsShape shape;
  shape.method = "same";
  ExpectEqualsNotFiltered(shape);
}

// The code calls a $record$equals that takes a Foo.
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsThatCallsOtherSignature) {
  EqualsShape shape;
  shape.signature = "(LFoo;)Z";
  ExpectEqualsNotFiltered(shape);
}

// The code compares the other object with itself.
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsOfOtherObject) {
  EqualsShape shape;
  shape.arguments = {2, 2};
  ExpectEqualsNotFiltered(shape);
}

// The code compares `this` with itself.
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsWithItself) {
  EqualsShape shape;
  shape.arguments = {1, 1};
  ExpectEqualsNotFiltered(shape);
}

// The code returns true whatever the result.
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsThatDiscardsResult) {
  EqualsShape shape;
  shape.moves_result = false;
  ExpectEqualsNotFiltered(shape);
}

// The code returns another register than the result.
TEST_F(RecordsFilterTest, ShouldNotFilterEqualsThatReturnsOtherRegister) {
  EqualsShape shape;
  shape.returned = 2;
  ExpectEqualsNotFiltered(shape);
}

// hashCode().

// D8 passes the fields to a method m that it generates.
TEST_F(RecordsFilterTest, ShouldFilterHashCode) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/3, /*ins=*/1);
  AddHashCode(m);

  Filter(m);

  ExpectAllIgnored(m);
}

// record Foo(String name, long id) {}
// D8 reads id before name, and its method takes name as an Object.
TEST_F(RecordsFilterTest, ShouldFilterHashCodeThatPassesReferenceAsObject) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/4, /*ins=*/1);
  m.Add(dex::OP_IGET_WIDE,
        {Pair(m, 0), m.Reg(3), m.FieldRef(kTestClassName, "id", "J")});
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(2), m.Reg(3), m.FieldRef(kTestClassName, "name", kString)});
  m.Add(
      dex::OP_INVOKE_STATIC,
      {m.Regs({0, 1, 2}), m.MethodRef(kHelper, "m", "(JLjava/lang/Object;)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectAllIgnored(m);
}

// record Foo(int[] values) {}
// D8 takes the array as an Object, with a method that it can share with other
// records, which can be in the class of another record Bar.
TEST_F(RecordsFilterTest, ShouldFilterHashCodeOfRecordWithOneComponent) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "values", "[I")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0}), m.MethodRef("LBar$0;", "m", "(Ljava/lang/Object;)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectAllIgnored(m);
}

// record Foo(boolean done, char initial, float ratio, double weight,
//            byte flags, short port) {}
// D8 reads the fields in the order of their types, takes char, byte and short
// as int, and passes more than five registers with invoke-static/range.
TEST_F(RecordsFilterTest, ShouldFilterHashCodeWithRange) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/8, /*ins=*/1);
  m.Add(dex::OP_IGET_BOOLEAN,
        {m.Reg(0), m.Reg(7), m.FieldRef(kTestClassName, "done", "Z")});
  m.Add(dex::OP_IGET_CHAR,
        {m.Reg(1), m.Reg(7), m.FieldRef(kTestClassName, "initial", "C")});
  m.Add(dex::OP_IGET_BYTE,
        {m.Reg(2), m.Reg(7), m.FieldRef(kTestClassName, "flags", "B")});
  m.Add(dex::OP_IGET_SHORT,
        {m.Reg(3), m.Reg(7), m.FieldRef(kTestClassName, "port", "S")});
  m.Add(dex::OP_IGET,
        {m.Reg(4), m.Reg(7), m.FieldRef(kTestClassName, "ratio", "F")});
  m.Add(dex::OP_IGET_WIDE,
        {Pair(m, 5), m.Reg(7), m.FieldRef(kTestClassName, "weight", "D")});
  m.Add(dex::OP_INVOKE_STATIC_RANGE,
        {m.RegRange(0, 7), m.MethodRef(kHelper, "m", "(ZIIIFD)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectAllIgnored(m);
}

// In release mode, D8 merges the methods that it generates into one class,
// and renames this one m$1 when that class already has a method m(II)I. It also
// reads y into the register of `this`, which it no longer needs.
TEST_F(RecordsFilterTest, ShouldFilterHashCodeWhoseMethodD8Renamed) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_IGET,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "x", "I")});
  m.Add(dex::OP_IGET,
        {m.Reg(1), m.Reg(1), m.FieldRef(kTestClassName, "y", "I")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0, 1}), m.MethodRef("LFoo$4;", "m$1", "(II)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(1)});
  m.Add(dex::OP_RETURN, {m.Reg(1)});

  Filter(m);

  ExpectAllIgnored(m);
}

// When the agent grows the frame of hashCode(), `this` arrives in v3, and the
// agent first copies it back to v2.
TEST_F(RecordsFilterTest, ShouldFilterHashCodeWithCopyOfAgent) {
  TestMethod m("hashCode", "()I");
  lir::Instruction* copy = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(2), m.Reg(3)});
  Begin(m, /*registers=*/4, /*ins=*/1);
  AddHashCode(m);

  Filter(m);

  ExpectIgnored(m, {{copy, m.Last()}});
}

// record Foo() {}
// D8 returns 0.
TEST_F(RecordsFilterTest, ShouldFilterHashCodeOfRecordWithoutComponents) {
  TestMethod m("hashCode", "()I");
  DeclareFields(m, {});
  Begin(m, /*registers=*/2, /*ins=*/1);
  AddHashCodeOfRecordWithoutComponents(m);

  Filter(m);

  ExpectAllIgnored(m);
}

// record Foo() {}
// When the agent grows the frame, `this` arrives in v2, and the agent first
// copies it back to v1.
TEST_F(RecordsFilterTest,
       ShouldFilterHashCodeOfRecordWithoutComponentsWithCopyOfAgent) {
  TestMethod m("hashCode", "()I");
  DeclareFields(m, {});
  lir::Instruction* copy = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(1), m.Reg(2)});
  Begin(m, /*registers=*/3, /*ins=*/1);
  AddHashCodeOfRecordWithoutComponents(m);

  Filter(m);

  ExpectIgnored(m, {{copy, m.Last()}});
}

// public int hashCode() { return Integer.hashCode(x); }
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeWrittenInSource) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_IGET,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "x", "I")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0}), m.MethodRef("Ljava/lang/Integer;", "hashCode", "(I)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// The method of hashCode() has a name that D8 doesn't give it.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeWhoseMethodHasOtherName) {
  for (const char* name : {"n", "mm", "m$", "m$a", "m$1a"}) {
    SCOPED_TRACE(name);
    output_ = Output();
    HashCodeShape shape;
    shape.helper = name;
    ExpectHashCodeNotFiltered(shape);
  }
}

// record Foo(long value) {
//   @Override
//   public int hashCode() { return Long.hashCode(value); }
// }
// Below API level 24, D8 replaces Long.hashCode(long) with a method m that it
// generates, which makes the code look like the generated hashCode(). Only
// the generated one is final.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatCallsBackport) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/3, /*ins=*/1);
  m.ir_method()->access_flags = dex::kAccPublic;
  m.Add(dex::OP_IGET_WIDE,
        {Pair(m, 0), m.Reg(2), m.FieldRef(kTestClassName, "value", "J")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0, 1}), m.MethodRef(kHelper, "m", "(J)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo() {
//   @Override
//   public int hashCode() { return 0; }
// }
TEST_F(RecordsFilterTest,
       ShouldNotFilterHashCodeThatReturnsZeroWrittenInSource) {
  TestMethod m("hashCode", "()I");
  DeclareFields(m, {});
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.ir_method()->access_flags = dex::kAccPublic;
  AddHashCodeOfRecordWithoutComponents(m);

  Filter(m);

  ExpectIgnored(m, {});
}

// public int hashCode() { return Util.m(); }
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatPassesNoField) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_INVOKE_STATIC, {m.Regs({}), m.MethodRef("LUtil;", "m", "()I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo(Foo next, int x) {
//   public int hashCode() { return Util.m(next, next.x); }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatReadsFieldOfOtherObject) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/3, /*ins=*/1);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(2), m.FieldRef(kTestClassName, "next", "LFoo;")});
  m.Add(dex::OP_IGET,
        {m.Reg(1), m.Reg(0), m.FieldRef(kTestClassName, "x", "I")});
  m.Add(dex::OP_INVOKE_STATIC,
        {m.Regs({0, 1}), m.MethodRef("LUtil;", "m", "(Ljava/lang/Object;I)I")});
  m.Add(dex::OP_MOVE_RESULT, {m.Reg(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// The code writes x instead of reading it.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatWritesField) {
  HashCodeShape shape;
  shape.read = dex::OP_IPUT;
  ExpectHashCodeNotFiltered(shape);
}

// The code calls the method with invoke-virtual.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeWithVirtualCall) {
  HashCodeShape shape;
  shape.call = dex::OP_INVOKE_VIRTUAL;
  ExpectHashCodeNotFiltered(shape);
}

// public int hashCode() { return m(x, y); }
// private static int m(int x, int y) { ... }
// D8 never generates the method in the record.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatCallsMethodOfRecord) {
  HashCodeShape shape;
  shape.helper_class = kTestClassName;
  ExpectHashCodeNotFiltered(shape);
}

// The code calls another method than m.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatCallsOtherMethod) {
  HashCodeShape shape;
  shape.helper = "hash";
  ExpectHashCodeNotFiltered(shape);
}

// The method takes y as a boolean.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeWhoseMethodTakesOtherTypes) {
  HashCodeShape shape;
  shape.helper_signature = "(IZ)I";
  ExpectHashCodeNotFiltered(shape);
}

// public int hashCode() { return Foo$0.m(x, x); }
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatPassesOtherValues) {
  HashCodeShape shape;
  shape.arguments = {0, 0};
  ExpectHashCodeNotFiltered(shape);
}

// The code returns 1 whatever the result.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatDiscardsResult) {
  HashCodeShape shape;
  shape.moves_result = false;
  ExpectHashCodeNotFiltered(shape);
}

// The code returns y instead of the result.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatReturnsOtherRegister) {
  HashCodeShape shape;
  shape.returned = 1;
  ExpectHashCodeNotFiltered(shape);
}

// record Foo(int x) {
//   public int hashCode() { return 0; }
// }
TEST_F(RecordsFilterTest,
       ShouldNotFilterHashCodeThatReturnsZeroInRecordWithComponents) {
  TestMethod m("hashCode", "()I");
  DeclareFields(m, {"x"});
  Begin(m, /*registers=*/2, /*ins=*/1);
  AddHashCodeOfRecordWithoutComponents(m);

  Filter(m);

  ExpectIgnored(m, {});
}

// Without the class definition of the record, its fields are unknown.
TEST_F(RecordsFilterTest,
       ShouldNotFilterHashCodeThatReturnsZeroWithoutClassDefinition) {
  TestMethod m("hashCode", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  AddHashCodeOfRecordWithoutComponents(m);

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo() {
//   public int hashCode() { return 1; }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatReturnsOtherConstant) {
  TestMethod m("hashCode", "()I");
  DeclareFields(m, {});
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(1)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo() {
//   static int count;
//   public int hashCode() { return count; }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatReturnsStaticField) {
  TestMethod m("hashCode", "()I");
  DeclareFields(m, {});
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_SGET, {m.Reg(0), m.FieldRef(kTestClassName, "count", "I")});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// In a record without components, the code returns another register than the
// 0 that it loads.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatDoesNotReturnItsZero) {
  TestMethod m("hashCode", "()I");
  DeclareFields(m, {});
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN, {m.Reg(1)});

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo() {
//   public int hashCode() { throw null; }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatThrowsNull) {
  TestMethod m("hashCode", "()I");
  DeclareFields(m, {});
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_CONST_4, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_THROW, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// D8 loads the 0 with const/4. A const/16 isn't its code.
TEST_F(RecordsFilterTest, ShouldNotFilterHashCodeThatLoadsZeroWithConst16) {
  TestMethod m("hashCode", "()I");
  DeclareFields(m, {});
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_CONST_16, {m.Reg(0), m.Literal(0)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo() {
//   public int count() { return 0; }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterOtherMethodThatReturnsZero) {
  TestMethod m("count", "()I");
  DeclareFields(m, {});
  Begin(m, /*registers=*/2, /*ins=*/1);
  AddHashCodeOfRecordWithoutComponents(m);

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo() {
//   public int hashCode(int seed) { return 0; }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterOverloadOfHashCode) {
  TestMethod m("hashCode", "(I)I");
  DeclareFields(m, {});
  Begin(m, /*registers=*/3, /*ins=*/2);
  AddHashCodeOfRecordWithoutComponents(m);

  Filter(m);

  ExpectIgnored(m, {});
}

// Accessors.

// record Foo(int x, long id, String name, boolean done) {}
// Each accessor reads its field and returns it.
TEST_F(RecordsFilterTest, ShouldFilterAccessors) {
  struct Accessor {
    const char* name;
    const char* signature;
    const char* type;
    dex::Opcode get;
    dex::Opcode ret;
  };
  for (const Accessor& accessor :
       {Accessor{"x", "()I", "I", dex::OP_IGET, dex::OP_RETURN},
        Accessor{"id", "()J", "J", dex::OP_IGET_WIDE, dex::OP_RETURN_WIDE},
        Accessor{"name", "()Ljava/lang/String;", kString, dex::OP_IGET_OBJECT,
                 dex::OP_RETURN_OBJECT},
        Accessor{"done", "()Z", "Z", dex::OP_IGET_BOOLEAN, dex::OP_RETURN}}) {
    SCOPED_TRACE(accessor.name);
    output_ = Output();
    // A wide value takes v0 and v1, and then `this` arrives in v2.
    const dex::u4 self = accessor.get == dex::OP_IGET_WIDE ? 2 : 1;
    TestMethod m(accessor.name, accessor.signature);
    Begin(m, /*registers=*/static_cast<dex::u2>(self + 1), /*ins=*/1);
    AddAccessor(m, accessor.name, accessor.type, accessor.get, accessor.ret,
                self);

    Filter(m);

    ExpectAllIgnored(m);
  }
}

// When the agent grows the frame of x(), `this` arrives in v2, and the agent
// first copies it back to v1.
TEST_F(RecordsFilterTest, ShouldFilterAccessorWithCopyOfAgent) {
  TestMethod m("x", "()I");
  lir::Instruction* copy = m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(1), m.Reg(2)});
  Begin(m, /*registers=*/3, /*ins=*/1);
  AddAccessor(m, "x", "I", dex::OP_IGET, dex::OP_RETURN, /*self=*/1);

  Filter(m);

  ExpectIgnored(m, {{copy, m.Last()}});
}

// In release mode, D8 reads the field into the register of `this`.
TEST_F(RecordsFilterTest, ShouldFilterAccessorOfReleaseMode) {
  TestMethod m("x", "()I");
  Begin(m, /*registers=*/1, /*ins=*/1);
  AddAccessor(m, "x", "I", dex::OP_IGET, dex::OP_RETURN, /*self=*/0);

  Filter(m);

  ExpectAllIgnored(m);
}

// record Foo(String name) {
//   public String name() { return name.toUpperCase(); }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterAccessorThatDoesMore) {
  TestMethod m("name", "()Ljava/lang/String;");
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "name", kString)});
  m.Add(dex::OP_INVOKE_VIRTUAL,
        {m.Regs({0}), m.MethodRef(kString, "toUpperCase", kToString)});
  m.Add(dex::OP_MOVE_RESULT_OBJECT, {m.Reg(0)});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// The method must be exactly the code of an accessor, even with code after the
// return that nothing reaches.
TEST_F(RecordsFilterTest, ShouldNotFilterAccessorWithMoreCode) {
  TestMethod m("x", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  AddAccessor(m, "x", "I", dex::OP_IGET, dex::OP_RETURN, /*self=*/1);
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// Only the copies before the code are the ones of the agent.
TEST_F(RecordsFilterTest, ShouldNotFilterAccessorWithMoveInside) {
  TestMethod m("x", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_IGET,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "x", "I")});
  m.Add(dex::OP_MOVE_OBJECT_16, {m.Reg(1), m.Reg(1)});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// public int size() { return x; }
TEST_F(RecordsFilterTest, ShouldNotFilterMethodThatReturnsOtherField) {
  TestMethod m("size", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  AddAccessor(m, "x", "I", dex::OP_IGET, dex::OP_RETURN, /*self=*/1);

  Filter(m);

  ExpectIgnored(m, {});
}

// public int x(int unused) { return x; }
TEST_F(RecordsFilterTest, ShouldNotFilterMethodWithParameters) {
  TestMethod m("x", "(I)I");
  Begin(m, /*registers=*/3, /*ins=*/2);
  AddAccessor(m, "x", "I", dex::OP_IGET, dex::OP_RETURN, /*self=*/1);

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo(Foo next) {
//   public Foo next() {
//     Foo unused = next;
//     return this;
//   }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterAccessorThatReturnsThis) {
  TestMethod m("next", "()LFoo;");
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1), m.FieldRef(kTestClassName, "next", "LFoo;")});
  m.Add(dex::OP_RETURN_OBJECT, {m.Reg(1)});

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo(RuntimeException error) {
//   public RuntimeException error() { throw error; }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterAccessorThatThrowsField) {
  constexpr char kRuntimeException[] = "Ljava/lang/RuntimeException;";
  TestMethod m("error", "()Ljava/lang/RuntimeException;");
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_IGET_OBJECT,
        {m.Reg(0), m.Reg(1),
         m.FieldRef(kTestClassName, "error", kRuntimeException)});
  m.Add(dex::OP_THROW, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// record Foo(int x) {
//   static int count;
//   public int count() { return count; }
// }
TEST_F(RecordsFilterTest, ShouldNotFilterMethodThatReturnsStaticField) {
  TestMethod m("count", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  m.Add(dex::OP_SGET, {m.Reg(0), m.FieldRef(kTestClassName, "count", "I")});
  m.Add(dex::OP_RETURN, {m.Reg(0)});

  Filter(m);

  ExpectIgnored(m, {});
}

// The classes that the filter handles.

// class Foo { int x; int x() { return x; } }
// Only the methods of records are filtered.
TEST_F(RecordsFilterTest, ShouldNotFilterMethodsOfOtherClasses) {
  context_.super_class_name = "Ljava/lang/Object;";
  TestMethod m("x", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  AddAccessor(m, "x", "I", dex::OP_IGET, dex::OP_RETURN, /*self=*/1);

  Filter(m);

  ExpectIgnored(m, {});
}

TEST_F(RecordsFilterTest, ShouldRunOnJavaClasses) {
  TestMethod m("equals", kEquals);
  Begin(m, /*registers=*/3, /*ins=*/2);
  AddEquals(m);

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(m.Last()));
}

// Kotlin classes can be records too, with @JvmRecord, so the filter runs on
// them as well.
TEST_F(RecordsFilterTest, ShouldRunOnKotlinClasses) {
  context_.class_annotations.insert(kKotlinMetadataDescriptor);
  TestMethod m("x", "()I");
  Begin(m, /*registers=*/2, /*ins=*/1);
  AddAccessor(m, "x", "I", dex::OP_IGET, dex::OP_RETURN, /*self=*/1);

  FilterResult result =
      SyntheticFilter::Apply(context_, m.ir_method(), m.code_ir(), {});

  EXPECT_TRUE(result.IsIgnored(m.Last()));
}

}  // namespace
}  // namespace coverage
