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

#include "tools/base/android-test/coverage/agent/native/metadata_collector.h"

#include <gtest/gtest.h>

namespace coverage {

/**
 * Host-side unit tests for the MetadataCollector.
 */
class MetadataCollectorTest : public ::testing::Test {
 protected:
  // Each test uses its own fresh, isolated instance of the collector.
  MetadataCollector collector_;
};

TEST_F(MetadataCollectorTest, AggregationLogic) {
  collector_.Initialize("com.example.test");

  // 1. Add a Class
  auto* class_meta =
      collector_.AddClass("com/example/MyClass", "MyClass.kt", "SMAP...");
  ASSERT_NE(class_meta, nullptr);
  EXPECT_EQ(class_meta->class_name(), "com/example/MyClass");
  EXPECT_EQ(class_meta->source_file(), "MyClass.kt");
  EXPECT_EQ(class_meta->smap(), "SMAP...");

  // 2. Add a Method
  auto* method_meta = collector_.AddMethod(class_meta, "myMethod", "(I)V");
  ASSERT_NE(method_meta, nullptr);
  EXPECT_EQ(method_meta->name(), "myMethod");
  EXPECT_EQ(method_meta->signature(), "(I)V");

  EXPECT_EQ(class_meta->class_name(), "com/example/MyClass");

  // 3. Add a Block with multiple lines and instruction counts
  std::vector<std::pair<int32_t, uint32_t>> lines = {{10, 5}, {11, 3}};
  collector_.AddBlock(method_meta, 101, lines, 2);

  // 4. Verify the entire hierarchy via the public metadata() getter.
  const auto& metadata = collector_.metadata();
  EXPECT_EQ(metadata.version(), 1);
  ASSERT_EQ(metadata.classes_size(), 1);

  const auto& c = metadata.classes(0);
  EXPECT_EQ(c.class_name(), "com/example/MyClass");
  ASSERT_EQ(c.methods_size(), 1);

  const auto& m = c.methods(0);
  EXPECT_EQ(m.name(), "myMethod");
  ASSERT_EQ(m.blocks_size(), 1);

  const auto& b = m.blocks(0);
  EXPECT_EQ(b.block_id(), 101u);
  EXPECT_EQ(b.branch_count(), 2u);
  ASSERT_EQ(b.lines_size(), 2);

  EXPECT_EQ(b.lines(0).line_number(), 10);
  EXPECT_EQ(b.lines(0).instruction_count(), 5);
  EXPECT_EQ(b.lines(1).line_number(), 11);
  EXPECT_EQ(b.lines(1).instruction_count(), 3);
}

TEST_F(MetadataCollectorTest, MultiClassAggregation) {
  collector_.Initialize("com.example.test");

  collector_.AddClass("com/example/ClassA", "A.java", "");
  collector_.AddClass("com/example/ClassB", "B.java", "");

  const auto& metadata = collector_.metadata();
  EXPECT_EQ(metadata.classes_size(), 2);
  EXPECT_EQ(metadata.classes(0).class_name(), "com/example/ClassA");
  EXPECT_EQ(metadata.classes(1).class_name(), "com/example/ClassB");
}

TEST_F(MetadataCollectorTest, IsolationVerification) {
  // This test proves that the instance is indeed fresh and not a singleton.
  const auto& metadata = collector_.metadata();
  EXPECT_EQ(metadata.classes_size(), 0);
}

}  // namespace coverage
