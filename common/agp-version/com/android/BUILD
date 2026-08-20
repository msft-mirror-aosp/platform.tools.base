load(
    "//tools/base/android-test:release_version.bzl",
    "ANDROID_TEST_ENGINE_VERSION_DEV",
    "ANDROID_TEST_ENGINE_VERSION_RELEASE",
)
load("//tools/base/bazel:expand_template.bzl", "expand_template")
load(
    "//tools/base/common:release_version.bzl",
    "IS_AGP_RELEASE_BRANCH",
    "LAST_STABLE_BUILD_VERSION",
)
load(
    "//tools/base/common:version.bzl",
    "DEV_BASE_VERSION",
    "DEV_BUILD_VERSION",
    "DEV_COMMANDLINE_TOOLS_VERSION",
    "RELEASE_BASE_VERSION",
    "RELEASE_BUILD_VERSION",
    "RELEASE_COMMANDLINE_TOOLS_VERSION",
)

expand_template(
    name = "versionProperties",
    out = "version.properties",
    substitutions = select({
        "//tools/base/bazel:release": {
            "{ANDROID_TEST_ENGINE_VERSION}": ANDROID_TEST_ENGINE_VERSION_RELEASE,
            "{BASE_VERSION}": RELEASE_BASE_VERSION,
            "{BUILD_VERSION}": RELEASE_BUILD_VERSION,
            "{COMMANDLINE_TOOLS_VERSION}": RELEASE_COMMANDLINE_TOOLS_VERSION,
            "{IS_AGP_RELEASE_BRANCH}": IS_AGP_RELEASE_BRANCH,
            "{LAST_STABLE_BUILD_VERSION}": LAST_STABLE_BUILD_VERSION,
        },
        "//conditions:default": {
            "{ANDROID_TEST_ENGINE_VERSION}": ANDROID_TEST_ENGINE_VERSION_DEV,
            "{BASE_VERSION}": DEV_BASE_VERSION,
            "{BUILD_VERSION}": DEV_BUILD_VERSION,
            "{COMMANDLINE_TOOLS_VERSION}": DEV_COMMANDLINE_TOOLS_VERSION,
            "{IS_AGP_RELEASE_BRANCH}": IS_AGP_RELEASE_BRANCH,
            "{LAST_STABLE_BUILD_VERSION}": LAST_STABLE_BUILD_VERSION,
        },
    }),
    template = "version.properties.template",
    visibility = [
        "//tools/base/build-system/builder-model:__pkg__",
        "//tools/base/build-system/gradle-core:__pkg__",
        "//tools/base/common:__subpackages__",
        "//tools/data-binding:__pkg__",
    ],
)
