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

#include <limits>
#include <string_view>

#include "slicer/dex_ir.h"
#include "slicer/reader.h"
#include "slicer/writer.h"
#include "tools/base/android-test/coverage/common/log.h"

namespace coverage {

namespace {

/**
 * An allocator for Slicer that uses JVMTI's allocation mechanism.
 * This is necessary because ART will eventually deallocate the new class bytes
 * using JVMTI's Deallocate.
 */
class JvmtiAllocator : public dex::Writer::Allocator {
 public:
  explicit JvmtiAllocator(jvmtiEnv* jvmti) : jvmti_(jvmti) {}

  void* Allocate(size_t size) override {
    unsigned char* alloc = nullptr;
    jvmtiError error = jvmti_->Allocate(size, &alloc);
    if (error != JVMTI_ERROR_NONE) {
      Log::E("Error: JVMTI Allocate failed. Error code: %d", error);
      return nullptr;
    }
    return reinterpret_cast<void*>(alloc);
  }

  void Free(void* ptr) override {
    if (ptr == nullptr) {
      return;
    }
    jvmti_->Deallocate(reinterpret_cast<unsigned char*>(ptr));
  }

 private:
  jvmtiEnv* jvmti_;
};

}  // namespace

Instrumenter* Instrumenter::instance_ = nullptr;

Instrumenter::~Instrumenter() {
  if (instance_ == this) {
    instance_ = nullptr;
  }
}

/**
 * JVMTI callback for the ClassFileLoadHook event.
 */
void JNICALL Instrumenter::OnClassFileLoadHook(
    jvmtiEnv* jvmti, JNIEnv* jni, jclass class_being_redefined, jobject loader,
    const char* name, jobject protection_domain, jint class_data_len,
    const unsigned char* class_data, jint* new_class_data_len,
    unsigned char** new_class_data) {
  if (jvmti == nullptr || name == nullptr || class_data == nullptr ||
      class_data_len <= 0 || new_class_data_len == nullptr ||
      new_class_data == nullptr) {
    return;
  }

  if (instance_ == nullptr ||
      !instance_->ShouldInstrument(loader, name, class_being_redefined)) {
    return;
  }

  // TODO: Instrumentation logic will be added here.

  // The class name from JVMTI needs to be converted to a JNI descriptor.
  std::string descriptor = "L" + std::string(name) + ";";

  dex::Reader reader(class_data, class_data_len);
  auto class_index = reader.FindClassIndex(descriptor.c_str());
  if (class_index == dex::kNoIndex) {
    Log::W("Could not find class index for %s. Skipping instrumentation.",
           descriptor.c_str());
    return;
  }

  // Create an IR for only the specific class that ART is loading. This
  // effectively "prunes" the DEX so that the resulting byte array only contains
  // one class definition, which is a requirement for JVMTI class
  // redefinition/retransformation in ART.
  reader.CreateClassIr(class_index);
  auto dex_ir = reader.GetIr();
  if (dex_ir == nullptr) {
    Log::E("Slicer failed to generate IR for %s", descriptor.c_str());
    return;
  }

  // TODO: Apply basic block instrumentation here in future steps.

  dex::Writer writer(dex_ir);
  JvmtiAllocator allocator(jvmti);
  size_t new_image_size = 0;
  dex::u1* new_image = writer.CreateImage(&allocator, &new_image_size);

  if (new_image != nullptr) {
    if (new_image_size > std::numeric_limits<jint>::max()) {
      Log::E("Error: Generated DEX image size exceeds jint max for %s", name);
      jvmti->Deallocate(reinterpret_cast<unsigned char*>(new_image));
      return;
    }

    *new_class_data_len = static_cast<jint>(new_image_size);
    *new_class_data = reinterpret_cast<unsigned char*>(new_image);

    // After successful instrumentation, tag the class (if it exists)
    // to avoid future redundant instrumentation.
    // TODO: Handle tagging for classes instrumented during their initial load.
    if (class_being_redefined != nullptr) {
      jvmti->SetTag(class_being_redefined, kInstrumentedTag);
    }
  } else {
    Log::E("Slicer failed to produce new DEX image for %s", name);
  }
}

bool Instrumenter::ShouldInstrument(jobject loader, const char* name,
                                    jclass klass) const {
  if (name == nullptr) {
    return false;
  }

  // Check if the class is already instrumented.
  if (klass != nullptr) {
    jlong tag = 0;
    jvmti_->GetTag(klass, &tag);
    if (tag == kInstrumentedTag) {
      return false;
    }
  }

  // Don't instrument classes loaded by the bootstrap class loader (eg. OS
  // classes).
  if (loader == nullptr) {
    return false;
  }

  std::string_view class_name(name);

  // Only instrument the classes which belong to the package names provided
  // by the build system.
  // TODO: Update this logic to support a delimited list of multiple packages.
  if (inclusion_prefix_.empty()) {
    return false;
  }

  std::string_view inc_prefix(inclusion_prefix_);
  // The build system for this project is constrained to the C++17 standard.
  // Since std::string_view::starts_with() was only introduced in C++20, we
  // use rfind(prefix, 0) as a functionally equivalent and efficient way to
  // verify that the class name begins with our inclusion prefix without
  // incurring the overhead of string copies.
  if (class_name.size() < inc_prefix.size() ||
      class_name.rfind(inc_prefix, 0) != 0) {
    return false;
  }

  // If the class name is longer than the prefix, the character immediately
  // following the prefix match must be a separator ('/' or '$') unless
  // the prefix itself already ended with a separator.
  if (class_name.size() > inc_prefix.size() && inc_prefix.back() != '/' &&
      inc_prefix.back() != '$') {
    char next_char = class_name[inc_prefix.size()];
    if (next_char != '/' && next_char != '$') {
      return false;
    }
  }

  return true;
}

bool Instrumenter::RegisterHooks() {
  jvmtiEventCallbacks callbacks = {};
  callbacks.ClassFileLoadHook = &OnClassFileLoadHook;

  jvmtiError error = jvmti_->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (error != JVMTI_ERROR_NONE) {
    Log::E("Error: Unable to set JVMTI callbacks. Error code: %d", error);
    return false;
  }

  // Set the global instance pointer before enabling notifications to ensure
  // that no classes loaded during the registration process are missed.
  instance_ = this;

  error = jvmti_->SetEventNotificationMode(
      JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
  if (error != JVMTI_ERROR_NONE) {
    Log::E("Error: Unable to enable ClassFileLoadHook. Error code: %d", error);
    return false;
  }

  return true;
}

}  // namespace coverage
