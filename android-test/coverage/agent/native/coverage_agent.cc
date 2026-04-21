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

#include "tools/base/android-test/coverage/common/log.h"

// Global JVMTI environment handle
jvmtiEnv* jvmti_env = nullptr;

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options,
                                                 void* reserved) {
  coverage::Log::I("Coverage Agent attached with options: %s",
                   (options ? options : "none"));

  // 1. Get the JVMTI environment
  jint res =
      vm->GetEnv(reinterpret_cast<void**>(&jvmti_env), JVMTI_VERSION_1_2);
  if (res != JNI_OK || jvmti_env == nullptr) {
    coverage::Log::E("Error: Unable to get JVMTI environment. Return code: %d",
                     res);
    return JNI_OK;  // Return OK to prevent ART from re-attaching on failure.
  }

  // 2. Request necessary capabilities (e.g., for class transformation)
  jvmtiCapabilities caps = {};
  caps.can_retransform_classes = 1;
  caps.can_retransform_any_class = 1;

  jvmtiError error = jvmti_env->AddCapabilities(&caps);
  if (error != JVMTI_ERROR_NONE) {
    coverage::Log::E("Error: Unable to add JVMTI capabilities. Error code: %d",
                     error);
    return JNI_OK;  // Return OK to prevent ART from re-attaching on failure.
  }

  coverage::Log::I(
      "Coverage Agent JVMTI environment initialized successfully.");
  return JNI_OK;
}
