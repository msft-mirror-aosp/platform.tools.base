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

namespace sherlock {

size_t GetValidSequenceLength(const char* p) {
  if (!p || !*p) return 0;
  unsigned char c = *p;

  if (c < 0x80) {
    return 1;  // Valid ASCII
  }

  if ((c & kTwoBytePrefixMask) == kTwoBytePrefix) {
    // Reject overlong encodings (values <= 127).
    // Lead bytes 0xC0 and 0xC1 are always overlong.
    if (c >= 0xC2 && (p[1] & kContinuationMask) == kContinuationPrefix) {
      return 2;
    }
    return 0;
  }

  if ((c & kThreeBytePrefixMask) == kThreeBytePrefix) {
    if ((p[1] & kContinuationMask) == kContinuationPrefix &&
        (p[2] & kContinuationMask) == kContinuationPrefix &&
        // Reject overlong encodings (values < 2048).
        !(c == 0xE0 && (unsigned char)p[1] < 0xA0) &&
        // Reject surrogates (U+D800 to U+DFFF reserved for UTF-16).
        !(c == 0xED && (unsigned char)p[1] >= 0xA0)) {
      return 3;
    }
    return 0;
  }

  if ((c & kFourBytePrefixMask) == kFourBytePrefix) {
    if (c <= 0xF4 &&
        // Reject overlong encodings (values < 65536).
        !(c == 0xF0 && (unsigned char)p[1] < 0x90) &&
        // Restrict values to the Unicode limit of U+10FFFF.
        // If lead is 0xF4, the second byte must be <= 0x8F.
        (c < 0xF4 || (c == 0xF4 && (unsigned char)p[1] <= 0x8F)) &&
        (p[1] & kContinuationMask) == kContinuationPrefix &&
        (p[2] & kContinuationMask) == kContinuationPrefix &&
        (p[3] & kContinuationMask) == kContinuationPrefix) {
      return 4;
    }
    return 0;
  }

  return 0;  // Invalid lead byte
}

std::string SanitizeUtf8(const char* s) {
  if (!s) return "";

  // Fast path: scan the string to check if it is already valid UTF-8.
  const char* p = s;
  bool needs_sanitization = false;
  while (*p) {
    size_t len = GetValidSequenceLength(p);
    if (len > 0) {
      p += len;
    } else {
      needs_sanitization = true;
      break;
    }
  }

  if (!needs_sanitization) {
    // Use the calculated length to avoid a redundant strlen call.
    return std::string(s, p - s);
  }

  // Slow path: allocate and sanitize
  std::string result(s, p - s);
  while (*p) {
    size_t len = GetValidSequenceLength(p);
    if (len > 0) {
      result.append(p, len);
      p += len;
    } else {
      result += '?';
      p++;
    }
  }
  return result;
}

}  // namespace sherlock
