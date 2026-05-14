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

#ifndef COVERAGE_INSTRUMENTER_H_
#define COVERAGE_INSTRUMENTER_H_

#include <jni.h>
#include <jvmti.h>

#include <string>

namespace ir {
struct EncodedMethod;
struct MethodDecl;
struct DexFile;
}  // namespace ir

namespace coverage {

class Instrumenter {
 public:
  Instrumenter(jvmtiEnv* jvmti, const std::string& inclusion_prefix)
      : jvmti_(jvmti), inclusion_prefix_(inclusion_prefix) {}

  ~Instrumenter();

  // Registers the ClassFileLoadHook and enables the notification.
  bool RegisterHooks();

  // Iterates through all currently loaded classes and triggers a
  // retransformation for those that match the inclusion filter.
  void RetransformLoadedClasses(JNIEnv* jni);

 private:
  // JVMTI callback for the ClassFileLoadHook event.
  static void JNICALL OnClassFileLoadHook(
      jvmtiEnv* jvmti, JNIEnv* jni, jclass class_being_redefined,
      jobject loader, const char* name, jobject protection_domain,
      jint class_data_len, const unsigned char* class_data,
      jint* new_class_data_len, unsigned char** new_class_data);

  // Helper to determine if a class should be instrumented.
  bool ShouldInstrument(jobject loader, const char* name, jclass klass) const;

  // Instruments a single method by allocating scratch registers and injecting
  // coverage tracking calls. Returns true if the method was modified.
  bool InstrumentMethod(ir::EncodedMethod* ir_method,
                        ir::MethodDecl* hit_method_decl,
                        const std::shared_ptr<ir::DexFile>& dex_ir) const;

  jvmtiEnv* jvmti_;
  std::string inclusion_prefix_;

  // Tag value used to mark classes as already instrumented.
  static constexpr jlong kInstrumentedTag = 0xCAFE1234;

  static Instrumenter* instance_;
};

}  // namespace coverage

#endif  // COVERAGE_INSTRUMENTER_H_
