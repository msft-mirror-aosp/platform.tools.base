#include "tools/base/debuggers/native/coroutine/agent/jni_utils.h"
#include "tools/base/transport/native/jvmti/jvmti_helper.h"
#include "tools/base/transport/native/utils/log.h"

#include "slicer/dex_ir.h"
#include "slicer/instrumentation.h"
#include "slicer/reader.h"
#include "slicer/writer.h"
#include "stdlib.h"

/**
 * This agent works as follow:
 * 1. Register a ClassFileLoadHook.
 * 2. Monitor to find kotlin/coroutines/jvm/internal/DebugProbesKt.
 * 3. Check that coroutine lib version is supported (version must be higher
 * than 1.6.0)
 * 4. Set AgentInstallationType#isInstalledStatically to true. This tells
 *    the coroutine lib that DebugProbesKt should not be replaced
 *    lazily when DebugProbesImpl#install is called.
 *    The lazy replacement uses ByteBuddy and Java instrumentation
 *    apis, that are not supported on Android.
 * 5. Call `install` on DebugProbesImpl.
 * 6. On found, instrument methods in
 * kotlin/coroutines/jvm/internal/DebugProbesK to call methods in
 * kotlinx/coroutines/debug/internal/DebugProbesKt.
 * 7. Unregister ClassFileLoadHook.
 */

struct SemanticVersion {
  uint32_t major;
  uint32_t minor;
  uint32_t patch;
};

struct InstrumentedClass {
  unsigned char* new_class_data;
  size_t new_class_data_len;
  bool success;
};

const std::string kDebug_debugProbesKt =
    "Lkotlinx/coroutines/debug/internal/DebugProbesKt;";
const std::string kStdlib_debugProbesKt =
    "Lkotlin/coroutines/jvm/internal/DebugProbesKt;";

const std::string kMeta_inf_version_path =
    "META-INF/kotlinx_coroutines_core.version";

const SemanticVersion kCoroutines_min_supported_version = {
    .major = 1, .minor = 6, .patch = 0};

// Class required by dex::Writer to allocate and free space for new instrumented
// class
class JvmtiAllocator : public dex::Writer::Allocator {
 public:
  JvmtiAllocator(jvmtiEnv* jvmti_env) : jvmti_env_(jvmti_env) {}

  virtual void* Allocate(size_t size) {
    unsigned char* alloc = nullptr;
    jvmtiError err_num = jvmti_env_->Allocate(size, &alloc);

    if (err_num != JVMTI_ERROR_NONE) {
      profiler::Log::E(profiler::Log::Tag::COROUTINE_DEBUGGER,
                       "JVMTI error: %d", err_num);
    }

    return (void*)alloc;
  }

  virtual void Free(void* ptr) {
    if (ptr == nullptr) {
      return;
    }

    jvmtiError err_num = jvmti_env_->Deallocate((unsigned char*)ptr);

    if (err_num != JVMTI_ERROR_NONE) {
      profiler::Log::E(profiler::Log::Tag::COROUTINE_DEBUGGER,
                       "JVMTI error: %d", err_num);
    }
  }

 private:
  jvmtiEnv* jvmti_env_;
};

// Gets the exceptions stacktrace and logs it.
void printStackTrace(JNIEnv* jni) {
  std::unique_ptr<jniutils::StackTrace> stackTrace =
      jniutils::getExceptionStackTrace(jni);
  if (stackTrace == nullptr) {
    return;
  }
  std::string stringStackTrace = jniutils::stackTraceToString(move(stackTrace));
  profiler::Log::E(profiler::Log::Tag::COROUTINE_DEBUGGER, "%s",
                   stringStackTrace.c_str());
}

// Check if DebugProbesImpl exists, then calls
// DebugProbesImpl#install.
bool installDebugProbes(JNIEnv* jni, std::string* error_msg) {
  const std::string debug_probes_impl_class_name =
      "kotlinx/coroutines/debug/internal/DebugProbesImpl";
  jniutils::ScopedLocalRef<jclass> klass(
      jni, jni->FindClass(debug_probes_impl_class_name.c_str()));
  if (klass.get() == nullptr) {
    *error_msg = "Class " + debug_probes_impl_class_name + " not found";
    return false;
  }

  // get DebugProbesImpl constructor
  jmethodID constructor = jni->GetMethodID(klass.get(), "<init>", "()V");
  if (constructor == nullptr) {
    *error_msg =
        "Constructor of " + debug_probes_impl_class_name + " not found";
    return false;
  }

  // create DebugProbesImpl by calling constructor
  jniutils::ScopedLocalRef<jobject> debug_probes_impl_obj(
      jni, jni->NewObject(klass.get(), constructor));
  if (jni->ExceptionOccurred()) {
    *error_msg = "Constructor of " + debug_probes_impl_class_name +
                 " threw an exception";
    return false;
  }

  // get install method id
  jmethodID install = jni->GetMethodID(klass.get(), "install", "()V");
  if (install == nullptr) {
    *error_msg =
        "Method " + debug_probes_impl_class_name + "#install()V not found";
    return false;
  }

  // invoke install method
  jni->CallVoidMethod(debug_probes_impl_obj.get(), install);

  if (jni->ExceptionOccurred()) {
    *error_msg = "Method " + debug_probes_impl_class_name +
                 "#install threw an exception";
    return false;
  }
  return true;
}

/**
 * Instruments DebugProbesKt from kotlin stdlib, to call respective methods in
 * DebugProbesKt from kotlinx-coroutines-core
 */
InstrumentedClass instrumentClass(jvmtiEnv* jvmti, std::string class_name,
                                  const unsigned char* class_data,
                                  int class_data_len, std::string* error_msg) {
  InstrumentedClass instrumentedClass{};

  dex::Reader reader(class_data, class_data_len);
  auto class_index = reader.FindClassIndex(class_name.c_str());
  if (class_index == dex::kNoIndex) {
    *error_msg = "Could not find class index for " + class_name;
    instrumentedClass.success = false;
    return instrumentedClass;
  }

  reader.CreateClassIr(class_index);
  auto dex_ir = reader.GetIr();

  // TODO(b/182023904): instead of hard coding the methods we should iterate
  // over all the methods of kotlinx/coroutines/debug/internal/DebugProbesKt and
  // match them with methods in kotlinx/coroutines/debug/internal/DebugProbesKt

  // probeCoroutineCreated
  slicer::MethodInstrumenter miCreated(dex_ir);
  miCreated.AddTransformation<slicer::ExitHook>(
      ir::MethodId(kDebug_debugProbesKt.c_str(), "probeCoroutineCreated"));

  if (!miCreated.InstrumentMethod(
          ir::MethodId(kStdlib_debugProbesKt.c_str(), "probeCoroutineCreated",
                       "(Lkotlin/coroutines/Continuation;)Lkotlin/coroutines/"
                       "Continuation;"))) {
    *error_msg = "Error instrumenting DebugProbesKt.probeCoroutineCreated";
    instrumentedClass.success = false;
    return instrumentedClass;
  }

  // probeCoroutineResumed
  slicer::MethodInstrumenter miResumed(dex_ir);
  miResumed.AddTransformation<slicer::EntryHook>(
      ir::MethodId(kDebug_debugProbesKt.c_str(), "probeCoroutineResumed"));

  if (!miResumed.InstrumentMethod(
          ir::MethodId(kStdlib_debugProbesKt.c_str(), "probeCoroutineResumed",
                       "(Lkotlin/coroutines/Continuation;)V"))) {
    *error_msg = "Error instrumenting DebugProbesKt.probeCoroutineResumed";
    instrumentedClass.success = false;
    return instrumentedClass;
  }

  // probeCoroutineSuspended
  slicer::MethodInstrumenter miSuspended(dex_ir);
  miSuspended.AddTransformation<slicer::EntryHook>(
      ir::MethodId(kDebug_debugProbesKt.c_str(), "probeCoroutineSuspended"));

  if (!miSuspended.InstrumentMethod(
          ir::MethodId(kStdlib_debugProbesKt.c_str(), "probeCoroutineSuspended",
                       "(Lkotlin/coroutines/Continuation;)V"))) {
    *error_msg = "Error instrumenting DebugProbesKt.probeCoroutineSuspended";
    instrumentedClass.success = false;
    return instrumentedClass;
  }

  size_t new_image_size = 0;
  dex::u1* new_image = nullptr;
  dex::Writer writer(dex_ir);

  JvmtiAllocator allocator(jvmti);
  new_image = writer.CreateImage(&allocator, &new_image_size);

  if (new_image == nullptr) {
    instrumentedClass.success = false;
    *error_msg = "Failed to create new image for class " + class_name;
    return instrumentedClass;
  }

  instrumentedClass.new_class_data = new_image;
  instrumentedClass.new_class_data_len = new_image_size;
  instrumentedClass.success = true;

  return instrumentedClass;
}

/**
 * Try to set
 * kotlinx.coroutines.debug.AgentInstallationType#setInstalledStatically$kotlinx_coroutines_core
 * to true.
 */
bool setAgentInstallationType(JNIEnv* jni, std::string* error_msg) {
  const std::string class_name = "AgentInstallationType";
  const std::string class_full_name =
      "kotlinx/coroutines/debug/internal/" + class_name;
  const std::string method_name =
      "setInstalledStatically$kotlinx_coroutines_core";

  jniutils::ScopedLocalRef<jclass> klass_agentInstallationType(
      jni, jni->FindClass(class_full_name.c_str()));
  if (klass_agentInstallationType.get() == nullptr) {
    *error_msg = "Class " + class_full_name + " not found";
    return false;
  }

  const std::string sig = "L" + class_full_name + ";";
  jfieldID instance_filedId = jni->GetStaticFieldID(
      klass_agentInstallationType.get(), "INSTANCE", sig.c_str());

  if (instance_filedId == nullptr) {
    *error_msg = class_full_name + "#INSTANCE not found";
    return false;
  }

  jniutils::ScopedLocalRef<jobject> obj_agentInstallationType(
      jni, jni->GetStaticObjectField(klass_agentInstallationType.get(),
                                     instance_filedId));
  if (obj_agentInstallationType.get() == nullptr) {
    *error_msg = "Failed to retrieve " + class_full_name + "#INSTANCE";
    return false;
  }

  jmethodID mid_setIsInstalledStatically = jni->GetMethodID(
      klass_agentInstallationType.get(), method_name.c_str(), "(Z)V");
  if (mid_setIsInstalledStatically == nullptr) {
    *error_msg = class_full_name + "#" + method_name + "(Z)V not found";
    return false;
  }

  jni->CallVoidMethod(obj_agentInstallationType.get(),
                      mid_setIsInstalledStatically, true);

  if (jni->ExceptionOccurred()) {
    *error_msg =
        class_full_name + "#" + method_name + "(Z)V threw an exception";
    return false;
  }
  return true;
}

// Returns a java.net.URL for the requested resource, or nullptr if something
// goes wrong
// Callers of this function are responsible for handling exceptions.
jobject classloader_get_resource(JNIEnv* jni, jobject class_loader_object,
                                 jstring resource_path) {
  jniutils::ScopedLocalRef<jclass> klass_class_loader(
      jni, jni->FindClass("java/lang/ClassLoader"));
  if (klass_class_loader.get() == nullptr) {
    return nullptr;
  }

  jmethodID class_loader_getResource =
      jni->GetMethodID(klass_class_loader.get(), "getResource",
                       "(Ljava/lang/String;)Ljava/net/URL;");
  if (class_loader_getResource == nullptr) {
    return nullptr;
  }

  return jni->CallObjectMethod(class_loader_object, class_loader_getResource,
                               resource_path);
}

// Extracts the major, minor and patch components from
// the semantic version string passed as input and adds them
// to the SemanticVersion pointer passed as input.
// returns true in case of success, false otherwise.
// the semantic version is parsed following these grammar rules:
// https://semver.org/#backusnaur-form-grammar-for-valid-semver-versions
// Callers of this function are responsible for handling exceptions.
bool extractTokensFromSemanticVersion(const std::string& semantic_version,
                                      SemanticVersion* lib_semantic_version,
                                      std::string* error_msg) {
  // Safely parse the version string without risking out-of-bounds array reads.
  // This approach is more robust against malformed version strings and ensures
  // memory safety by using std::vector for indices and bounds-checked strtol
  // parsing.
  std::vector<int> separator_indices;
  for (int i = 0; i < semantic_version.size(); i++) {
    char c = semantic_version[i];
    if (c == '.' || c == '-' || c == '+') {
      separator_indices.push_back(i);
    }
  }

  // semantic version string not well formed
  // if string is well formed we should have at least 2 separators for
  // major.minor.patch However, "1.2.3" might only have 2 separators (the dots).
  if (separator_indices.size() < 2) {
    *error_msg = "Version of kotlinx-coroutines '" + semantic_version +
                 "' not well formed according to semantic versioning.";
    return false;
  }

  int major_start = 0;
  int major_end = separator_indices[0];
  int minor_start = separator_indices[0] + 1;
  int minor_end = separator_indices[1];
  int patch_start = separator_indices[1] + 1;
  int patch_end = (separator_indices.size() > 2) ? separator_indices[2]
                                                 : semantic_version.size();

  auto parse_part = [&](int start, int end, uint32_t* value) {
    if (start >= end) return false;
    std::string s = semantic_version.substr(start, end - start);
    char* endptr;
    long val = strtol(s.c_str(), &endptr, 10);
    if (endptr == s.c_str() || val < 0) return false;
    *value = static_cast<uint32_t>(val);
    return true;
  };

  if (!parse_part(major_start, major_end, &lib_semantic_version->major) ||
      !parse_part(minor_start, minor_end, &lib_semantic_version->minor) ||
      !parse_part(patch_start, patch_end, &lib_semantic_version->patch)) {
    *error_msg = "Version of kotlinx-coroutines '" + semantic_version +
                 "' not well formed according to semantic versioning.";
    return false;
  }

  return true;
}

// Takes a string representing the semantic version of a library eg.
// 1.5.0, 1.6.1-SNAPSHOT and returns true or false if the version if bigger or
// equal to the min supported coroutines version
// Callers of this function are responsible for handling exceptions.
bool is_supported(std::string semantic_version, std::string* error_msg) {
  SemanticVersion lib_semantic_version = SemanticVersion();
  if (!extractTokensFromSemanticVersion(semantic_version, &lib_semantic_version,
                                        error_msg)) {
    return false;
  }

  if (lib_semantic_version.major == kCoroutines_min_supported_version.major) {
    if (lib_semantic_version.minor == kCoroutines_min_supported_version.minor) {
      return lib_semantic_version.patch >=
             kCoroutines_min_supported_version.patch;
    } else {
      return lib_semantic_version.minor >
             kCoroutines_min_supported_version.minor;
    }
  } else {
    return lib_semantic_version.major > kCoroutines_min_supported_version.major;
  }
}

// Callers of this function are responsible for handling exceptions.
bool close_input_stream(JNIEnv* jni, jobject input_stream_obj,
                        jmethodID input_stream_close) {
  if (jni->ExceptionCheck()) {
    return false;
  }

  jni->CallVoidMethod(input_stream_obj, input_stream_close);
  if (jni->ExceptionCheck()) {
    return false;
  }

  return true;
}

// This function:
//   1. gets "META-INF/kotlinx_coroutines_core.version" from the classloader
//   resources.
//   2. Opens a Java InputStream to read it.
//   3. Closes it.
//   4. Checks if the version of the library (read from the file) is 1.6.0+
//   5. Returns true if 1.6.0+, false otherwise.
// Callers of this function are responsible for handling exceptions.
bool isUsingSupportedCoroutinesVersion(JNIEnv* jni, jobject class_loader_object,
                                       std::string* error_msg) {
  // create jstring containing META-INF/*.version path
  jniutils::ScopedLocalRef<jstring> meta_inf_version_path_jstring(
      jni, jni->NewStringUTF(kMeta_inf_version_path.c_str()));
  if (meta_inf_version_path_jstring.get() == nullptr) {
    return false;
  }

  // get java.net.URL for version file resource.
  jniutils::ScopedLocalRef<jobject> version_file_url(
      jni, classloader_get_resource(jni, class_loader_object,
                                    meta_inf_version_path_jstring));
  if (version_file_url.get() == nullptr) {
    // META-INF/*.version file not found, app is using kotlinx-coroutines older
    // than 1.6.0
    *error_msg =
        "The version of kotlinx-coroutines-core used by the app is not "
        "supported. Should be 1.6.0 or higher.";
    return false;
  }

  // get required java classes and methods
  jniutils::ScopedLocalRef<jclass> klass_url(jni,
                                             jni->FindClass("java/net/URL"));
  if (klass_url.get() == nullptr) {
    return false;
  }

  jniutils::ScopedLocalRef<jclass> klass_input_stream(
      jni, jni->FindClass("java/io/InputStream"));
  if (klass_input_stream.get() == nullptr) {
    return false;
  }

  jniutils::ScopedLocalRef<jclass> klass_scanner(
      jni, jni->FindClass("java/util/Scanner"));
  if (klass_scanner.get() == nullptr) {
    return false;
  }

  jmethodID url_openStream =
      jni->GetMethodID(klass_url, "openStream", "()Ljava/io/InputStream;");
  if (url_openStream == nullptr) {
    return false;
  }

  jmethodID input_stream_close =
      jni->GetMethodID(klass_input_stream, "close", "()V");
  if (input_stream_close == nullptr) {
    return false;
  }

  jmethodID scanner_nextLine =
      jni->GetMethodID(klass_scanner, "nextLine", "()Ljava/lang/String;");
  if (scanner_nextLine == nullptr) {
    return false;
  }

  jmethodID scanner_constructor =
      jni->GetMethodID(klass_scanner, "<init>", "(Ljava/io/InputStream;)V");
  if (scanner_constructor == nullptr) {
    return false;
  }

  // open the input stream for version_file_url
  jniutils::ScopedLocalRef<jobject> input_stream_obj(
      jni, jni->CallObjectMethod(version_file_url, url_openStream));
  if (input_stream_obj.get() == nullptr) {
    return false;
  }

  // create the java.util.Scanner passing the input stream to the constructor
  jniutils::ScopedLocalRef<jobject> scanner_obj(
      jni, jni->NewObject(klass_scanner, scanner_constructor,
                          input_stream_obj.get()));
  if (scanner_obj.get() == nullptr) {
    close_input_stream(jni, input_stream_obj, input_stream_close);
    return false;
  }

  // read next line from the scanner
  jniutils::ScopedLocalRef<jstring> lib_version_jstring(
      jni, (jstring)jni->CallObjectMethod(scanner_obj, scanner_nextLine));
  if (lib_version_jstring.get() == nullptr) {
    close_input_stream(jni, input_stream_obj, input_stream_close);
    return false;
  }

  if (!close_input_stream(jni, input_stream_obj, input_stream_close)) {
    return false;
  }

  // convert jstring to char*
  jniutils::ScopedUtfChars version_str(jni, lib_version_jstring);
  if (jni->ExceptionCheck() || version_str.c_str() == nullptr) {
    return false;
  }

  // check that the version is higher or equal to 1.6.0
  return is_supported(version_str.c_str(), error_msg);
}

// clears exceptions and disables ClassFileLoadHook
void classFileLoadHook_CleanUp(jvmtiEnv* jvmti, JNIEnv* jni,
                               std::string error_msg) {
  if (!error_msg.empty()) {
    profiler::Log::E(profiler::Log::Tag::COROUTINE_DEBUGGER, "%s",
                     error_msg.c_str());
  }

  if (jni->ExceptionCheck()) {
    printStackTrace(jni);
    jni->ExceptionClear();
  }
  profiler::SetEventNotification(jvmti, JVMTI_DISABLE,
                                 JVMTI_EVENT_CLASS_FILE_LOAD_HOOK);
}

static void JNICALL
ClassFileLoadHook(jvmtiEnv* jvmti, JNIEnv* jni, jclass class_being_redefined,
                  jobject loader, const char* name, jobject protection_domain,
                  jint class_data_len, const unsigned char* class_data,
                  jint* new_class_data_len, unsigned char** new_class_data) {
  // do nothing if class is not DebugProbesKt
  const std::string class_name = "L" + std::string(name) + ";";
  if (class_name != "Lkotlin/coroutines/jvm/internal/DebugProbesKt;") {
    return;
  }

  // check if coroutines version is supported
  std::string error_msg;
  if (!isUsingSupportedCoroutinesVersion(jni, loader, &error_msg)) {
    classFileLoadHook_CleanUp(jvmti, jni, error_msg);
    return;
  }

  // set AgentInstallationType#isInstalledStatically to true
  bool setAgentInstallationTypeSuccessful =
      setAgentInstallationType(jni, &error_msg);
  if (!setAgentInstallationTypeSuccessful) {
    classFileLoadHook_CleanUp(jvmti, jni, error_msg);
    return;
  }

  // call DebugProbesImpl#install
  bool installed = installDebugProbes(jni, &error_msg);
  if (!installed) {
    classFileLoadHook_CleanUp(jvmti, jni, error_msg);
    return;
  }

  // check if kotlinx/coroutines/debug/internal/DebugProbesKt is loadable
  jniutils::ScopedLocalRef<jclass> klass(
      jni, jni->FindClass("kotlinx/coroutines/debug/internal/DebugProbesKt"));
  if (klass.get() == nullptr) {
    error_msg =
        "Couldn't find class kotlinx/coroutines/debug/internal/DebugProbesKt";
    // clear exception thrown by failed FindClass
    classFileLoadHook_CleanUp(jvmti, jni, error_msg);
    return;
  }

  // instrument kotlin/coroutines/jvm/internal/DebugProbesKt to call methods in
  // kotlinx/coroutines/debug/internal/DebugProbesKt
  InstrumentedClass instrumentedClass = instrumentClass(
      jvmti, class_name, class_data, class_data_len, &error_msg);
  if (!instrumentedClass.success) {
    error_msg =
        "Instrumentation of kotlin/coroutines/jvm/internal/DebugProbesKt "
        "failed. " +
        error_msg;
    classFileLoadHook_CleanUp(jvmti, jni, error_msg);
    return;
  }

  *new_class_data_len = instrumentedClass.new_class_data_len;
  *new_class_data = instrumentedClass.new_class_data;

  // DebugProbesKt is the only class we need to transform, so we can disable
  // events
  profiler::SetEventNotification(jvmti, JVMTI_DISABLE,
                                 JVMTI_EVENT_CLASS_FILE_LOAD_HOOK);
}

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options,
                                                 void* reserved) {
  // This will attach the current thread to the vm, otherwise CreateJvmtiEnv(vm)
  // below will return JNI_EDETACHED error code.
  profiler::GetThreadLocalJNI(vm);

  jvmtiEnv* jvmti = profiler::CreateJvmtiEnv(vm);
  if (jvmti == nullptr) {
    profiler::Log::E(profiler::Log::Tag::COROUTINE_DEBUGGER,
                     "Failed to initialize JVMTI env.");
    return -1;
  }

  // set JVMTI capabilities
  jvmtiCapabilities capa;
  bool hasError =
      profiler::CheckJvmtiError(jvmti, jvmti->GetPotentialCapabilities(&capa));
  if (hasError) {
    profiler::Log::E(profiler::Log::Tag::COROUTINE_DEBUGGER,
                     "JVMTI GetPotentialCapabilities error.");
    return -1;
  }
  profiler::SetAllCapabilities(jvmti);

  // set JVMTI callbacks
  jvmtiEventCallbacks callbacks;
  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.ClassFileLoadHook = &ClassFileLoadHook;

  hasError = profiler::CheckJvmtiError(
      jvmti, jvmti->SetEventCallbacks(&callbacks, (jint)sizeof(callbacks)));
  if (hasError) {
    profiler::Log::E(profiler::Log::Tag::COROUTINE_DEBUGGER,
                     "JVMTI SetEventCallbacks error");
    return -1;
  }

  profiler::SetEventNotification(jvmti, JVMTI_ENABLE,
                                 JVMTI_EVENT_CLASS_FILE_LOAD_HOOK);

  return JNI_OK;
}
