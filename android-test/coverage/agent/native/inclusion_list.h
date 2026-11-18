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

#ifndef COVERAGE_INCLUSION_LIST_H_
#define COVERAGE_INCLUSION_LIST_H_

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace coverage {

struct InclusionListOrError;

/**
 * The classes to instrument: the internal names, such as `com/example/Foo`, of
 * the classes that the tested module compiles, as the build-time report covers
 * them. The test runner puts them in a file in the agent's data directory, in
 * this format:
 *
 *   #coverage-classes v1 count=<number of classes>
 *   com/example/Foo
 *   ...
 *   #end
 *
 * The header and the end marker tell a complete list from a missing, truncated
 * or unknown one, for which the agent falls back to the inclusion prefix.
 */
class InclusionList {
 public:
  // The name of the file in the agent's data directory.
  static constexpr char kFileName[] = "coverage_classes.txt";

  // Parses `contents`, the contents of the file. Lines may end in "\r\n".
  static InclusionListOrError Parse(std::string_view contents);

  // Reads and parses the file at `path`.
  static InclusionListOrError Load(const std::string& path);

  // Returns true if the list has the class with the internal name
  // `class_name`.
  bool Contains(std::string_view class_name) const;

  size_t size() const { return class_names_.size(); }

 private:
  explicit InclusionList(std::vector<std::string> class_names);

  // Sorted, without duplicates.
  std::vector<std::string> class_names_;
};

// An inclusion list, or the reason why there isn't one.
struct InclusionListOrError {
  std::optional<InclusionList> list;
  // Why `list` is empty.
  std::string error;
  // False if there is no file at all, which is expected when the test runner
  // doesn't provide a list.
  bool file_found = true;
};

}  // namespace coverage

#endif  // COVERAGE_INCLUSION_LIST_H_
