"""
This module contains variables storing the next release version number of the journeys test plugin.
"""

load("//tools/base/journeys:release_version.bzl", "JOURNEYS_TEST_PLUGIN_VERSION_DEV", "JOURNEYS_TEST_PLUGIN_VERSION_RELEASE")

JOURNEYS_TEST_PLUGIN_VERSION = select({
    "//tools/base/bazel:release": JOURNEYS_TEST_PLUGIN_VERSION_RELEASE,
    "//conditions:default": JOURNEYS_TEST_PLUGIN_VERSION_DEV,
})

JOURNEYS_TEST_PLUGIN_GRADLE_PROPERTIES = select({
    "//tools/base/bazel:release": {
        "JOURNEYS_TEST_PLUGIN_VERSION": JOURNEYS_TEST_PLUGIN_VERSION_RELEASE,
    },
    "//conditions:default": {
        "JOURNEYS_TEST_PLUGIN_VERSION": JOURNEYS_TEST_PLUGIN_VERSION_DEV,
    },
})
