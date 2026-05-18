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

#ifndef COVERAGE_HITS_EXTRACTOR_H_
#define COVERAGE_HITS_EXTRACTOR_H_

#include <jni.h>

#include <string>

namespace coverage {

/**
 * A singleton responsible for extracting coverage hit data from the Java
 * runtime (CoverageTracker) and serializing it to disk during teardown.
 */
class HitsExtractor {
 public:
  static HitsExtractor& Instance();

  // Initialize the extractor. This must be called during agent attachment
  // while a valid Java stack is present to resolve and cache class handles.
  void Initialize(JNIEnv* jni, const std::string& package_name);

  // Extracts the hits array from CoverageTracker and writes it to disk.
  // This uses cached handles for safety during VM shutdown.
  bool ExtractAndWrite(JNIEnv* jni) const;

 private:
  HitsExtractor() = default;
  ~HitsExtractor() = default;

  // Disallow copy and assignment.
  HitsExtractor(const HitsExtractor&) = delete;
  HitsExtractor& operator=(const HitsExtractor&) = delete;

  std::string package_name_;
  jclass tracker_class_ = nullptr;
  jmethodID get_hits_method_ = nullptr;
  bool initialized_ = false;
};

}  // namespace coverage

#endif  // COVERAGE_HITS_EXTRACTOR_H_
