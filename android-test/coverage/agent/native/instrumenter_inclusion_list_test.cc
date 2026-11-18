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

// Tests the classes that never loaded with a class list. This is a test binary
// of its own, so that its metadata only has the classes that it adds.

#include <set>
#include <string>
#include <utility>

#include <gtest/gtest.h>
#include "tools/base/android-test/coverage/agent/native/inclusion_list.h"
#include "tools/base/android-test/coverage/agent/native/instrumenter.h"
#include "tools/base/android-test/coverage/agent/native/metadata_collector.h"

// testdata/com/example/InstrumenterTestTarget.java, compiled to dex.
extern "C" const unsigned char instrumenter_test_target_dex[];
extern "C" const long long instrumenter_test_target_dex_len;

// testdata/com/example/InstrumenterTestKotlinTarget.kt, compiled to dex.
extern "C" const unsigned char instrumenter_test_kotlin_target_dex[];
extern "C" const long long instrumenter_test_kotlin_target_dex_len;

namespace coverage {
namespace {

TEST(InstrumenterInclusionListTest, AddsOnlyTheListedClassesThatNeverLoaded) {
  InclusionListOrError parsed = InclusionList::Parse(
      "#coverage-classes v1 count=4\n"
      "com/example/InstrumenterTestTarget\n"
      "com/example/InstrumenterTestTarget$Command\n"
      "com/example/InstrumenterTestKotlinTarget$Message$$serializer\n"
      "com/example/NotInTheDex\n"
      "#end\n");
  ASSERT_TRUE(parsed.list.has_value()) << parsed.error;
  // The prefix doesn't match the classes, so only the list can.
  Instrumenter instrumenter(/*jvmti=*/nullptr, "com/other",
                            std::move(parsed.list));

  instrumenter.AddUnloadedClasses(instrumenter_test_target_dex,
                                  instrumenter_test_target_dex_len);
  instrumenter.AddUnloadedClasses(instrumenter_test_kotlin_target_dex,
                                  instrumenter_test_kotlin_target_dex_len);

  const proto::CoverageMetadata metadata =
      MetadataCollector::Instance().BuildMetadata();
  std::set<std::string> class_names;
  std::set<std::string> companion_methods;
  for (const auto& class_meta : metadata.classes()) {
    class_names.insert(class_meta.class_name());
    if (class_meta.class_name() ==
        "Lcom/example/InstrumenterTestTarget$Command$-CC;") {
      for (const auto& method : class_meta.methods()) {
        companion_methods.insert(method.name());
      }
    }
  }
  // With `InstrumenterTestTarget$Command$-CC`, where d8 moves the code of the
  // default methods of `Command`, and not the other classes of the dex files,
  // such as the inner classes and the classes of lambdas that d8 creates.
  EXPECT_EQ(
      class_names,
      (std::set<std::string>{
          "Lcom/example/InstrumenterTestTarget;",
          "Lcom/example/InstrumenterTestTarget$Command;",
          "Lcom/example/InstrumenterTestTarget$Command$-CC;",
          "Lcom/example/InstrumenterTestKotlinTarget$Message$$serializer;",
      }));
  // The code of the default methods, and of the lambdas in them, is
  // instrumented, as with the prefix.
  EXPECT_EQ(companion_methods.count("$default$invoke"), 1u);
  EXPECT_EQ(companion_methods.count("$private$lambda$later$0"), 1u);
}

}  // namespace
}  // namespace coverage
