"""
This module contains variables storing the next release version number of the android test engine.
"""

load(
    "//tools/base/android-test:release_version.bzl",
    "ANDROID_TEST_ENGINE_VERSION_DEV",
    "ANDROID_TEST_ENGINE_VERSION_RELEASE",
)

ANDROID_TEST_ENGINE_VERSION = select({
    "//tools/base/bazel:release": ANDROID_TEST_ENGINE_VERSION_RELEASE,
    "//conditions:default": ANDROID_TEST_ENGINE_VERSION_DEV,
})
