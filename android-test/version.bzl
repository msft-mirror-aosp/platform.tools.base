"""
This module contains variables storing the next release version number of the android test engine.
"""

load("//tools/base/common:version.bzl", "IS_AGP_RELEASE_BRANCH")

ANDROID_TEST_ENGINE_VERSION = "0.1.0" if IS_AGP_RELEASE_BRANCH == "true" else "0.1.0-dev"
