"""
This module contains variables storing the next release version number of the journeys test plugin.
"""

load("//tools/base/journeys:release_version.bzl", "JOURNEYS_TEST_ENGINE_VERSION_DEV", "JOURNEYS_TEST_ENGINE_VERSION_RELEASE")

JOURNEYS_TEST_ENGINE_VERSION = select({
    "//tools/base/bazel:release": JOURNEYS_TEST_ENGINE_VERSION_RELEASE,
    "//conditions:default": JOURNEYS_TEST_ENGINE_VERSION_DEV,
})
