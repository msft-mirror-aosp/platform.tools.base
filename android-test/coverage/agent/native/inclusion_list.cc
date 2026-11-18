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

#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace coverage {

namespace {

constexpr std::string_view kHeaderPrefix = "#coverage-classes ";
constexpr std::string_view kVersion = "v1";
constexpr std::string_view kCountPrefix = " count=";
constexpr std::string_view kEndMarker = "#end";

InclusionListOrError Error(std::string error) {
  InclusionListOrError result;
  result.error = std::move(error);
  return result;
}

// Splits `contents` into lines, without their "\n" or "\r\n" endings.
std::vector<std::string_view> SplitLines(std::string_view contents) {
  std::vector<std::string_view> lines;
  while (!contents.empty()) {
    size_t end = contents.find('\n');
    std::string_view line = contents.substr(0, end);
    if (!line.empty() && line.back() == '\r') {
      line.remove_suffix(1);
    }
    lines.push_back(line);
    if (end == std::string_view::npos) {
      break;
    }
    contents.remove_prefix(end + 1);
  }
  return lines;
}

// Parses `text`, the number of classes in the header, into `count`. Returns
// false if it isn't a decimal number.
bool ParseCount(std::string_view text, size_t* count) {
  if (text.empty() || text.size() > 9) {
    return false;
  }
  size_t value = 0;
  for (char digit : text) {
    if (digit < '0' || digit > '9') {
      return false;
    }
    value = value * 10 + static_cast<size_t>(digit - '0');
  }
  *count = value;
  return true;
}

}  // namespace

InclusionList::InclusionList(std::vector<std::string> class_names)
    : class_names_(std::move(class_names)) {
  std::sort(class_names_.begin(), class_names_.end());
  class_names_.erase(std::unique(class_names_.begin(), class_names_.end()),
                     class_names_.end());
}

InclusionListOrError InclusionList::Parse(std::string_view contents) {
  std::vector<std::string_view> lines = SplitLines(contents);
  if (lines.empty()) {
    return Error("the class list is empty");
  }

  // The header: "#coverage-classes v1 count=<N>".
  std::string_view header = lines.front();
  if (header.substr(0, kHeaderPrefix.size()) != kHeaderPrefix) {
    return Error("the class list has no header");
  }
  header.remove_prefix(kHeaderPrefix.size());
  std::string_view version = header.substr(0, header.find(' '));
  if (version != kVersion) {
    return Error("unsupported class list version: " + std::string(version));
  }
  header.remove_prefix(version.size());
  size_t expected_count = 0;
  if (header.substr(0, kCountPrefix.size()) != kCountPrefix ||
      !ParseCount(header.substr(kCountPrefix.size()), &expected_count)) {
    return Error("the class list header has no valid count");
  }

  // The classes, up to the end marker.
  std::vector<std::string> class_names;
  size_t index = 1;
  for (; index < lines.size() && lines[index] != kEndMarker; ++index) {
    std::string_view class_name = lines[index];
    if (class_name.empty() || class_name.front() == '#') {
      return Error("the class list has an invalid line: " +
                   std::string(class_name));
    }
    class_names.emplace_back(class_name);
  }
  if (index == lines.size()) {
    return Error("the class list has no end marker, so it may be truncated");
  }
  if (class_names.size() != expected_count) {
    return Error("the class list has " + std::to_string(class_names.size()) +
                 " classes, but its header says " +
                 std::to_string(expected_count));
  }
  // Only empty lines may follow the end marker.
  for (++index; index < lines.size(); ++index) {
    if (!lines[index].empty()) {
      return Error("the class list has content after its end marker");
    }
  }
  if (class_names.empty()) {
    return Error("the class list has no classes");
  }

  InclusionListOrError result;
  result.list = InclusionList(std::move(class_names));
  return result;
}

InclusionListOrError InclusionList::Load(const std::string& path) {
  int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd == -1) {
    int open_errno = errno;
    InclusionListOrError result =
        Error("can't open " + path + ": " + std::strerror(open_errno));
    result.file_found = open_errno != ENOENT;
    return result;
  }
  // Unlike a stream, read() tells a read error from the end of the file.
  std::string contents;
  char buffer[4096];
  ssize_t count;
  while ((count = read(fd, buffer, sizeof(buffer))) != 0) {
    if (count > 0) {
      contents.append(buffer, static_cast<size_t>(count));
    } else if (errno != EINTR) {
      int read_errno = errno;
      close(fd);
      return Error("can't read " + path + ": " + std::strerror(read_errno));
    }
  }
  close(fd);
  return Parse(contents);
}

bool InclusionList::Contains(std::string_view class_name) const {
  return std::binary_search(
      class_names_.begin(), class_names_.end(), class_name,
      [](std::string_view a, std::string_view b) { return a < b; });
}

}  // namespace coverage
