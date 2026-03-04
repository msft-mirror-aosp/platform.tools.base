"""
This module contains variables storing the next release version number of the android test engine.
"""

ANDROID_TEST_ENGINE_VERSION = select({
    "//tools/base/bazel:release": "0.1.0",
    "//conditions:default": "0.1.0-dev",
})
