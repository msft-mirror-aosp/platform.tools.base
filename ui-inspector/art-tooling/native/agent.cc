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
#include <mutex>
#include <optional>
#include <string>
#include "agent_options.h"
#include "art_tooling_constants.h"
#include "art_tooling_jni.h"
#include "jvmti_art_tooling.h"
#include "tools/base/transport/native/jvmti/hidden_api_silencer.h"
#include "tools/base/transport/native/jvmti/jvmti_helper.h"
#include "tools/base/transport/native/jvmti/scoped_local_ref.h"
#include "tools/base/transport/native/utils/log.h"

namespace {

using art_tooling::kAgentLoaderClassName;
using art_tooling::kLogTag;

constexpr const char* kAttachMethodName = "attach";
constexpr const char* kAttachMethodSignature =
    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V";

// This pointer holds the JVMTI tooling engine of this loaded copy of the
// library. The library is never unloaded, so one engine serves every attach
// that reaches this copy. The first attach through this copy creates the
// engine, its JVMTI environment and the hidden-API silencer, and adds the
// library dex to the bootstrap search path. The pointer stays null when the
// JVMTI environment or the bootstrap search path could not be set up, and every
// later attach through this copy then fails as well.
//
// A process can hold several copies, because the loader treats each newly
// installed file as a new library. The copies coexist. The hook registry lives
// in the Java ArtTooling class on the bootstrap loader, which exists once per
// process, and each attach points that class at the engine of the copy it
// reached.
art_tooling::JvmtiArtTooling* g_art_tooling = nullptr;
std::once_flag g_art_tooling_once;

// Keeps hidden API enforcement disabled for the life of the process. The
// silencer restores the policy when destroyed, so it is never destroyed.
profiler::HiddenApiSilencer* g_hidden_api_silencer = nullptr;

// Runs the setup that happens once per loaded copy of the library. Returns the
// engine, or nullptr when the JVMTI environment or the bootstrap search path
// could not be set up.
art_tooling::JvmtiArtTooling* CreateArtTooling(JavaVM* vm,
                                               const std::string& library_dex) {
  jvmtiEnv* jvmti = profiler::CreateJvmtiEnv(vm);
  if (jvmti == nullptr) {
    profiler::Log::E(kLogTag, "Could not get JVMTI env.");
    return nullptr;
  }

  profiler::Log::I(kLogTag, "Adding library dex to bootstrap search path: %s",
                   library_dex.c_str());
  if (jvmti->AddToBootstrapClassLoaderSearch(library_dex.c_str()) !=
      JVMTI_ERROR_NONE) {
    profiler::Log::E(kLogTag,
                     "Failed to add library dex to bootstrap classloader "
                     "search");
    jvmti->DisposeEnvironment();
    return nullptr;
  }

  // Bypass hidden API enforcement so tools can reach internal framework
  // classes.
  g_hidden_api_silencer = new profiler::HiddenApiSilencer(jvmti);

  return new art_tooling::JvmtiArtTooling(jvmti);
}

// Logs and clears a pending Java exception, if any.
void ClearException(JNIEnv* env) {
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options,
                                                 void* reserved) {
  profiler::Log::I(kLogTag, "ART Tooling agent attaching...");

  auto parsed = art_tooling::ParseAgentOptions(options);
  if (!parsed) {
    profiler::Log::E(
        kLogTag,
        "Invalid options format. Expected: "
        "library_dex;agent_dex;agent_class;agent_options");
    return JNI_ERR;
  }

  JNIEnv* env = profiler::GetThreadLocalJNI(vm);
  if (env == nullptr) {
    profiler::Log::E(kLogTag, "Could not attach to current thread.");
    return JNI_ERR;
  }

  // Only the first attach through this copy adds its library dex to the
  // bootstrap search path. A later attach through this copy reuses the engine
  // as it is.
  std::call_once(g_art_tooling_once, [vm, &parsed]() {
    g_art_tooling = CreateArtTooling(vm, parsed->library_dex);
  });
  if (g_art_tooling == nullptr) {
    profiler::Log::E(kLogTag, "ART Tooling engine is not available.");
    return JNI_ERR;
  }
  jlong toolingPtr = reinterpret_cast<jlong>(g_art_tooling);

  // Bind the ArtTooling native methods explicitly. ArtTooling is invoked
  // synchronously below, before the JVM has registered this library for
  // automatic dynamic JNI resolution, so explicit binding prevents
  // UnsatisfiedLinkError. Binding again on a re-attach replaces the same
  // entry points.
  if (art_tooling::RegisterArtToolingNatives(env) != JNI_OK) {
    profiler::Log::E(kLogTag, "Failed to register ArtTooling native methods");
    ClearException(env);
    return JNI_ERR;
  }

  profiler::ScopedLocalRef<jclass> loaderClass(
      env, env->FindClass(kAgentLoaderClassName));
  if (env->ExceptionCheck() || loaderClass.get() == nullptr) {
    profiler::Log::E(kLogTag, "Failed to find %s", kAgentLoaderClassName);
    ClearException(env);
    return JNI_ERR;
  }

  jmethodID attachMethod = env->GetStaticMethodID(
      loaderClass.get(), kAttachMethodName, kAttachMethodSignature);
  if (env->ExceptionCheck() || attachMethod == nullptr) {
    profiler::Log::E(kLogTag, "Failed to find %s method", kAttachMethodName);
    ClearException(env);
    return JNI_ERR;
  }

  // NewStringUTF may not be called with a pending exception, so each allocation
  // is checked before the next.
  auto newString = [&](const std::string& value) -> jstring {
    jstring result = env->NewStringUTF(value.c_str());
    if (env->ExceptionCheck()) {
      profiler::Log::E(kLogTag, "Failed to allocate agent option string");
      ClearException(env);
      return nullptr;
    }
    return result;
  };

  profiler::ScopedLocalRef<jstring> agentDex(env, newString(parsed->agent_dex));
  if (agentDex.get() == nullptr) {
    return JNI_ERR;
  }
  profiler::ScopedLocalRef<jstring> agentClass(env,
                                               newString(parsed->agent_class));
  if (agentClass.get() == nullptr) {
    return JNI_ERR;
  }
  profiler::ScopedLocalRef<jstring> agentOptions(
      env, newString(parsed->agent_options));
  if (agentOptions.get() == nullptr) {
    return JNI_ERR;
  }

  env->CallStaticVoidMethod(loaderClass.get(), attachMethod, agentDex.get(),
                            agentClass.get(), agentOptions.get(), toolingPtr);

  if (env->ExceptionCheck()) {
    profiler::Log::E(kLogTag, "Exception in %s.%s", kAgentLoaderClassName,
                     kAttachMethodName);
    ClearException(env);
    return JNI_ERR;
  }

  profiler::Log::I(kLogTag, "Agent attached successfully!");
  return JNI_OK;
}
