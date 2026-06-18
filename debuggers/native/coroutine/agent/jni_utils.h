#include "jni.h"

#include <memory>
#include <string>
#include <vector>

namespace jniutils {

// RAII wrapper for JNI local references to prevent memory leaks and local
// reference table overflows. It ensures DeleteLocalRef is called automatically
// when the reference goes out of scope, even if an exception is thrown or an
// early return occurs.
template <typename T>
class ScopedLocalRef {
 public:
  ScopedLocalRef(JNIEnv* env, T ref) : env_(env), ref_(ref) {}
  ~ScopedLocalRef() {
    if (ref_) {
      env_->DeleteLocalRef(ref_);
    }
  }
  T get() const { return ref_; }
  operator T() const { return ref_; }

 private:
  JNIEnv* env_;
  T ref_;
};

// RAII wrapper for JNI string characters to prevent memory leaks.
// It ensures ReleaseStringUTFChars is called automatically, which is critical
// because GetStringUTFChars allocates memory that the JVM does not garbage
// collect.
class ScopedUtfChars {
 public:
  ScopedUtfChars(JNIEnv* env, jstring s) : env_(env), jstr_(s) {
    utf_chars_ = (s != nullptr) ? env_->GetStringUTFChars(s, nullptr) : nullptr;
  }
  ~ScopedUtfChars() {
    if (utf_chars_) {
      env_->ReleaseStringUTFChars(jstr_, utf_chars_);
    }
  }
  const char* c_str() const { return utf_chars_; }

 private:
  JNIEnv* env_;
  jstring jstr_;
  const char* utf_chars_;
};

struct StackTrace {
  std::string msg;
  std::vector<std::string> frames;
  std::unique_ptr<StackTrace> cause;
};

/**
 * Gets the exception from JNI and clears it. Then constructs the stacktrace and
 * returns it as a jniutils::StackTrace.
 */
std::unique_ptr<StackTrace> getExceptionStackTrace(JNIEnv* jni);

/**
 * Prints the stack trace passed as argument into a string.
 */
std::string stackTraceToString(std::unique_ptr<StackTrace> stackTrace);
}  // namespace jniutils
