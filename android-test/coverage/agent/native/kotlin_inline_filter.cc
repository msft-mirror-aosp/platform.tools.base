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

#include "tools/base/android-test/coverage/agent/native/kotlin_inline_filter.h"

#include <cstddef>
#include <cstdint>
#include <limits>
#include <map>
#include <optional>
#include <string_view>
#include <vector>

#include "slicer/code_ir.h"
#include "slicer/dex_format.h"
#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

namespace {

// Splits `text` into lines ended by "\n", "\r\n" or "\r".
std::vector<std::string_view> SplitLines(std::string_view text) {
  std::vector<std::string_view> lines;
  size_t start = 0;
  for (size_t i = 0; i < text.size(); ++i) {
    if (text[i] != '\n' && text[i] != '\r') {
      continue;
    }
    lines.push_back(text.substr(start, i - start));
    if (text[i] == '\r' && i + 1 < text.size() && text[i + 1] == '\n') {
      ++i;
    }
    start = i + 1;
  }
  if (start < text.size()) {
    lines.push_back(text.substr(start));
  }
  return lines;
}

// Moves `*pos` past the digits at `*pos` in `line`. Returns false if there are
// none.
bool SkipDigits(std::string_view line, size_t* pos) {
  size_t start = *pos;
  while (*pos < line.size() && line[*pos] >= '0' && line[*pos] <= '9') {
    ++*pos;
  }
  return *pos > start;
}

// Reads the decimal number at `*pos` in `line` into `value`, and moves `*pos`
// past it. Returns false if there are no digits, or if the number doesn't fit
// in an int32_t.
bool ReadNumber(std::string_view line, size_t* pos, int32_t* value) {
  size_t start = *pos;
  if (!SkipDigits(line, pos)) {
    return false;
  }
  int64_t number = 0;
  for (size_t i = start; i < *pos; ++i) {
    number = number * 10 + (line[i] - '0');
    if (number > std::numeric_limits<int32_t>::max()) {
      return false;
    }
  }
  *value = static_cast<int32_t>(number);
  return true;
}

// Parses a line of the file section, "+ <id> <file name>", and returns its id
// in `id`. The next line holds the class path of the file.
bool ParseFileInfo(std::string_view line, int32_t* id) {
  if (line.size() < 2 || line[0] != '+' || line[1] != ' ') {
    return false;
  }
  size_t pos = 2;
  if (!ReadNumber(line, &pos, id)) {
    return false;
  }
  // A space, then a file name of at least one character.
  return pos + 1 < line.size() && line[pos] == ' ';
}

// What the filter needs from a line of the line section,
// "<input start>#<file id>[,<repeat count>]:<output start>[,<increment>]".
struct LineInfo {
  int32_t input_start = 0;
  int32_t file_id = 0;
  int32_t output_start = 0;
};

// Parses a line of the line section into `info`. The file id is optional in
// the SMAP format, but the Kotlin compiler always writes it.
bool ParseLineInfo(std::string_view line, LineInfo* info) {
  size_t pos = 0;
  int32_t repeat_count = 0;
  if (!ReadNumber(line, &pos, &info->input_start) || pos >= line.size() ||
      line[pos] != '#') {
    return false;
  }
  ++pos;
  if (!ReadNumber(line, &pos, &info->file_id)) {
    return false;
  }
  if (pos < line.size() && line[pos] == ',') {
    ++pos;
    if (!ReadNumber(line, &pos, &repeat_count)) {
      return false;
    }
  }
  if (pos >= line.size() || line[pos] != ':') {
    return false;
  }
  ++pos;
  if (!ReadNumber(line, &pos, &info->output_start)) {
    return false;
  }
  if (pos < line.size() && line[pos] == ',') {
    ++pos;
    if (!SkipDigits(line, &pos)) {
      return false;
    }
  }
  return pos == line.size();
}

}  // namespace

std::optional<int32_t> KotlinInlineFilter::FirstInlinedLine(
    std::string_view class_name, std::string_view source_file,
    std::string_view smap) {
  if (smap.empty()) {
    return std::nullopt;
  }
  const std::vector<std::string_view> lines = SplitLines(smap);
  size_t next = 0;
  auto read_line = [&lines, &next](std::string_view* line) {
    if (next >= lines.size()) {
      return false;
    }
    *line = lines[next++];
    return true;
  };

  // The header, then the file section of the Kotlin stratum.
  std::string_view line;
  for (std::string_view expected :
       {std::string_view("SMAP"), source_file, std::string_view("Kotlin"),
        std::string_view("*S Kotlin"), std::string_view("*F")}) {
    if (!read_line(&line) || line != expected) {
      return std::nullopt;
    }
  }
  std::map<int32_t, std::string_view> class_paths;
  while (true) {
    if (!read_line(&line)) {
      return std::nullopt;
    }
    if (line == "*L") {
      break;
    }
    int32_t id = 0;
    std::string_view class_path;
    if (!ParseFileInfo(line, &id) || !read_line(&class_path)) {
      return std::nullopt;
    }
    class_paths[id] = class_path;
  }

  // The line section, up to the end of the SMAP or to the KotlinDebug stratum,
  // which maps the inlined code to its call sites. The class's own lines are
  // mapped to themselves; every other mapping is inlined code.
  std::optional<int32_t> first_inlined_line;
  while (true) {
    if (!read_line(&line)) {
      return std::nullopt;
    }
    if (line == "*E" || line == "*S KotlinDebug") {
      break;
    }
    LineInfo info;
    if (!ParseLineInfo(line, &info)) {
      return std::nullopt;
    }
    auto class_path = class_paths.find(info.file_id);
    if (class_path != class_paths.end() && class_path->second == class_name &&
        info.input_start == info.output_start) {
      continue;
    }
    if (!first_inlined_line || info.output_start < *first_inlined_line) {
      first_inlined_line = info.output_start;
    }
  }
  return first_inlined_line;
}

void KotlinInlineFilter::Filter(const FilterContext& context,
                                ir::EncodedMethod* ir_method,
                                lir::CodeIr& code_ir, FilterOutput& output) {
  // The class name without the "L" and ";" of its descriptor.
  std::string_view class_name = context.class_name;
  if (class_name.size() >= 2 && class_name.front() == 'L' &&
      class_name.back() == ';') {
    class_name = class_name.substr(1, class_name.size() - 2);
  }
  const std::optional<int32_t> first_inlined_line =
      FirstInlinedLine(class_name, context.source_file, context.smap);
  if (!first_inlined_line) {
    return;
  }

  // Follows the line numbers in code order. Slicer places the annotation of
  // each line number right before the first instruction of the line. In a
  // method with debug information, it also places an annotation of the start
  // line of the debug information before them, which is not a line number of
  // the method. The code before the first line number is on line 0.
  int32_t line = 0;
  bool skipped_start_line = false;
  lir::Instruction* first_ignored = nullptr;
  lir::Instruction* last_ignored = nullptr;
  for (auto* instr : code_ir.instructions) {
    if (auto* annotation = dynamic_cast<lir::DbgInfoAnnotation*>(instr)) {
      if (annotation->dbg_opcode != dex::DBG_ADVANCE_LINE) {
        continue;
      }
      if (!skipped_start_line) {
        skipped_start_line = true;
        continue;
      }
      if (!annotation->operands.empty()) {
        if (auto* number =
                dynamic_cast<lir::LineNumber*>(annotation->operands[0])) {
          line = static_cast<int32_t>(number->line);
        }
      }
      continue;
    }
    if (dynamic_cast<lir::Bytecode*>(instr) == nullptr) {
      continue;
    }
    // Ignores each run of inlined instructions as one range.
    if (line >= *first_inlined_line) {
      if (first_ignored == nullptr) {
        first_ignored = instr;
      }
      last_ignored = instr;
    } else if (first_ignored != nullptr) {
      output.Ignore(first_ignored, last_ignored);
      first_ignored = nullptr;
    }
  }
  if (first_ignored != nullptr) {
    output.Ignore(first_ignored, last_ignored);
  }
}

REGISTER_KOTLIN_FILTER(KotlinInlineFilter)

}  // namespace coverage
