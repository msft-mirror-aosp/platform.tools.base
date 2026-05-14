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

#include "utf8_utils.h"
#include <gtest/gtest.h>

namespace {

TEST(Utf8UtilsTest, SanitizeUtf8_ValidAscii) {
  EXPECT_EQ(sherlock::SanitizeUtf8("hello"), "hello");
}

TEST(Utf8UtilsTest, SanitizeUtf8_InvalidUtf8_OrphanContinuation) {
  unsigned char invalid[] = {0x80, 0};
  EXPECT_EQ(sherlock::SanitizeUtf8(reinterpret_cast<const char*>(invalid)),
            "?");
}

TEST(Utf8UtilsTest, SanitizeUtf8_ValidEmoji) {
  unsigned char emoji[] = {0xF0, 0x9F, 0x8E, 0xA1, 0};
  EXPECT_EQ(sherlock::SanitizeUtf8(reinterpret_cast<const char*>(emoji)),
            reinterpret_cast<const char*>(emoji));
}

TEST(Utf8UtilsTest, SanitizeUtf8_BrokenEmoji) {
  unsigned char broken[] = {0xF0, 0x9F, 0x8E, 0};
  EXPECT_EQ(sherlock::SanitizeUtf8(reinterpret_cast<const char*>(broken)),
            "???");
}

TEST(Utf8UtilsTest, SanitizeUtf8_MixedValidAndInvalid) {
  unsigned char mixed[] = {'a', 0x80, 'b', 0};
  EXPECT_EQ(sherlock::SanitizeUtf8(reinterpret_cast<const char*>(mixed)),
            "a?b");
}

TEST(Utf8UtilsTest, SanitizeUtf8_OriginalBugSequence) {
  unsigned char bug_seq[] = {0xF6, 0xED, 0};
  EXPECT_EQ(sherlock::SanitizeUtf8(reinterpret_cast<const char*>(bug_seq)),
            "??");
}

TEST(Utf8UtilsTest, SanitizeUtf8_OverlongEncoding_2Byte) {
  unsigned char overlong[] = {0xC0, 0x80, 0};
  EXPECT_EQ(sherlock::SanitizeUtf8(reinterpret_cast<const char*>(overlong)),
            "??");
}

TEST(Utf8UtilsTest, SanitizeUtf8_OverlongEncoding_3Byte) {
  unsigned char overlong[] = {0xE0, 0x9F, 0xBF, 0};
  EXPECT_EQ(sherlock::SanitizeUtf8(reinterpret_cast<const char*>(overlong)),
            "???");
}

TEST(Utf8UtilsTest, SanitizeUtf8_Surrogate) {
  unsigned char surrogate[] = {0xED, 0xA0, 0x80, 0};
  EXPECT_EQ(sherlock::SanitizeUtf8(reinterpret_cast<const char*>(surrogate)),
            "???");
}

TEST(Utf8UtilsTest, GetValidSequenceLength_Ascii) {
  EXPECT_EQ(sherlock::GetValidSequenceLength("hello"), 1);
}

TEST(Utf8UtilsTest, GetValidSequenceLength_ValidEmoji) {
  unsigned char emoji[] = {0xF0, 0x9F, 0x8E, 0xA1, 0};
  EXPECT_EQ(
      sherlock::GetValidSequenceLength(reinterpret_cast<const char*>(emoji)),
      4);
}

TEST(Utf8UtilsTest, GetValidSequenceLength_Invalid) {
  unsigned char invalid[] = {0x80, 0};
  EXPECT_EQ(
      sherlock::GetValidSequenceLength(reinterpret_cast<const char*>(invalid)),
      0);
}

}  // namespace
