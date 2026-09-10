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

#include "tools/base/android-test/coverage/agent/native/tagging_manager.h"
#include "tools/base/android-test/coverage/common/log.h"

namespace coverage {

TaggingManager& TaggingManager::Instance() {
  static TaggingManager instance;
  return instance;
}

void TaggingManager::TrackClass(const std::string& descriptor) {
  std::lock_guard<std::mutex> lock(mutex_);
  awaiting_tag_classes_.insert(descriptor);
}

void TaggingManager::TagClassIfTracked(jvmtiEnv* jvmti, JNIEnv* jni,
                                       jclass klass) {
  if (klass == nullptr) {
    return;
  }

  if (jvmti == nullptr) {
    // Standard safety fallback for host-side unit tests
    return;
  }

  char* signature = nullptr;
  jvmtiError error = jvmti->GetClassSignature(klass, &signature, nullptr);
  if (error != JVMTI_ERROR_NONE || signature == nullptr) {
    return;
  }

  std::string descriptor(signature);
  jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));

  bool is_tracked = false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (awaiting_tag_classes_.erase(descriptor) > 0) {
      is_tracked = true;
    }
  }

  if (is_tracked) {
    jvmtiError err = jvmti->SetTag(klass, kInstrumentedTag);
    if (err == JVMTI_ERROR_NONE) {
      Log::I("Successfully tagged initial-load class in ClassPrepare: %s",
             descriptor.c_str());
    } else {
      Log::W("Failed to set JVMTI tag in ClassPrepare for %s (error: %d)",
             descriptor.c_str(), err);
    }
  }
}

bool TaggingManager::IsClassTrackedForTesting(
    const std::string& descriptor) const {
  std::lock_guard<std::mutex> lock(mutex_);
  return awaiting_tag_classes_.count(descriptor) > 0;
}

}  // namespace coverage