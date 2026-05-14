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

#ifndef TOOLS_BASE_PROFILER_NATIVE_SHERLOCK_UTF8_UTILS_H_
#define TOOLS_BASE_PROFILER_NATIVE_SHERLOCK_UTF8_UTILS_H_

#include <string>

namespace sherlock {

// For 2-byte sequences (110xxxxx 10xxxxxx)
constexpr unsigned char kTwoBytePrefixMask = 0xE0;  // 11100000
constexpr unsigned char kTwoBytePrefix = 0xC0;      // 11000000

// For 3-byte sequences (1110xxxx 10xxxxxx 10xxxxxx)
constexpr unsigned char kThreeBytePrefixMask = 0xF0;  // 11110000
constexpr unsigned char kThreeBytePrefix = 0xE0;      // 11100000

// For 4-byte sequences (11110xxx 10xxxxxx 10xxxxxx 10xxxxxx)
constexpr unsigned char kFourBytePrefixMask = 0xF8;  // 11111000
constexpr unsigned char kFourBytePrefix = 0xF0;      // 11110000

// For trailing continuation bytes (10xxxxxx)
constexpr unsigned char kContinuationMask = 0xC0;    // 11000000
constexpr unsigned char kContinuationPrefix = 0x80;  // 10000000

/**
 * Sanitizes a string to ensure it is valid UTF-8 by replacing invalid byte
 * sequences with '?'. Valid multi-byte characters (e.g., localized text,
 * emojis) are preserved.
 */
std::string SanitizeUtf8(const char* s);

/**
 * Returns the length of the valid UTF-8 sequence starting at 'p'.
 * Returns 0 if the sequence is invalid.
 * Relies on short-circuit evaluation to prevent reading past null terminator.
 */
size_t GetValidSequenceLength(const char* p);

}  // namespace sherlock

#endif  // TOOLS_BASE_PROFILER_NATIVE_SHERLOCK_UTF8_UTILS_H_
