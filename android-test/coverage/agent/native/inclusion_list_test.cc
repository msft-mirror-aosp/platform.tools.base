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

#include "tools/base/android-test/coverage/agent/native/inclusion_list.h"

#include <fstream>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include <gtest/gtest.h>
#include "tools/base/android-test/coverage/agent/native/instrumenter.h"

namespace coverage {
namespace {

// Returns the contents of a class list file with `class_names`.
std::string ListFile(const std::vector<std::string_view>& class_names) {
  std::string contents =
      "#coverage-classes v1 count=" + std::to_string(class_names.size()) + "\n";
  for (std::string_view class_name : class_names) {
    contents.append(class_name).append("\n");
  }
  return contents + "#end\n";
}

// Returns the class list with `class_names`.
InclusionList ListOf(const std::vector<std::string_view>& class_names) {
  InclusionListOrError parsed = InclusionList::Parse(ListFile(class_names));
  EXPECT_TRUE(parsed.list.has_value()) << parsed.error;
  return std::move(*parsed.list);
}

// Writes `contents` to the file `name` in the test's temporary directory and
// returns its path.
std::string WriteTempFile(const std::string& name, std::string_view contents) {
  std::string path = testing::TempDir() + "/" + name;
  std::ofstream file(path, std::ios::binary | std::ios::trunc);
  file << contents;
  return path;
}

TEST(InclusionListTest, ParsesTheClasses) {
  InclusionListOrError parsed = InclusionList::Parse(
      ListFile({"com/example/Foo", "com/example/Foo$Bar", "hilt/Baz"}));

  ASSERT_TRUE(parsed.list.has_value()) << parsed.error;
  EXPECT_EQ(parsed.list->size(), 3u);
  EXPECT_TRUE(parsed.list->Contains("com/example/Foo"));
  EXPECT_TRUE(parsed.list->Contains("com/example/Foo$Bar"));
  EXPECT_TRUE(parsed.list->Contains("hilt/Baz"));
  EXPECT_FALSE(parsed.list->Contains("com/example"));
  EXPECT_FALSE(parsed.list->Contains("com/example/Fo"));
  EXPECT_FALSE(parsed.list->Contains("com/example/Foo$Baz"));
}

TEST(InclusionListTest, AcceptsWindowsLineEndings) {
  InclusionListOrError parsed = InclusionList::Parse(
      "#coverage-classes v1 count=2\r\ncom/example/Foo\r\ncom/example/Bar\r\n"
      "#end\r\n");

  ASSERT_TRUE(parsed.list.has_value()) << parsed.error;
  EXPECT_TRUE(parsed.list->Contains("com/example/Foo"));
  EXPECT_TRUE(parsed.list->Contains("com/example/Bar"));
}

TEST(InclusionListTest, AcceptsAMissingFinalNewline) {
  InclusionListOrError parsed = InclusionList::Parse(
      "#coverage-classes v1 count=1\ncom/example/Foo\n#end");

  ASSERT_TRUE(parsed.list.has_value()) << parsed.error;
  EXPECT_TRUE(parsed.list->Contains("com/example/Foo"));
}

TEST(InclusionListTest, IgnoresDuplicates) {
  InclusionListOrError parsed =
      InclusionList::Parse(ListFile({"com/example/Foo", "com/example/Foo"}));

  ASSERT_TRUE(parsed.list.has_value()) << parsed.error;
  EXPECT_EQ(parsed.list->size(), 1u);
  EXPECT_TRUE(parsed.list->Contains("com/example/Foo"));
}

TEST(InclusionListTest, RejectsAnEmptyFile) {
  EXPECT_FALSE(InclusionList::Parse("").list.has_value());
}

TEST(InclusionListTest, RejectsAListWithoutHeader) {
  EXPECT_FALSE(
      InclusionList::Parse("com/example/Foo\n#end\n").list.has_value());
}

TEST(InclusionListTest, RejectsAnUnknownVersion) {
  InclusionListOrError parsed = InclusionList::Parse(
      "#coverage-classes v2 count=1\ncom/example/Foo\n#end\n");

  EXPECT_FALSE(parsed.list.has_value());
  EXPECT_NE(parsed.error.find("v2"), std::string::npos) << parsed.error;
}

TEST(InclusionListTest, RejectsAnInvalidCount) {
  EXPECT_FALSE(
      InclusionList::Parse("#coverage-classes v1\ncom/example/Foo\n#end\n")
          .list.has_value());
  EXPECT_FALSE(InclusionList::Parse(
                   "#coverage-classes v1 count=x\ncom/example/Foo\n#end\n")
                   .list.has_value());
  EXPECT_FALSE(InclusionList::Parse(
                   "#coverage-classes v1 count=-1\ncom/example/Foo\n#end\n")
                   .list.has_value());
}

TEST(InclusionListTest, RejectsACountThatDoesNotMatch) {
  EXPECT_FALSE(InclusionList::Parse(
                   "#coverage-classes v1 count=2\ncom/example/Foo\n#end\n")
                   .list.has_value());
}

TEST(InclusionListTest, RejectsATruncatedList) {
  std::string contents = ListFile({"com/example/Foo", "com/example/Bar"});
  // Cut the end marker and part of the last class off.
  contents.resize(contents.find("Bar"));

  InclusionListOrError parsed = InclusionList::Parse(contents);

  EXPECT_FALSE(parsed.list.has_value());
  EXPECT_NE(parsed.error.find("end marker"), std::string::npos) << parsed.error;
}

TEST(InclusionListTest, RejectsContentAfterTheEndMarker) {
  EXPECT_FALSE(InclusionList::Parse(ListFile({"com/example/Foo"}) + "x\n")
                   .list.has_value());
}

TEST(InclusionListTest, AcceptsEmptyLinesAfterTheEndMarker) {
  EXPECT_TRUE(InclusionList::Parse(ListFile({"com/example/Foo"}) + "\n\n")
                  .list.has_value());
}

TEST(InclusionListTest, RejectsAnEmptyLineBetweenClasses) {
  EXPECT_FALSE(InclusionList::Parse(
                   "#coverage-classes v1 count=2\ncom/example/Foo\n\n#end\n")
                   .list.has_value());
}

TEST(InclusionListTest, RejectsAListWithoutClasses) {
  EXPECT_FALSE(InclusionList::Parse(ListFile({})).list.has_value());
}

TEST(InclusionListTest, LoadsAFile) {
  std::string path =
      WriteTempFile("valid_classes.txt", ListFile({"com/example/Foo"}));

  InclusionListOrError loaded = InclusionList::Load(path);

  ASSERT_TRUE(loaded.list.has_value()) << loaded.error;
  EXPECT_TRUE(loaded.list->Contains("com/example/Foo"));
}

TEST(InclusionListTest, ReportsAMissingFile) {
  InclusionListOrError loaded =
      InclusionList::Load(testing::TempDir() + "/missing_classes.txt");

  EXPECT_FALSE(loaded.list.has_value());
  EXPECT_FALSE(loaded.file_found);
}

TEST(InclusionListTest, ReportsAnInvalidFile) {
  std::string path = WriteTempFile("invalid_classes.txt", "com/example/Foo\n");

  InclusionListOrError loaded = InclusionList::Load(path);

  EXPECT_FALSE(loaded.list.has_value());
  EXPECT_TRUE(loaded.file_found);
}

TEST(InclusionListTest, ReportsAFileThatCantBeRead) {
  // A directory opens, but can't be read.
  InclusionListOrError loaded = InclusionList::Load(testing::TempDir());

  EXPECT_FALSE(loaded.list.has_value());
  EXPECT_TRUE(loaded.file_found);
  EXPECT_EQ(loaded.error.rfind("can't read ", 0), 0u) << loaded.error;
}

TEST(InclusionListTest, InstrumenterMatchesOnlyTheListedClasses) {
  Instrumenter instrumenter(
      /*jvmti=*/nullptr, "com/example",
      ListOf({"com/example/Foo", "hilt_aggregated_deps/_com_example_FooModule",
              "com/example/Foo$special$$inlined$map$1",
              "com/android/tools/coverage/CoverageTracker"}));

  // A listed class, even outside the prefix.
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example/Foo"));
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter(
      "hilt_aggregated_deps/_com_example_FooModule"));
  // A listed class, even with a name that the prefix filter skips.
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter(
      "com/example/Foo$special$$inlined$map$1"));
  // Classes with the prefix that aren't listed: test classes, and the classes
  // that d8 creates.
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter("com/example/FooTest"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter(
      "com/example/Foo$$ExternalSyntheticLambda0"));
  // Never the coverage runtime.
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter(
      "com/android/tools/coverage/CoverageTracker"));
}

TEST(InclusionListTest, InstrumenterMatchesTheCompanionClassOfAListedClass) {
  Instrumenter instrumenter(
      /*jvmti=*/nullptr, "com/example",
      ListOf({"com/example/Api", "hilt_aggregated_deps/_com_example_Api",
              "com/example/Listed$-CC"}));

  // Below API 24, d8 moves the default, static and private methods of an
  // interface to its companion class, which the prefix matches too.
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example/Api$-CC"));
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter(
      "hilt_aggregated_deps/_com_example_Api$-CC"));
  // A companion class in the list, without its interface.
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example/Listed$-CC"));
  // Not the companion class of a class that isn't listed.
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter("com/example/Other$-CC"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter("$-CC"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter("com/example/Api$-CC$1"));
}

TEST(InclusionListTest, InstrumenterWithoutListMatchesThePrefix) {
  Instrumenter instrumenter(/*jvmti=*/nullptr, "com/example");

  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example/Foo"));
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example/Foo$Bar"));
  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example/Foo$-CC"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter("com/examples/Foo"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter(
      "hilt_aggregated_deps/_com_example_FooModule"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter(
      "com/example/Foo$special$$inlined$map$1"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter(
      "com/android/tools/coverage/CoverageTracker"));
}

TEST(InclusionListTest, InstrumenterHasAFilterWithAListOrAPrefix) {
  EXPECT_TRUE(Instrumenter(/*jvmti=*/nullptr, "", ListOf({"com/example/Foo"}))
                  .HasInclusionFilter());
  EXPECT_TRUE(
      Instrumenter(/*jvmti=*/nullptr, "com/example").HasInclusionFilter());
  EXPECT_FALSE(Instrumenter(/*jvmti=*/nullptr, "").HasInclusionFilter());
}

TEST(InclusionListTest, InstrumenterWithListAndNoPrefixMatchesTheList) {
  Instrumenter instrumenter(/*jvmti=*/nullptr, "", ListOf({"com/example/Foo"}));

  EXPECT_TRUE(instrumenter.MatchesInclusionFilter("com/example/Foo"));
  EXPECT_FALSE(instrumenter.MatchesInclusionFilter("com/example/Bar"));
}

}  // namespace
}  // namespace coverage
