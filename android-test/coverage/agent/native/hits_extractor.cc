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

#include "tools/base/android-test/coverage/agent/native/hits_extractor.h"

#include <unistd.h>
#include <cstdio>
#include <fstream>

#include "tools/base/android-test/coverage/common/log.h"
#include "tools/base/android-test/coverage/proto/coverage_metadata.pb.h"

namespace coverage {

namespace proto = android::tools::coverage::proto;

HitsExtractor& HitsExtractor::Instance() {
  static HitsExtractor* instance = new HitsExtractor();
  return *instance;
}

void HitsExtractor::Initialize(JNIEnv* jni, const std::string& data_dir) {
  if (initialized_) {
    return;
  }
  data_dir_ = data_dir;

  // Resolve and cache the handles to CoverageTracker.
  // This must be done during attachment while a valid ClassLoader is active
  // in the current thread stack.
  jclass local_class =
      jni->FindClass("com/android/tools/coverage/CoverageTracker");
  if (local_class == nullptr) {
    jni->ExceptionClear();
    Log::E("Failed to find CoverageTracker class during initialization.");
    return;
  }

  // Store a GlobalRef so the class handle remains valid across different
  // threads and during the VMDeath event where the original loader may
  // no longer be in the stack.
  tracker_class_ = static_cast<jclass>(jni->NewGlobalRef(local_class));
  jni->DeleteLocalRef(local_class);

  get_hits_method_ = jni->GetStaticMethodID(tracker_class_, "getHits", "()[Z");
  if (get_hits_method_ == nullptr) {
    jni->ExceptionClear();
    Log::E("Failed to find getHits method during initialization.");
    return;
  }
  initialized_ = true;
}

std::string HitsExtractor::PackHits(const jboolean* hits, jsize len,
                                    uint32_t* last_hit_index) {
  size_t mask_size = (len + 7) / 8;
  std::string bitmask(mask_size, 0);

  *last_hit_index = 0;
  bool any_hits = false;

  for (jsize i = 0; i < len; ++i) {
    if (hits[i]) {
      bitmask[i / 8] |= (1 << (i % 8));
      *last_hit_index = static_cast<uint32_t>(i);
      any_hits = true;
    }
  }

  if (!any_hits) {
    return "";
  }

  // Prune the bitmask to only include bytes up to the last hit to save space.
  size_t pruned_size = (*last_hit_index / 8) + 1;
  bitmask.resize(pruned_size);
  return bitmask;
}

bool HitsExtractor::ExtractAndWrite(JNIEnv* jni) const {
  if (!initialized_ || data_dir_.empty()) {
    Log::E("HitsExtractor not initialized or handles missing.");
    return false;
  }

  // 1. Invoke getHits() to retrieve the boolean array using cached handles.
  jbooleanArray hits_array = static_cast<jbooleanArray>(
      jni->CallStaticObjectMethod(tracker_class_, get_hits_method_));
  if (jni->ExceptionCheck()) {
    jni->ExceptionClear();
    Log::E("Exception occurred while calling CoverageTracker.getHits().");
    return false;
  }

  if (hits_array == nullptr) {
    Log::E("CoverageTracker.getHits() returned null.");
    return false;
  }

  jsize hits_len = jni->GetArrayLength(hits_array);
  jboolean* hits_ptr = jni->GetBooleanArrayElements(hits_array, nullptr);
  if (hits_ptr == nullptr) {
    Log::E("Failed to get elements of the hits array.");
    return false;
  }

  // 2. Pack the boolean array into a compact bitmask.
  uint32_t last_hit_index = 0;
  std::string bitmask = PackHits(hits_ptr, hits_len, &last_hit_index);

  jni->ReleaseBooleanArrayElements(hits_array, hits_ptr, JNI_ABORT);

  if (bitmask.empty()) {
    Log::I("No coverage hits recorded. Skipping hits file generation.");
    return true;
  }

  // 3. Serialize to Protobuf.
  proto::CoverageHits hits_proto;
  hits_proto.set_version(1);
  hits_proto.set_hit_mask(bitmask);

  std::string path = data_dir_ + "/code_cache/coverage_hits.pb";
  std::string tmp_path = path + ".tmp";

  std::ofstream out(tmp_path, std::ios::binary | std::ios::trunc);
  if (!out) {
    Log::E("Failed to open %s for writing hits data.", tmp_path.c_str());
    return false;
  }

  if (!hits_proto.SerializeToOstream(&out)) {
    Log::E("Failed to serialize coverage hits to %s.", tmp_path.c_str());
    out.close();
    unlink(tmp_path.c_str());
    return false;
  }
  out.close();

  if (std::rename(tmp_path.c_str(), path.c_str()) != 0) {
    Log::E("Failed to rename temporary hits file to %s.", path.c_str());
    unlink(tmp_path.c_str());
    return false;
  }

  Log::I("Coverage hits successfully written to %s (%zu bytes)", path.c_str(),
         bitmask.size());
  return true;
}

}  // namespace coverage
