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

#include "tools/base/android-test/coverage/agent/native/metadata_collector.h"

#include <unistd.h>
#include <cstdio>
#include <fstream>
#include <string>

#include "tools/base/android-test/coverage/common/log.h"

namespace coverage {

MetadataCollector& MetadataCollector::Instance() {
  // Use a heap-allocated static local to avoid destructor-related issues
  // during process exit, as Protobuf messages have non-trivial destructors.
  static MetadataCollector* instance = new MetadataCollector();
  return *instance;
}

void MetadataCollector::Initialize(const std::string& data_dir) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (initialized_) {
    return;
  }
  data_dir_ = data_dir;
  metadata_.set_version(1);
  initialized_ = true;
}

proto::ClassMetadata* MetadataCollector::AddClass(
    const std::string& class_name, const std::string& source_file,
    const std::string& smap) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto* class_meta = metadata_.add_classes();
  class_meta->set_class_name(class_name);
  class_meta->set_source_file(source_file);
  class_meta->set_smap(smap);
  return class_meta;
}

proto::MethodMetadata* MetadataCollector::AddMethod(
    proto::ClassMetadata* class_meta, const std::string& method_name,
    const std::string& method_signature) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto* method_meta = class_meta->add_methods();
  method_meta->set_name(method_name);
  method_meta->set_signature(method_signature);
  return method_meta;
}

void MetadataCollector::AddBlock(
    proto::MethodMetadata* method_meta, uint32_t block_id,
    const std::vector<std::pair<int32_t, uint32_t>>& lines,
    uint32_t branch_count) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto* block_meta = method_meta->add_blocks();
  block_meta->set_block_id(block_id);
  block_meta->set_branch_count(branch_count);

  for (const auto& line_info : lines) {
    auto* line_meta = block_meta->add_lines();
    line_meta->set_line_number(line_info.first);
    line_meta->set_instruction_count(line_info.second);
  }
}

bool MetadataCollector::WriteToDisk() const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_ || data_dir_.empty()) {
    Log::E("MetadataCollector not initialized.");
    return false;
  }

  std::string path = data_dir_ + "/coverage_metadata.pb";

  // Use a temporary file for atomic write.
  std::string tmp_path = path + ".tmp";

  std::ofstream out(tmp_path, std::ios::binary | std::ios::trunc);
  if (!out) {
    Log::E("Failed to open %s for writing metadata.", tmp_path.c_str());
    return false;
  }

  if (!metadata_.SerializeToOstream(&out)) {
    Log::E("Failed to serialize coverage metadata to %s.", tmp_path.c_str());
    out.close();
    unlink(tmp_path.c_str());
    return false;
  }
  out.close();

  if (std::rename(tmp_path.c_str(), path.c_str()) != 0) {
    Log::E("Failed to rename temporary metadata file to %s.", path.c_str());
    unlink(tmp_path.c_str());
    return false;
  }

  Log::I("Coverage metadata successfully written to %s", path.c_str());
  return true;
}

const proto::CoverageMetadata& MetadataCollector::metadata() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return metadata_;
}

}  // namespace coverage
