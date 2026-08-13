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

#include "tools/base/android-test/coverage/agent/native/hits_extractor.h"
#include "tools/base/android-test/coverage/agent/native/instrumenter.h"

#include <gtest/gtest.h>

namespace coverage {

/**
 * Host-side unit tests for the HitsExtractor packing logic.
 */
TEST(HitsExtractorTest, PackHitsSimple) {
  // 10101010 in binary (LSB first) -> 0x55
  jboolean hits[] = {true, false, true, false, true, false, true, false};
  uint32_t last_hit_index = 0;
  std::string bitmask = HitsExtractor::PackHits(hits, 8, &last_hit_index);

  ASSERT_EQ(bitmask.size(), 1);
  EXPECT_EQ(static_cast<uint8_t>(bitmask[0]), 0x55);
  EXPECT_EQ(last_hit_index, 6);
}

TEST(HitsExtractorTest, PackHitsPruning) {
  // 16 booleans, but only the 3rd one (index 2) is true.
  jboolean hits[16] = {false};
  hits[2] = true;

  uint32_t last_hit_index = 0;
  std::string bitmask = HitsExtractor::PackHits(hits, 16, &last_hit_index);

  // Should be pruned to 1 byte since the last hit is at index 2.
  EXPECT_EQ(bitmask.size(), 1);
  EXPECT_EQ(static_cast<uint8_t>(bitmask[0]), 0x04);
  EXPECT_EQ(last_hit_index, 2);
}

TEST(HitsExtractorTest, PackHitsMultiByte) {
  jboolean hits[20] = {false};
  hits[1] = true;   // Byte 0, Bit 1 -> 0x02
  hits[10] = true;  // Byte 1, Bit 2 -> 0x04

  uint32_t last_hit_index = 0;
  std::string bitmask = HitsExtractor::PackHits(hits, 20, &last_hit_index);

  // Pruned to 2 bytes (last hit is at index 10, which is in the 2nd byte).
  ASSERT_EQ(bitmask.size(), 2);
  EXPECT_EQ(static_cast<uint8_t>(bitmask[0]), 0x02);
  EXPECT_EQ(static_cast<uint8_t>(bitmask[1]), 0x04);
  EXPECT_EQ(last_hit_index, 10);
}

TEST(HitsExtractorTest, PackHitsEmpty) {
  jboolean hits[10] = {false};
  uint32_t last_hit_index = 0;
  std::string bitmask = HitsExtractor::PackHits(hits, 10, &last_hit_index);

  EXPECT_TRUE(bitmask.empty());
}

TEST(InstrumenterTest, IsSyntheticOrCompilerGenerated) {
  // 1. Standard user-written outer classes (Should NOT be filtered)
  EXPECT_FALSE(
      Instrumenter::IsSyntheticOrCompilerGenerated("com/example/MyClass"));
  EXPECT_FALSE(
      Instrumenter::IsSyntheticOrCompilerGenerated("com/example/MyViewModel"));
  EXPECT_FALSE(
      Instrumenter::IsSyntheticOrCompilerGenerated("com/example/MyClass2"));

  // 2. User-written nested & inner classes (Should NOT be filtered)
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$MyInnerClass"));
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$InnerHelper2"));
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass2$InnerClass"));
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$InnerClass2"));

  // 3. User-written inner classes containing "Lambda" or "Sam" (Should NOT be
  // filtered)
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyLambdaClass"));
  EXPECT_FALSE(
      Instrumenter::IsSyntheticOrCompilerGenerated("com/example/SamClass"));
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$MyLambdaHelper"));
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$SamHelper"));

  // 4. Java anonymous inner classes and Kotlin numbered closures (Should be
  // instrumented / not filtered)
  EXPECT_FALSE(
      Instrumenter::IsSyntheticOrCompilerGenerated("com/example/MyClass$1"));
  EXPECT_FALSE(
      Instrumenter::IsSyntheticOrCompilerGenerated("com/example/MyClass$2"));
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$test$1"));

  // 5. Kotlin Coroutine State Machines / Suspend Lambdas (Should be
  // instrumented / not filtered)
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/ScrollbarExtKt$scrollbarState$6$1"));
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyViewModel$fetchData$2$1"));

  // 6. Kotlin Compiler Synthetic Lambdas, SAMs, and Inlined Helpers (Lambdas
  // should be instrumented)
  EXPECT_FALSE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$lambda-0"));
  EXPECT_TRUE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$sam$0"));
  EXPECT_TRUE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$sam$i$org_junit_Test$0"));
  EXPECT_TRUE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$inlined$0"));
  EXPECT_TRUE(Instrumenter::IsSyntheticOrCompilerGenerated(
      "com/example/MyClass$inlined$MyHelper$1"));
}

}  // namespace coverage
