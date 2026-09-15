"""
This module contains variables storing the next release version number of the android test engine.
"""

load(
    "//tools/base/android-test:release_version.bzl",
    _ANDROID_TEST_ENGINE_VERSION_DEV = "ANDROID_TEST_ENGINE_VERSION_DEV",
    _ANDROID_TEST_ENGINE_VERSION_RELEASE = "ANDROID_TEST_ENGINE_VERSION_RELEASE",
)
load("//tools/base/common:release_version.bzl", "IS_AGP_RELEASE_BRANCH")

DEV_ANDROID_TEST_ENGINE_VERSION = _ANDROID_TEST_ENGINE_VERSION_DEV
RELEASE_ANDROID_TEST_ENGINE_VERSION = (
    _ANDROID_TEST_ENGINE_VERSION_RELEASE if IS_AGP_RELEASE_BRANCH == "true" else DEV_ANDROID_TEST_ENGINE_VERSION
)

ANDROID_TEST_ENGINE_VERSION = select({
    "//tools/base/bazel:release": RELEASE_ANDROID_TEST_ENGINE_VERSION,
    "//conditions:default": DEV_ANDROID_TEST_ENGINE_VERSION,
})
