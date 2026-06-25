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

/*
 * JNI export bindings for the Java ArtToolingBridge class.
 * This file routes Java calls through to the native JvmtiArtTooling
 * implementation.
 */

#include <jni.h>
#include "jvmti_art_tooling.h"
#include "tools/base/transport/native/agent/jni_wrappers.h"
#include "tools/base/transport/native/utils/log.h"

namespace {
constexpr const char* kLogTag = "studio.ui-inspector";
}

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_com_android_tools_ui_inspector_service_ArtToolingBridge_nativeFindInstances(
    JNIEnv* env, jclass callerClass, jlong artToolingPtr, jclass clazz) {
  auto* art_tooling =
      reinterpret_cast<ui_inspector::JvmtiArtTooling*>(artToolingPtr);
  return art_tooling->FindInstances(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_android_tools_ui_inspector_service_ArtToolingBridge_nativeRegisterEntryHook(
    JNIEnv* env, jclass callerClass, jlong artToolingPtr, jclass originClass,
    jstring originMethod) {
  auto* art_tooling =
      reinterpret_cast<ui_inspector::JvmtiArtTooling*>(artToolingPtr);
  profiler::JStringWrapper method_str(env, originMethod);
  std::size_t found = method_str.get().find("(");
  if (found == std::string::npos) {
    profiler::Log::E(kLogTag, "Invalid method signature for entry hook: %s",
                     method_str.get().c_str());
    return;
  }
  art_tooling->AddEntryTransform(env, originClass,
                                 method_str.get().substr(0, found),
                                 method_str.get().substr(found));
}

JNIEXPORT void JNICALL
Java_com_android_tools_ui_inspector_service_ArtToolingBridge_nativeRegisterExitHook(
    JNIEnv* env, jclass callerClass, jlong artToolingPtr, jclass originClass,
    jstring originMethod) {
  auto* art_tooling =
      reinterpret_cast<ui_inspector::JvmtiArtTooling*>(artToolingPtr);
  profiler::JStringWrapper method_str(env, originMethod);
  std::size_t found = method_str.get().find("(");
  if (found == std::string::npos) {
    profiler::Log::E(kLogTag, "Invalid method signature for exit hook: %s",
                     method_str.get().c_str());
    return;
  }
  art_tooling->AddExitTransform(env, originClass,
                                method_str.get().substr(0, found),
                                method_str.get().substr(found));
}

}  // extern "C"
