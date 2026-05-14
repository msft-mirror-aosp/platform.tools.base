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

#include <atomic>
#include <limits>
#include <string_view>

#include "slicer/code_ir.h"
#include "slicer/control_flow_graph.h"
#include "slicer/dex_ir.h"
#include "slicer/dex_ir_builder.h"
#include "slicer/instrumentation.h"
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

// Global atomic counter for unique basic block IDs.
static std::atomic<uint32_t> g_next_block_id(0);

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

  // Pre-build the declaration for CoverageTracker.hit(int) once
  // per class to avoid redundant lookups for every method.
  ir::Builder builder(dex_ir);
  auto ir_proto = builder.GetProto(builder.GetType("V"),
                                   builder.GetTypeList({builder.GetType("I")}));

  auto hit_method_decl = builder.GetMethodDecl(
      builder.GetAsciiString("hit"), ir_proto,
      builder.GetType("Lcom/android/tools/coverage/CoverageTracker;"));

  bool modified = false;
  for (auto& ir_class : dex_ir->classes) {
    for (auto& method : ir_class->virtual_methods) {
      if (instance_->InstrumentMethod(method, hit_method_decl, dex_ir)) {
        modified = true;
      }
    }
    for (auto& method : ir_class->direct_methods) {
      if (instance_->InstrumentMethod(method, hit_method_decl, dex_ir)) {
        modified = true;
      }
    }
  }

  if (!modified) {
    return;
  }

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

bool Instrumenter::InstrumentMethod(
    ir::EncodedMethod* ir_method, ir::MethodDecl* hit_method_decl,
    const std::shared_ptr<ir::DexFile>& dex_ir) const {
  if (ir_method->code == nullptr) {
    return false;  // Abstract or native method
  }

  lir::CodeIr code_ir(ir_method, dex_ir);

  // Allocate 1 scratch register for our instrumentation.
  slicer::AllocateScratchRegs alloc_regs(1);
  if (!alloc_regs.Apply(&code_ir) || alloc_regs.ScratchRegs().empty()) {
    Log::W("Failed to allocate scratch register for method %s. Skipping.",
           ir_method->decl->name->c_str());
    return false;
  }

  dex::u4 scratch_reg = *alloc_regs.ScratchRegs().begin();

  lir::ControlFlowGraph cfg(&code_ir, true);

  bool injected = false;
  for (const auto& block : cfg.basic_blocks) {
    lir::Instruction* trace_point = nullptr;

    if (block.region.first == nullptr) continue;

    // Find the first bytecode instruction in this basic block.
    for (auto instr = block.region.first; instr != nullptr;
         instr = (instr == block.region.last) ? nullptr : instr->next) {
      if (dynamic_cast<lir::Bytecode*>(instr)) {
        trace_point = instr;
        break;
      }
    }

    if (trace_point == nullptr) continue;

    // Dalvik requires that OP_MOVE_RESULT_* and OP_MOVE_EXCEPTION instructions
    // immediately follow the instruction that produced the result/exception.
    // We cannot safely inject code between them, so we advance the trace point.
    while (auto trace_bytecode = dynamic_cast<lir::Bytecode*>(trace_point)) {
      auto opcode = trace_bytecode->opcode;
      if (opcode != dex::OP_MOVE_RESULT && opcode != dex::OP_MOVE_RESULT_WIDE &&
          opcode != dex::OP_MOVE_RESULT_OBJECT &&
          opcode != dex::OP_MOVE_EXCEPTION) {
        break;
      }
      trace_point = trace_point->next;
    }

    if (trace_point == nullptr) continue;

    // Assign a globally unique block ID only for blocks we are actually
    // instrumenting.
    uint32_t block_id = g_next_block_id.fetch_add(1);

    // Inject: const vX, <block_id>
    auto load_id = code_ir.Alloc<lir::Bytecode>();
    load_id->opcode = dex::OP_CONST;
    load_id->operands.push_back(code_ir.Alloc<lir::VReg>(scratch_reg));
    load_id->operands.push_back(
        code_ir.Alloc<lir::Const32>(static_cast<dex::u4>(block_id)));

    // Inject: invoke-static/range {vX}, CoverageTracker.hit(I)V
    auto call_mark = code_ir.Alloc<lir::Bytecode>();
    call_mark->opcode = dex::OP_INVOKE_STATIC_RANGE;
    call_mark->operands.push_back(
        code_ir.Alloc<lir::VRegRange>(scratch_reg, 1));
    call_mark->operands.push_back(code_ir.Alloc<lir::Method>(
        hit_method_decl, hit_method_decl->orig_index));

    code_ir.instructions.InsertBefore(trace_point, load_id);
    code_ir.instructions.InsertBefore(trace_point, call_mark);
    injected = true;

    // TODO: Record the mapping of block_id -> source file / line numbers here.
  }

  if (!injected) {
    return false;
  }

  code_ir.Assemble();
  return true;
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

  // Explicitly skip our own runtime tracker to avoid infinite recursion.
  if (class_name == "com/android/tools/coverage/CoverageTracker") {
    return false;
  }

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

void Instrumenter::RetransformLoadedClasses(JNIEnv* jni) {
  jint class_count = 0;
  jclass* classes = nullptr;
  jvmtiError error = jvmti_->GetLoadedClasses(&class_count, &classes);
  if (error != JVMTI_ERROR_NONE) {
    Log::E("Error: GetLoadedClasses failed. Error code: %d", error);
    return;
  }

  std::vector<jclass> candidates;
  for (jint i = 0; i < class_count; ++i) {
    jclass klass = classes[i];

    // 1. Performance Optimization: Quick Prefix Check on Signature (Fastest)
    char* sig_ptr = nullptr;
    error = jvmti_->GetClassSignature(klass, &sig_ptr, nullptr);
    if (error != JVMTI_ERROR_NONE) {
      jni->DeleteLocalRef(klass);
      continue;
    }

    std::string_view sig(sig_ptr);
    bool prefix_match = false;
    std::string internal_name;

    // JNI signature format: "Lcom/example/MyClass;"
    if (sig.length() > 2 && sig.front() == 'L' && sig.back() == ';') {
      // Fast check: does the signature (skipping 'L') start with our prefix?
      std::string_view name_view = sig.substr(1, sig.length() - 2);
      if (name_view.rfind(inclusion_prefix_, 0) == 0) {
        prefix_match = true;
        internal_name = std::string(name_view);
      }
    }

    if (!prefix_match) {
      jvmti_->Deallocate(reinterpret_cast<unsigned char*>(sig_ptr));
      jni->DeleteLocalRef(klass);
      continue;
    }

    // 2. Validation: Check Loader and Tags
    jobject loader = nullptr;
    jvmti_->GetClassLoader(klass, &loader);

    bool should_instrument =
        ShouldInstrument(loader, internal_name.c_str(), klass);

    if (loader != nullptr) {
      jni->DeleteLocalRef(loader);
    }

    // 3. Capability Check: Is it actually modifiable?
    if (should_instrument) {
      jboolean modifiable = JNI_FALSE;
      jvmti_->IsModifiableClass(klass, &modifiable);
      if (modifiable) {
        // Use GlobalRef to avoid exceeding JNI local reference limits
        // (typically 512).
        candidates.push_back(
            reinterpret_cast<jclass>(jni->NewGlobalRef(klass)));
      }
    }

    // Clean up temporary references for this iteration
    jvmti_->Deallocate(reinterpret_cast<unsigned char*>(sig_ptr));
    jni->DeleteLocalRef(klass);
  }

  if (!candidates.empty()) {
    Log::I("Retransforming %zu loaded classes...", candidates.size());
    error = jvmti_->RetransformClasses(static_cast<jint>(candidates.size()),
                                       candidates.data());
    if (error != JVMTI_ERROR_NONE) {
      Log::E("Error: RetransformClasses failed. Error code: %d", error);
    }

    // Clean up GlobalRefs
    for (jclass global_klass : candidates) {
      jni->DeleteGlobalRef(global_klass);
    }
  }

  jvmti_->Deallocate(reinterpret_cast<unsigned char*>(classes));
}

}  // namespace coverage
