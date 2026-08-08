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
 * JNI export bindings for ArtTooling. Routes its native
 * methods through to the JvmtiArtTooling implementation.
 */

#include "art_tooling_jni.h"

#include <string>
#include "art_tooling_constants.h"
#include "jvmti_art_tooling.h"
#include "tools/base/transport/native/agent/jni_wrappers.h"
#include "tools/base/transport/native/jvmti/scoped_local_ref.h"
#include "tools/base/transport/native/utils/log.h"

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_com_android_tools_arttooling_ArtTooling_nativeFindInstances(
    JNIEnv* env, jclass callerClass, jlong toolingPtr, jclass clazz) {
  auto* tooling = reinterpret_cast<art_tooling::JvmtiArtTooling*>(toolingPtr);
  return tooling->FindInstances(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_android_tools_arttooling_ArtTooling_nativeRegisterEntryHook(
    JNIEnv* env, jclass callerClass, jlong toolingPtr, jclass originClass,
    jstring originMethod) {
  auto* tooling = reinterpret_cast<art_tooling::JvmtiArtTooling*>(toolingPtr);
  profiler::JStringWrapper method_str(env, originMethod);
  std::size_t found = method_str.get().find("(");
  if (found == std::string::npos) {
    profiler::Log::E(art_tooling::kLogTag,
                     "Invalid method signature for entry hook: %s",
                     method_str.get().c_str());
    return;
  }
  tooling->AddEntryTransform(env, originClass,
                             method_str.get().substr(0, found),
                             method_str.get().substr(found));
}

JNIEXPORT void JNICALL
Java_com_android_tools_arttooling_ArtTooling_nativeRegisterExitHook(
    JNIEnv* env, jclass callerClass, jlong toolingPtr, jclass originClass,
    jstring originMethod) {
  auto* tooling = reinterpret_cast<art_tooling::JvmtiArtTooling*>(toolingPtr);
  profiler::JStringWrapper method_str(env, originMethod);
  std::size_t found = method_str.get().find("(");
  if (found == std::string::npos) {
    profiler::Log::E(art_tooling::kLogTag,
                     "Invalid method signature for exit hook: %s",
                     method_str.get().c_str());
    return;
  }
  tooling->AddExitTransform(env, originClass,
                            method_str.get().substr(0, found),
                            method_str.get().substr(found));
}

}  // extern "C"

namespace art_tooling {

// The method names and signatures below MUST match the native declarations in
// ArtTooling.java exactly. Updating a native declaration there requires
// updating this mapping in lockstep.
int RegisterArtToolingNatives(JNIEnv* env) {
  profiler::ScopedLocalRef<jclass> clazz(env,
                                         env->FindClass(kArtToolingClassName));
  if (clazz.get() == nullptr) {
    return JNI_ERR;
  }
  JNINativeMethod methods[] = {
      {(char*)"nativeFindInstances",
       (char*)"(JLjava/lang/Class;)[Ljava/lang/Object;",
       (void*)&Java_com_android_tools_arttooling_ArtTooling_nativeFindInstances},
      {(char*)"nativeRegisterEntryHook",
       (char*)"(JLjava/lang/Class;Ljava/lang/String;)V",
       (void*)&Java_com_android_tools_arttooling_ArtTooling_nativeRegisterEntryHook},
      {(char*)"nativeRegisterExitHook",
       (char*)"(JLjava/lang/Class;Ljava/lang/String;)V",
       (void*)&Java_com_android_tools_arttooling_ArtTooling_nativeRegisterExitHook},
  };
  return env->RegisterNatives(clazz.get(), methods,
                              sizeof(methods) / sizeof(methods[0]));
}

}  // namespace art_tooling
