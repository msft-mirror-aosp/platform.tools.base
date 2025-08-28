load("//tools/base/common:release_version.bzl", "IS_AGP_RELEASE_BRANCH")

GRADLE_PROPERTIES = select({
    "//tools/base/bazel:release": {
        "hybrid-build-embedded-in-bazel": "true",
        "release": IS_AGP_RELEASE_BRANCH,
    },
    "//conditions:default": {
        "hybrid-build-embedded-in-bazel": "true",
    },
})
