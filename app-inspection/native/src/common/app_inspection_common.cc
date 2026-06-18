#include "app_inspection_common.h"

namespace app_inspection {

const char* ARTIFACT_COORDINATE_CLASS =
    "com/android/tools/agent/app/inspection/version/ArtifactCoordinate";
const std::string ARTIFACT_COORDINATE_TYPE =
    "L" + std::string(ARTIFACT_COORDINATE_CLASS) + ";";
const char* LIBRARY_COMPATIBILITY_CLASS =
    "com/android/tools/agent/app/inspection/version/LibraryCompatibility";
const std::string LIBRARY_COMPATIBILITY_TYPE =
    "L" + std::string(LIBRARY_COMPATIBILITY_CLASS) + ";";

jobject CreateArtifactCoordinate(JNIEnv* env, jstring group_id,
                                 jstring artifact_id, jstring version) {
  jclass clazz = env->FindClass(ARTIFACT_COORDINATE_CLASS);
  // SECURITY: Check for pending exceptions to avoid native crash on subsequent
  // JNI calls.
  if (env->ExceptionCheck() || clazz == nullptr) return nullptr;
  jmethodID constructor = env->GetMethodID(
      clazz, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
  if (env->ExceptionCheck() || constructor == nullptr) {
    env->DeleteLocalRef(clazz);
    return nullptr;
  }
  jobject result =
      env->NewObject(clazz, constructor, group_id, artifact_id, version);
  env->DeleteLocalRef(clazz);
  return result;
}

jobject CreateLibraryCompatibility(JNIEnv* env, jobject artifact,
                                   jobjectArray expected_library_class_names) {
  jclass clazz = env->FindClass(LIBRARY_COMPATIBILITY_CLASS);
  if (env->ExceptionCheck() || clazz == nullptr) return nullptr;
  jmethodID constructor = env->GetMethodID(
      clazz, "<init>",
      ("(" + ARTIFACT_COORDINATE_TYPE + "[Ljava/lang/String;)V").c_str());
  if (env->ExceptionCheck() || constructor == nullptr) {
    env->DeleteLocalRef(clazz);
    return nullptr;
  }
  jobject result = env->NewObject(clazz, constructor, artifact,
                                  expected_library_class_names);
  env->DeleteLocalRef(clazz);
  return result;
}

}  // namespace app_inspection
