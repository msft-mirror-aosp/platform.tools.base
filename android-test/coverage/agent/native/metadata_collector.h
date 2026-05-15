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

#ifndef COVERAGE_METADATA_COLLECTOR_H_
#define COVERAGE_METADATA_COLLECTOR_H_

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "tools/base/android-test/coverage/proto/coverage_metadata.pb.h"

namespace coverage {

namespace proto = android::tools::coverage::proto;

/**
 * A thread-safe singleton that aggregates coverage metadata during the
 * instrumentation phase.
 *
 * This class builds the Protobuf mapping that links unique Block IDs to
 * source lines and instruction counts.
 */
class MetadataCollector {
 public:
  static MetadataCollector& Instance();

  // Initialize the collector. Metadata is typically written to the
  // application's code_cache directory.
  void Initialize(const std::string& package_name);

  // Starts a new class metadata entry and returns a pointer to it.
  // The pointer remains valid until the collector is destroyed.
  proto::ClassMetadata* AddClass(const std::string& class_name,
                                 const std::string& source_file,
                                 const std::string& smap);

  // Adds a method to a class metadata entry.
  proto::MethodMetadata* AddMethod(proto::ClassMetadata* class_meta,
                                   const std::string& method_name,
                                   const std::string& method_signature);

  // Adds a block to a method metadata entry.
  void AddBlock(proto::MethodMetadata* method_meta, uint32_t block_id,
                const std::vector<std::pair<int32_t, uint32_t>>& lines);

  // Serializes the collected metadata to a binary protobuf file.
  // Returns true on success.
  bool WriteToDisk() const;

 private:
  MetadataCollector() = default;
  ~MetadataCollector() = default;

  // Disallow copy and assignment.
  MetadataCollector(const MetadataCollector&) = delete;
  MetadataCollector& operator=(const MetadataCollector&) = delete;

  mutable std::mutex mutex_;
  std::string package_name_;
  proto::CoverageMetadata metadata_;
  bool initialized_ = false;
};

}  // namespace coverage

#endif  // COVERAGE_METADATA_COLLECTOR_H_
