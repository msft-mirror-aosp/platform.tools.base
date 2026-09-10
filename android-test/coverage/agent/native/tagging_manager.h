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

#ifndef COVERAGE_TAGGING_MANAGER_H_
#define COVERAGE_TAGGING_MANAGER_H_

#include <jni.h>
#include <jvmti.h>

#include <mutex>
#include <string>
#include <unordered_set>

namespace coverage {

class TaggingManager {
 public:
  static TaggingManager& Instance();

  // Registers a class descriptor that has been successfully instrumented
  void TrackClass(const std::string& descriptor);

  // Checks if the prepared class is tracked, applies the JVMTI tag,
  // and removes it from tracking.
  void TagClassIfTracked(jvmtiEnv* jvmti, JNIEnv* jni, jclass klass);

  // Test helper to verify the state machine
  bool IsClassTrackedForTesting(const std::string& descriptor) const;

  // Tag value used to mark classes as already instrumented
  static constexpr jlong kInstrumentedTag = 0xCAFE1234;

 private:
  TaggingManager() = default;
  ~TaggingManager() = default;
  TaggingManager(const TaggingManager&) = delete;
  TaggingManager& operator=(const TaggingManager&) = delete;

  std::unordered_set<std::string> awaiting_tag_classes_;
  mutable std::mutex mutex_;
};

}  // namespace coverage

#endif  // COVERAGE_TAGGING_MANAGER_H_