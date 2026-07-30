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

#ifndef SHERLOCK_UTILS_MAPPED_FILE_H_
#define SHERLOCK_UTILS_MAPPED_FILE_H_

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include <cstddef>
#include <cstdint>
#include <string>

namespace sherlock {

struct MappedFile {
  const uint8_t* data = nullptr;
  size_t size = 0;
#ifdef _WIN32
  HANDLE file = INVALID_HANDLE_VALUE;
  HANDLE mapping = NULL;
#else
  int fd = -1;
#endif
};

bool MapInputFile(const std::string& path, MappedFile* mapped);
void UnmapInputFile(MappedFile* mapped);

}  // namespace sherlock

#endif  // SHERLOCK_UTILS_MAPPED_FILE_H_
