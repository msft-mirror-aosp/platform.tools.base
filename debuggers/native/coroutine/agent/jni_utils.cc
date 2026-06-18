#include "jni_utils.h"

namespace jniutils {
static std::unique_ptr<StackTrace> retrieveStackTrace(
    JNIEnv* jni, jthrowable exception, jmethodID throwable_getCause,
    jmethodID throwable_getStackTrace, jmethodID throwable_getMessage,
    jmethodID frame_toString) {
  if (exception == nullptr) {
    return nullptr;
  }
  std::unique_ptr<StackTrace> stackTrace(new StackTrace());

  // Use RAII wrappers (ScopedLocalRef/ScopedUtfChars) to automatically manage
  // JNI lifetimes. This ensures resources are freed (via DeleteLocalRef,
  // ReleaseStringUTFChars) even on early returns or if an exception is pending,
  // preventing memory leaks and JNI table crashes.

  // get the array of StackTraceElements
  ScopedLocalRef<jobjectArray> frames(
      jni,
      (jobjectArray)jni->CallObjectMethod(exception, throwable_getStackTrace));

  if (frames.get() == nullptr) {
    return nullptr;
  }

  jsize frames_length = jni->GetArrayLength(frames);

  // add Throwable.getMessage() before descending stack trace messages
  ScopedLocalRef<jstring> msg_obj(
      jni, (jstring)jni->CallObjectMethod(exception, throwable_getMessage));

  if (msg_obj.get() != nullptr) {
    ScopedUtfChars msg_utf(jni, msg_obj);
    if (msg_utf.c_str() != nullptr) {
      stackTrace->msg = msg_utf.c_str();
    }
  }

  for (jsize i = 0; i < frames_length; i++) {
    // Get the string returned from the 'toString()'
    // method of the next frame and append it to
    // the error message.
    ScopedLocalRef<jobject> frame(jni, jni->GetObjectArrayElement(frames, i));
    if (frame.get() == nullptr) continue;

    ScopedLocalRef<jstring> frame_str(
        jni, (jstring)jni->CallObjectMethod(frame, frame_toString));
    if (frame_str.get() == nullptr) continue;

    ScopedUtfChars frame_str_utf(jni, frame_str);
    if (frame_str_utf.c_str() != nullptr) {
      stackTrace->frames.emplace_back(frame_str_utf.c_str());
    }
  }

  // if 'exception' has a cause then append the stack trace messages from the
  // cause
  ScopedLocalRef<jthrowable> cause(
      jni, (jthrowable)jni->CallObjectMethod(exception, throwable_getCause));
  if (cause.get() != nullptr) {
    stackTrace->cause = retrieveStackTrace(
        jni, cause, throwable_getCause, throwable_getStackTrace,
        throwable_getMessage, frame_toString);
  }

  return stackTrace;
}

std::unique_ptr<StackTrace> getExceptionStackTrace(JNIEnv* jni) {
  // get the exception and clear, as no JNI calls can be made while an exception
  // exists
  jthrowable exception = jni->ExceptionOccurred();
  jni->ExceptionClear();

  static jclass throwable_class = jni->FindClass("java/lang/Throwable");
  static jmethodID throwable_getCause =
      jni->GetMethodID(throwable_class, "getCause", "()Ljava/lang/Throwable;");
  static jmethodID throwable_getStackTrace = jni->GetMethodID(
      throwable_class, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
  static jmethodID throwable_getMessage =
      jni->GetMethodID(throwable_class, "getMessage", "()Ljava/lang/String;");

  static jclass frame_class = jni->FindClass("java/lang/StackTraceElement");
  static jmethodID frame_toString =
      jni->GetMethodID(frame_class, "toString", "()Ljava/lang/String;");

  std::unique_ptr<StackTrace> stackTrace = retrieveStackTrace(
      jni, exception, throwable_getCause, throwable_getStackTrace,
      throwable_getMessage, frame_toString);

  return stackTrace;
}

std::string stackTraceToString(std::unique_ptr<StackTrace> stackTrace) {
  std::string stringStackTrace;
  stringStackTrace += "Exception: " + stackTrace->msg + "\n";

  for (auto const& frame : stackTrace->frames) {
    stringStackTrace += "   at " + frame;
    stringStackTrace += "\n";
  }

  if (stackTrace->cause != nullptr) {
    stringStackTrace += "Caused by:\n";
    stringStackTrace += stackTraceToString(move(stackTrace->cause));
  }

  return stringStackTrace;
}
}  // namespace jniutils
