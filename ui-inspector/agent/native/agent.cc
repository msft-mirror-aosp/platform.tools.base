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
#include <string>
#include "tools/base/transport/native/jvmti/hidden_api_silencer.h"
#include "tools/base/transport/native/jvmti/jvmti_helper.h"
#include "tools/base/transport/native/utils/log.h"

namespace {
constexpr const char* kLogTag = "studio.ui-inspector";
constexpr const char* kInspectorServiceClassName =
    "com/android/tools/ui/inspector/service/InspectorService";
constexpr const char* kInitializeMethodName = "initialize";
constexpr const char* kInitializeMethodSignature = "(Ljava/lang/String;)V";
}  // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options,
                                                 void* reserved) {
  profiler::Log::I(kLogTag, "UI Inspector Agent attaching...");

  if (options == nullptr || strlen(options) == 0) {
    profiler::Log::E(
        kLogTag, "Agent requires options pointing to ui-inspector-service.jar");
    return JNI_OK;  // Return OK to avoid ART retrying attachment
  }

  std::string service_jar_path = std::string(options);

  JNIEnv* env = profiler::GetThreadLocalJNI(vm);
  if (env == nullptr) {
    profiler::Log::E(kLogTag, "Could not attach to current thread.");
    return JNI_OK;
  }

  jvmtiEnv* jvmti = profiler::CreateJvmtiEnv(vm);
  if (jvmti == nullptr) {
    profiler::Log::E(kLogTag, "Could not get JVMTI env.");
    return JNI_OK;
  }

  // Bypass hidden API enforcement policy to access internal framework classes
  // for UI inspection
  static profiler::HiddenApiSilencer hiddenApiSilencer(jvmti);

  profiler::Log::I(kLogTag, "Adding jar to bootstrap search path: %s",
                   service_jar_path.c_str());
  if (jvmti->AddToBootstrapClassLoaderSearch(service_jar_path.c_str()) !=
      JVMTI_ERROR_NONE) {
    profiler::Log::E(kLogTag,
                     "Failed to add jar to bootstrap classloader search");
    return JNI_OK;
  }

  // Find InspectorService
  jclass serviceClass = env->FindClass(kInspectorServiceClassName);
  if (env->ExceptionCheck() || serviceClass == nullptr) {
    profiler::Log::E(kLogTag, "Failed to find %s", kInspectorServiceClassName);
    env->ExceptionDescribe();
    return JNI_OK;
  }

  // Get initialize method
  jmethodID initMethod = env->GetStaticMethodID(
      serviceClass, kInitializeMethodName, kInitializeMethodSignature);
  if (env->ExceptionCheck() || initMethod == nullptr) {
    profiler::Log::E(kLogTag, "Failed to find %s method",
                     kInitializeMethodName);
    return JNI_OK;
  }

  // Call initialize
  jstring args = env->NewStringUTF("Hello from Agent C++");
  env->CallStaticVoidMethod(serviceClass, initMethod, args);

  profiler::Log::I(kLogTag, "Agent attached successfully!");
  return JNI_OK;
}
