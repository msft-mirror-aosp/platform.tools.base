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
 * Implements the JVMTI event callbacks, class retransformation, heap-walking
 * traversals (backward-compatible tagging and Q+ API iteration), and allocation
 * of instrumented bytecode.
 */

#include "jvmti_art_tooling.h"

#include <mutex>
#include <vector>
#include "array_params_entry_hook.h"
#include "art_tooling_constants.h"
#include "slicer/reader.h"
#include "slicer/writer.h"
#include "tools/base/transport/native/jvmti/hidden_api_silencer.h"
#include "tools/base/transport/native/jvmti/jvmti_helper.h"
#include "tools/base/transport/native/jvmti/scoped_local_ref.h"
#include "tools/base/transport/native/utils/device_info.h"
#include "tools/base/transport/native/utils/log.h"

namespace art_tooling {

static std::recursive_mutex g_transforms_mutex;
static std::unordered_map<std::string, ArtToolingTransform*> g_transformations;

// Serializes instance discovery. An object holds a single JVMTI tag slot, so
// concurrent walks over overlapping types would clobber one another's tags
// before their GetObjectsWithTags queries run, yielding incomplete results.
static std::mutex g_find_instances_mutex;

void JvmtiArtTooling::Initialize() {
  profiler::SetAllCapabilities(jvmti_);

  jvmtiEventCallbacks callbacks;
  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.ClassFileLoadHook = OnClassFileLoaded;

  profiler::CheckJvmtiError(
      jvmti_, jvmti_->SetEventCallbacks(&callbacks, sizeof(callbacks)));

  bool filter_class_load_hook =
      profiler::DeviceInfo::feature_level() < profiler::DeviceInfo::P;
  profiler::SetEventNotification(
      jvmti_, filter_class_load_hook ? JVMTI_DISABLE : JVMTI_ENABLE,
      JVMTI_EVENT_CLASS_FILE_LOAD_HOOK);
}

void JvmtiArtTooling::AddTransform(JNIEnv* jni, const jclass& origin_class,
                                   const std::string& method_name,
                                   const std::string& signature,
                                   bool is_entry) {
  profiler::HiddenApiSilencer silencer(jvmti_);
  std::lock_guard<std::recursive_mutex> lock(g_transforms_mutex);
  std::string class_name = ClassDescriptor(origin_class);
  if (class_name.empty()) {
    profiler::Log::E(kLogTag, "Could not resolve the class descriptor for %s%s",
                     method_name.c_str(), signature.c_str());
    return;
  }
  auto transform_iter = g_transformations.find(class_name);
  ArtToolingTransform* transform;
  if (transform_iter == g_transformations.end()) {
    transform = new ArtToolingTransform(class_name.c_str());
    g_transformations.insert({class_name, transform});
  } else {
    transform = transform_iter->second;
  }
  transform->AddTransform(class_name.c_str(), method_name.c_str(),
                          signature.c_str(), is_entry);

  jthread thread = nullptr;
  jvmti_->GetCurrentThread(&thread);

  bool manually_toggle_load_hook =
      profiler::DeviceInfo::feature_level() < profiler::DeviceInfo::P;

  if (manually_toggle_load_hook) {
    profiler::CheckJvmtiError(
        jvmti_, jvmti_->SetEventNotificationMode(
                    JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, thread));
  }
  profiler::CheckJvmtiError(jvmti_,
                            jvmti_->RetransformClasses(1, &origin_class));
  if (manually_toggle_load_hook) {
    profiler::CheckJvmtiError(
        jvmti_, jvmti_->SetEventNotificationMode(
                    JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, thread));
  }

  if (thread != nullptr) {
    jni->DeleteLocalRef(thread);
  }
}

std::string JvmtiArtTooling::ClassDescriptor(jclass clazz) {
  char* signature = nullptr;
  if (profiler::CheckJvmtiError(
          jvmti_, jvmti_->GetClassSignature(clazz, &signature, nullptr))) {
    return "";
  }
  std::string descriptor(signature);
  profiler::Deallocate(jvmti_, signature);
  return descriptor;
}

class JvmtiAllocator : public dex::Writer::Allocator {
 public:
  JvmtiAllocator(jvmtiEnv* jvmti_env) : jvmti_env_(jvmti_env) {}

  virtual void* Allocate(size_t size) {
    return profiler::Allocate(jvmti_env_, size);
  }

  virtual void Free(void* ptr) { profiler::Deallocate(jvmti_env_, ptr); }

 private:
  jvmtiEnv* jvmti_env_;
};

void JvmtiArtTooling::OnClassFileLoaded(
    jvmtiEnv* jvmti_env, JNIEnv* jni_env, jclass class_being_redefined,
    jobject loader, const char* name, jobject protection_domain,
    jint class_data_len, const unsigned char* class_data,
    jint* new_class_data_len, unsigned char** new_class_data) {
  // JVMTI allows a null class name when the runtime has not derived one; there
  // is nothing to match a transform against in that case.
  if (name == nullptr) return;
  std::string desc = "L" + std::string(name) + ";";
  std::lock_guard<std::recursive_mutex> lock(g_transforms_mutex);
  auto transform = g_transformations.find(desc);
  if (transform == g_transformations.end()) return;

  dex::Reader reader(class_data, class_data_len);
  auto class_index = reader.FindClassIndex(desc.c_str());
  if (class_index == dex::kNoIndex) {
    profiler::Log::E(kLogTag, "Could not find class index for %s", name);
    return;
  }

  reader.CreateClassIr(class_index);
  auto dex_ir = reader.GetIr();
  transform->second->Apply(dex_ir);

  size_t new_image_size = 0;
  dex::u1* new_image = nullptr;
  dex::Writer writer(dex_ir);

  JvmtiAllocator allocator(jvmti_env);
  new_image = writer.CreateImage(&allocator, &new_image_size);
  if (new_image == nullptr) {
    // JVMTI reads untouched outputs as "no change", so the class stays as it
    // was. The error is logged because nothing else reports it.
    profiler::Log::E(kLogTag,
                     "Could not allocate the instrumented image for %s", name);
    return;
  }

  *new_class_data_len = new_image_size;
  *new_class_data = new_image;
}

jobjectArray JvmtiArtTooling::FindInstances(JNIEnv* jni, jclass clazz) {
  std::lock_guard<std::mutex> lock(g_find_instances_mutex);
  profiler::ScopedLocalRef<jclass> class_class(
      jni, jni->FindClass("java/lang/Class"));
  if (class_class.get() == nullptr) {
    // FindClass left an exception pending; it propagates to the Java caller.
    return nullptr;
  }
  if (jni->IsSameObject(clazz, class_class.get())) {
    jint count;
    jclass* classes;

    if (profiler::CheckJvmtiError(jvmti_,
                                  jvmti_->GetLoadedClasses(&count, &classes))) {
      return jni->NewObjectArray(0, clazz, NULL);
    }

    auto result = jni->NewObjectArray(count, clazz, NULL);
    for (int i = 0; i < count; ++i) {
      if (result != nullptr) {
        jni->SetObjectArrayElement(result, i, (jobject)classes[i]);
      }
      jni->DeleteLocalRef(classes[i]);
    }
    jvmti_->Deallocate((unsigned char*)classes);

    return result;
  }

  jlong tag = next_tag_++;

  bool error = profiler::DeviceInfo::feature_level() < profiler::DeviceInfo::Q
                   ? tagClassInstancesO(jni, clazz, tag)
                   : tagClassInstancesQ(clazz, tag);

  if (error) {
    return jni->NewObjectArray(0, clazz, NULL);
  }

  jint count;
  jobject* instances;
  if (profiler::CheckJvmtiError(
          jvmti_,
          jvmti_->GetObjectsWithTags(1, &tag, &count, &instances, NULL))) {
    return jni->NewObjectArray(0, clazz, NULL);
  }

  auto result = jni->NewObjectArray(count, clazz, NULL);
  for (int i = 0; i < count; ++i) {
    if (result != nullptr) {
      jni->SetObjectArrayElement(result, i, instances[i]);
    }
    jni->DeleteLocalRef(instances[i]);
  }
  jvmti_->Deallocate((unsigned char*)instances);

  return result;
}

static jint JNICALL HeapIterationCallback(jlong class_tag, jlong size,
                                          jlong* tag_ptr, jint length,
                                          void* user_data) {
  jlong tag = *(reinterpret_cast<jlong*>(user_data));
  *tag_ptr = tag;
  return 0;
}

bool JvmtiArtTooling::tagClassInstancesO(JNIEnv* jni, jclass clazz, jlong tag) {
  jvmtiHeapCallbacks heap_callbacks;
  jint count;
  jclass* classes;

  if (profiler::CheckJvmtiError(jvmti_,
                                jvmti_->GetLoadedClasses(&count, &classes))) {
    return true;
  }

  memset(&heap_callbacks, 0, sizeof(heap_callbacks));
  heap_callbacks.heap_iteration_callback =
      reinterpret_cast<decltype(heap_callbacks.heap_iteration_callback)>(
          HeapIterationCallback);
  bool error = false;
  for (int i = 0; i < count; ++i) {
    if (!error && jni->IsAssignableFrom(classes[i], clazz)) {
      error = profiler::CheckJvmtiError(
          jvmti_,
          jvmti_->IterateThroughHeap(0, classes[i], &heap_callbacks, &tag));
    }
    jni->DeleteLocalRef(classes[i]);
  }
  jvmti_->Deallocate((unsigned char*)classes);
  return error;
}

static jvmtiIterationControl JNICALL HeapObjectCallback(jlong class_tag,
                                                        jlong size,
                                                        jlong* tag_ptr,
                                                        void* user_data) {
  jlong tag = *(reinterpret_cast<jlong*>(user_data));
  *tag_ptr = tag;
  return JVMTI_ITERATION_CONTINUE;
}

bool JvmtiArtTooling::tagClassInstancesQ(jclass clazz, jlong tag) {
  return profiler::CheckJvmtiError(
      jvmti_, jvmti_->IterateOverInstancesOfClass(
                  clazz, JVMTI_HEAP_OBJECT_EITHER, HeapObjectCallback, &tag));
}

}  // namespace art_tooling
