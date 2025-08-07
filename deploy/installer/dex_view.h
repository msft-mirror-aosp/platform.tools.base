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

#include <string>

#include "slicer/reader.h"
#include "tools/base/deploy/installer/apk_archive.h"

namespace deploy {

class DexView {
 public:
  DexView(uint8_t* data) : data_(data) {}

  bool ContainsClassDeclaration(const std::string_view& signature) const;

 private:
  uint8_t* data_;
};

class DexContainerReader {
 public:
  struct Iterator {
    Iterator(uint8_t* data) : header_(reinterpret_cast<dex::Header*>(data)) {}

    dex::Reader operator*() const;
    bool operator!=(const Iterator& other) const;
    Iterator& operator++();

   private:
    dex::Header* header_;
  };

  DexContainerReader(uint8_t* data) : data_(data) {}

  Iterator begin() const { return Iterator(data_); }
  Iterator end() const { return Iterator(nullptr); }

 private:
  uint8_t* data_;
};

std::vector<ApkArchive::Entry> GetUncompressedDexEntries(
    const ApkArchive& archive);

}  // namespace deploy
