"""
This module contains variables storing the next release version number of the coverage agent.
"""

load(
    "//tools/base/android-test:release_version.bzl",
    _COVERAGE_AGENT_VERSION_DEV = "COVERAGE_AGENT_VERSION_DEV",
    _COVERAGE_AGENT_VERSION_RELEASE = "COVERAGE_AGENT_VERSION_RELEASE",
)
load("//tools/base/common:release_version.bzl", "IS_AGP_RELEASE_BRANCH")

DEV_COVERAGE_AGENT_VERSION = _COVERAGE_AGENT_VERSION_DEV
RELEASE_COVERAGE_AGENT_VERSION = (
    _COVERAGE_AGENT_VERSION_RELEASE if IS_AGP_RELEASE_BRANCH == "true" else DEV_COVERAGE_AGENT_VERSION
)

COVERAGE_AGENT_VERSION = select({
    "//tools/base/bazel:release": RELEASE_COVERAGE_AGENT_VERSION,
    "//conditions:default": DEV_COVERAGE_AGENT_VERSION,
})
