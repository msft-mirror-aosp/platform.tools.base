/*
 * Copyright (C) 2023 The Android Open Source Project
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

#pragma once

#include <cstdint>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>

namespace processtracker {

using namespace std;

/**
 * Reads the contents of a file into a string with a maximum length.
 * SECURITY: Limiting the read size prevents resource exhaustion (DoS) if
 * a file is unexpectedly large or a malicious symlink points to a giant file.
 */
string readFile(const string& path, size_t maxLen = 4096) {
  ifstream stream(path);
  if (!stream) return "";

  string result;
  result.resize(maxLen);
  stream.read(&result[0], maxLen);
  result.resize(stream.gcount());
  return result;
}

/**
 * Returns true if str starts with prefix.
 *
 * Note that this uses rfind(str, 0) to avoid scanning the entire string.
 */
bool startsWith(const string& str, const string& prefix) {
  return str.rfind(prefix, 0) == 0;
}

/**
 * Parses a string into an integer.
 * SECURITY: Check for both upper and lower bounds to prevent integer
 * overflow/underflow vulnerabilities.
 */
int parseInt(const char* str, int defaultValue) {
  if (str == nullptr || *str == '\0') return defaultValue;
  char* ptr;
  long l = strtol(str, &ptr, 10);
  if (*ptr != '\0' || l > INT32_MAX || l < INT32_MIN) {
    return defaultValue;
  }
  return static_cast<int>(l);
}

}  // namespace processtracker
