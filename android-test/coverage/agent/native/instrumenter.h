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

#include <cstddef>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

#include "tools/base/android-test/coverage/agent/native/inclusion_list.h"

namespace android {
namespace tools {
namespace coverage {
namespace proto {
class ClassMetadata;
}  // namespace proto
}  // namespace coverage
}  // namespace tools
}  // namespace android

namespace ir {
struct EncodedMethod;
struct MethodDecl;
struct DexFile;
}  // namespace ir

namespace coverage {

struct FilterContext;

class Instrumenter {
 public:
  // Instruments the classes of `inclusion_list` if there is one, and otherwise
  // the classes with `inclusion_prefix`.
  Instrumenter(jvmtiEnv* jvmti, const std::string& inclusion_prefix,
               std::optional<InclusionList> inclusion_list = std::nullopt);

  ~Instrumenter();

  // JVMTI callback for the ClassFileLoadHook event.
  static void JNICALL OnClassFileLoadHook(
      jvmtiEnv* jvmti, JNIEnv* jni, jclass class_being_redefined,
      jobject loader, const char* name, jobject protection_domain,
      jint class_data_len, const unsigned char* class_data,
      jint* new_class_data_len, unsigned char** new_class_data);

  // Iterates through all currently loaded classes and triggers a
  // retransformation for those that match the inclusion filter.
  void RetransformLoadedClasses(JNIEnv* jni);

  // Adds the metadata of the classes in a dex file that match the inclusion
  // filter but were never loaded, so that they are reported with no coverage.
  // Their instrumented code is discarded: nothing is handed to ART.
  void AddUnloadedClasses(const unsigned char* dex, size_t size);

  // Returns true if the class name matches the inclusion filter. With a class
  // list, the class is in it, or it is the companion class `Foo$-CC` that d8
  // creates for an interface `Foo` in it. Otherwise, the class has the
  // inclusion prefix and isn't compiler-generated. The coverage runtime never
  // matches.
  bool MatchesInclusionFilter(std::string_view class_name) const;

  // Returns true if some class can match the inclusion filter: there is a
  // class list or an inclusion prefix.
  bool HasInclusionFilter() const;

  // Returns true if the class is a compiler-generated synthetic class.
  static bool IsSyntheticOrCompilerGenerated(std::string_view class_name);

 private:
  // Helper to determine if a class should be instrumented.
  bool ShouldInstrument(jobject loader, const char* name, jclass klass) const;

  // Instruments the class with the given descriptor in `dex_ir` and records its
  // metadata, as a loaded class or as one that never loaded. Returns true if
  // any method was modified.
  bool InstrumentClass(const std::shared_ptr<ir::DexFile>& dex_ir,
                       const std::string& descriptor, bool loaded) const;

  // Instruments a single method by allocating scratch registers and injecting
  // coverage tracking calls. `filter_context` describes the enclosing class
  // to the coverage filters. Returns true if the method was modified.
  bool InstrumentMethod(
      ir::EncodedMethod* ir_method, ir::MethodDecl* hit_method_decl,
      const std::shared_ptr<ir::DexFile>& dex_ir,
      android::tools::coverage::proto::ClassMetadata* class_meta,
      const FilterContext& filter_context) const;

  jvmtiEnv* jvmti_;
  std::string inclusion_prefix_;
  std::optional<InclusionList> inclusion_list_;

  // Tag value used to mark classes as already instrumented.
  static constexpr jlong kInstrumentedTag = 0xCAFE1234;

  static Instrumenter* instance_;
};

}  // namespace coverage

#endif  // COVERAGE_INSTRUMENTER_H_
