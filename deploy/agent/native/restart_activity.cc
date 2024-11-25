/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "tools/base/deploy/agent/native/restart_activity.h"

#include "tools/base/deploy/agent/native/jni/jni_class.h"

proto::AgentRestartActivityResponse deploy::RestartActivity(JNIEnv* jni) {
  JniClass instrument(
      jni, "com/android/tools/deploy/instrument/InstrumentationHooks");
  instrument.CallStaticVoidMethod("addResourceOverlays", "()V");
  instrument.CallStaticVoidMethod("restartActivity", "()V");
  proto::AgentRestartActivityResponse response;
  response.set_status(proto::AgentRestartActivityResponse::OK);
  return response;
}
