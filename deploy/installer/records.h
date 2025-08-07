/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include <cstddef>
#include <cstdint>

namespace deploy {

struct __attribute__((packed)) LFHRecord {
  static constexpr uint32_t SIGNATURE = 0x04034b50;

  uint32_t signature;
  uint16_t versionNeeded;
  uint16_t generalPurposeBitFlag;
  uint16_t compressionMethod;
  uint16_t lastModFileTime;
  uint16_t lastModFileData;
  uint32_t crc32;
  uint32_t compressedSize;
  uint32_t uncompressedSize;
  uint16_t fileNameLength;
  uint16_t extraFieldLength;

  bool IsCompressed() { return compressionMethod != 0; }
};

struct __attribute__((packed)) CDFHRecord {
  static constexpr uint32_t SIGNATURE = 0x02014b50;

  uint32_t signature;
  uint16_t version;
  uint16_t versionNeeded;
  uint16_t generalPurposeBitFlag;
  uint16_t compressionMethod;
  uint16_t lastModFileTime;
  uint16_t lastModFileDate;
  uint32_t crc32;
  uint32_t compressedSize;
  uint32_t uncompressedSize;
  uint16_t fileNameLength;
  uint16_t extraFieldLength;
  uint16_t fileCommentLength;
  uint16_t diskNumberStart;
  uint16_t internalFileAttributes;
  uint32_t externalFileAttributes;
  uint32_t relativeOffsetOfLocalHeader;

  size_t Size() {
    return sizeof(CDFHRecord) + fileNameLength + extraFieldLength +
           fileCommentLength;
  }
};

}  // namespace deploy
