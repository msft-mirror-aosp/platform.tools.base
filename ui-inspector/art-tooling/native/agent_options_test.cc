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

#include "agent_options.h"

#include <string>

#include "art_tooling_constants.h"
#include "gtest/gtest.h"

namespace art_tooling {
namespace {

TEST(AgentOptionsTest, ParsesAllFourFields) {
  auto parsed = ParseAgentOptions("lib.jar;agent.jar;com.example.Agent;1234");
  ASSERT_TRUE(parsed.has_value());
  EXPECT_EQ(parsed->library_dex, "lib.jar");
  EXPECT_EQ(parsed->agent_dex, "agent.jar");
  EXPECT_EQ(parsed->agent_class, "com.example.Agent");
  EXPECT_EQ(parsed->agent_options, "1234");
}

TEST(AgentOptionsTest, AllowsEmptyAgentOptions) {
  auto parsed = ParseAgentOptions("lib.jar;agent.jar;com.example.Agent;");
  ASSERT_TRUE(parsed.has_value());
  EXPECT_EQ(parsed->agent_options, "");
}

TEST(AgentOptionsTest, PreservesSemicolonsInAgentOptions) {
  auto parsed =
      ParseAgentOptions("lib.jar;agent.jar;com.example.Agent;one;two;three");
  ASSERT_TRUE(parsed.has_value());
  EXPECT_EQ(parsed->agent_options, "one;two;three");
}

TEST(AgentOptionsTest, RejectsNull) {
  EXPECT_FALSE(ParseAgentOptions(nullptr).has_value());
}

TEST(AgentOptionsTest, RejectsMissingDelimiters) {
  EXPECT_FALSE(ParseAgentOptions("lib.jar").has_value());
  EXPECT_FALSE(ParseAgentOptions("lib.jar;agent.jar").has_value());
  EXPECT_FALSE(ParseAgentOptions("lib.jar;agent.jar;com.example.Agent")
                   .has_value());
}

TEST(AgentOptionsTest, RejectsEmptyLibraryDex) {
  EXPECT_FALSE(
      ParseAgentOptions(";agent.jar;com.example.Agent;1234").has_value());
}

TEST(AgentOptionsTest, RejectsEmptyAgentDex) {
  EXPECT_FALSE(
      ParseAgentOptions("lib.jar;;com.example.Agent;1234").has_value());
}

TEST(AgentOptionsTest, RejectsEmptyAgentClass) {
  EXPECT_FALSE(ParseAgentOptions("lib.jar;agent.jar;;1234").has_value());
}

// Guards the invariant the slicer instrumentation relies on: the callback
// descriptor is the class name in type-descriptor form.
TEST(ArtToolingConstantsTest, DescriptorMatchesClassName) {
  EXPECT_EQ(std::string(kArtToolingClassDescriptor),
            "L" + std::string(kArtToolingClassName) + ";");
}

}  // namespace
}  // namespace art_tooling
