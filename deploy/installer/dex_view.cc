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

#include "tools/base/deploy/installer/dex_view.h"

namespace {

// Performs a fast check if a signature is declared in a DEX file.
// Works in 2 steps:
//   1. Binary search over a type ids table to find a type
//      matching a given signature. Type ids and string ids are sorted,
//      hence binary search is feasible (see
//      https://source.android.com/docs/core/runtime/dex-format#file-layout).
//   2. Linear scan over class defs table to find if a found type id
//      is present there.
// This approach allows exiting fast if a given signature is not referenced
// by a DEX file.
bool ContainsClassDeclaration(const dex::Reader& reader,
                              const std::string_view& signature) {
  auto type_ids = reader.TypeIds();
  auto class_defs = reader.ClassDefs();

  // [l, r)
  size_t l = 0;
  size_t r = type_ids.size();
  while (l < r) {
    size_t m = (l + r) / 2;
    dex::TypeId type_id = type_ids[m];
    std::string_view sv = reader.GetStringMUTF8(type_id.descriptor_idx);
    if (signature == sv) {
      // Check if type id is present in class definitions
      for (const dex::ClassDef& class_def : class_defs) {
        if (class_def.class_idx == m) {
          return true;
        }
      }
      return false;
    } else if (signature < sv) {
      r = m;
    } else {
      l = m + 1;
    }
  }
  return false;
}

static bool IsDexFileName(const std::string_view& str) {
  constexpr std::string_view DEX_SUFFIX = ".dex";
  return str.size() > DEX_SUFFIX.size() &&
         std::equal(DEX_SUFFIX.rbegin(), DEX_SUFFIX.rend(), str.rbegin());
}

}  // namespace

namespace deploy {

dex::Reader DexContainerReader::Iterator::operator*() const {
  // If header_ is nullptr, dex::Reader will abort() internally
  return dex::Reader(reinterpret_cast<dex::u1*>(header_),
                     header_->ContainerSize());
}

bool DexContainerReader::Iterator::operator!=(const Iterator& other) const {
  return header_ != other.header_;
}

DexContainerReader::Iterator& DexContainerReader::Iterator::operator++() {
  if (!header_) {
    return *this;
  }

  if (header_->ContainerOff() + header_->file_size >=
      header_->ContainerSize()) {
    header_ = nullptr;
  } else {
    header_ = reinterpret_cast<dex::Header*>(
        reinterpret_cast<uint8_t*>(header_) + header_->file_size);
  }
  return *this;
}

bool DexView::ContainsClassDeclaration(
    const std::string_view& signature) const {
  for (const auto& reader : DexContainerReader(data_)) {
    if (::ContainsClassDeclaration(reader, signature)) {
      return true;
    }
  }
  return false;
}

std::vector<ApkArchive::Entry> GetUncompressedDexEntries(
    const ApkArchive& archive) {
  std::vector<ApkArchive::Entry> entries = archive.GetEntries();
  std::vector<ApkArchive::Entry> result;
  for (const auto& entry : entries) {
    if (!entry.lfh->IsCompressed() && IsDexFileName(entry.name)) {
      result.push_back(entry);
    }
  }
  return result;
}

}  // namespace deploy
