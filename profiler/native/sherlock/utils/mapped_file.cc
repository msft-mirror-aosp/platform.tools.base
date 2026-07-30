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

#include "mapped_file.h"

#include "absl/log/log.h"

namespace sherlock {

bool MapInputFile(const std::string& path, MappedFile* mapped) {
#ifdef _WIN32
  mapped->file = CreateFileA(path.c_str(), GENERIC_READ, FILE_SHARE_READ, NULL,
                             OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
  if (mapped->file == INVALID_HANDLE_VALUE) {
    LOG(ERROR) << "Failed to open input file: " << path;
    return false;
  }

  LARGE_INTEGER file_size;
  if (!GetFileSizeEx(mapped->file, &file_size)) {
    LOG(ERROR) << "Failed to get file size for: " << path;
    CloseHandle(mapped->file);
    mapped->file = INVALID_HANDLE_VALUE;
    return false;
  }
  mapped->size = static_cast<size_t>(file_size.QuadPart);
  if (mapped->size == 0) {
    mapped->data = nullptr;
    return true;
  }

  mapped->mapping =
      CreateFileMappingA(mapped->file, NULL, PAGE_READONLY, 0, 0, NULL);
  if (mapped->mapping == NULL) {
    LOG(ERROR) << "Failed to create file mapping for: " << path;
    CloseHandle(mapped->file);
    mapped->file = INVALID_HANDLE_VALUE;
    return false;
  }

  mapped->data = static_cast<const uint8_t*>(
      MapViewOfFile(mapped->mapping, FILE_MAP_READ, 0, 0, 0));
  if (mapped->data == nullptr) {
    LOG(ERROR) << "Failed to memory map input file: " << path;
    CloseHandle(mapped->mapping);
    CloseHandle(mapped->file);
    mapped->mapping = NULL;
    mapped->file = INVALID_HANDLE_VALUE;
    return false;
  }
  return true;
#else
  mapped->fd = open(path.c_str(), O_RDONLY);
  if (mapped->fd < 0) {
    LOG(ERROR) << "Failed to open input file: " << path;
    return false;
  }
  struct stat st;
  if (fstat(mapped->fd, &st) != 0) {
    LOG(ERROR) << "Failed to stat input file: " << path;
    close(mapped->fd);
    mapped->fd = -1;
    return false;
  }
  mapped->size = static_cast<size_t>(st.st_size);
  if (mapped->size == 0) {
    mapped->data = nullptr;
    return true;
  }
  void* ptr =
      mmap(nullptr, mapped->size, PROT_READ, MAP_PRIVATE, mapped->fd, 0);
  if (ptr == MAP_FAILED) {
    LOG(ERROR) << "Failed to memory map input file: " << path;
    close(mapped->fd);
    mapped->fd = -1;
    return false;
  }
  mapped->data = static_cast<const uint8_t*>(ptr);
  return true;
#endif
}

void UnmapInputFile(MappedFile* mapped) {
  if (!mapped) return;
#ifdef _WIN32
  if (mapped->data != nullptr) {
    UnmapViewOfFile(mapped->data);
    mapped->data = nullptr;
  }
  if (mapped->mapping != NULL) {
    CloseHandle(mapped->mapping);
    mapped->mapping = NULL;
  }
  if (mapped->file != INVALID_HANDLE_VALUE) {
    CloseHandle(mapped->file);
    mapped->file = INVALID_HANDLE_VALUE;
  }
#else
  if (mapped->data != nullptr && mapped->data != MAP_FAILED) {
    munmap(const_cast<uint8_t*>(mapped->data), mapped->size);
    mapped->data = nullptr;
  }
  if (mapped->fd >= 0) {
    close(mapped->fd);
    mapped->fd = -1;
  }
#endif
  mapped->size = 0;
}

}  // namespace sherlock
