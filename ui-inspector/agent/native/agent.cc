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

#include <jni.h>
#include <jvmti.h>
#include "tools/base/transport/native/jvmti/jvmti_helper.h"
#include "tools/base/transport/native/utils/log.h"

namespace {
constexpr const char* kLogTag = "studio.ui-inspector";
}  // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options,
                                                 void* reserved) {
  // Ensure thread is attached to JVM (crucial on Android)
  profiler::GetThreadLocalJNI(vm);

  jvmtiEnv* jvmti = profiler::CreateJvmtiEnv(vm);
  if (jvmti == nullptr) {
    profiler::Log::E(kLogTag, "Failed to initialize JVMTI env.");
    return JNI_OK;  // Return OK to avoid ART retrying attachment
  }

  profiler::Log::I(kLogTag, "Hello World from UI Inspector Agent!");

  return JNI_OK;
}
