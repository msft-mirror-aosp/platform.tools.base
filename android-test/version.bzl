"""
This module contains variables storing the next release version number of the android test engine.
"""

load("//tools/base/common:version.bzl", "IS_AGP_RELEASE_BRANCH")

# Note: When updating this version, also update `androidTestEngineVersion` in
# tools/base/build-system/gradle-core/src/main/java/com/android/build/gradle/internal/testing/AndroidTestEngineConfigurer.kt
ANDROID_TEST_ENGINE_VERSION = "0.1.0-alpha03" if IS_AGP_RELEASE_BRANCH == "true" else "0.1.0-dev"
