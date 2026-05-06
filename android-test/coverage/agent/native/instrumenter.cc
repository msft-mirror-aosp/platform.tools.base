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

#include "tools/base/android-test/coverage/agent/native/instrumenter.h"

#include "tools/base/android-test/coverage/common/log.h"

namespace coverage {

namespace {

/**
 * JVMTI callback for the ClassFileLoadHook event.
 */
void JNICALL OnClassFileLoadHook(jvmtiEnv* jvmti, JNIEnv* jni,
                                 jclass class_being_redefined, jobject loader,
                                 const char* name, jobject protection_domain,
                                 jint class_data_len,
                                 const unsigned char* class_data,
                                 jint* new_class_data_len,
                                 unsigned char** new_class_data) {
  // TODO: Instrumentation logic to be added here.
}

}  // namespace

bool Instrumenter::RegisterHooks() {
  jvmtiEventCallbacks callbacks = {};
  callbacks.ClassFileLoadHook = &OnClassFileLoadHook;

  jvmtiError error = jvmti_->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (error != JVMTI_ERROR_NONE) {
    Log::E("Error: Unable to set JVMTI callbacks. Error code: %d", error);
    return false;
  }

  error = jvmti_->SetEventNotificationMode(
      JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
  if (error != JVMTI_ERROR_NONE) {
    Log::E("Error: Unable to enable ClassFileLoadHook. Error code: %d", error);
    return false;
  }

  return true;
}

}  // namespace coverage
