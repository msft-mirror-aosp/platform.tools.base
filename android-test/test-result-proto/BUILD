load("//tools/base/bazel:proto.bzl", "android_java_proto_library", "maven_proto_library")
load("//tools/base/common:version.bzl", "BUILD_VERSION")

# Proto definitions for storing test results.

maven_proto_library(
    name = "test-result-proto",
    srcs = glob(["proto/**/*.proto"]),
    coordinates = "com.android.tools.androidtest:test-result-proto",
    description = "Proto definitions for storing test results.",
    notice = "NOTICE",
    resource_strip_prefix = "tools/base/android-test/test-result-proto/",
    resources = glob(["proto/**/*.proto"]),
    version = BUILD_VERSION,
    visibility = [
        "//tools/base:__pkg__",
        "//tools/base/android-test:__subpackages__",
        "//tools/base/build-system/integration-test/connected:__pkg__",
        "//tools/base/build-system/integration-test/utp:__pkg__",
        "//tools/base/firebase/testlab/testlab-gradle-plugin:__pkg__",
        "//tools/base/gmaven:__pkg__",
        "//tools/base/utp:__subpackages__",
    ],
)

android_java_proto_library(
    name = "libstudio.test-result-proto",
    srcs = glob(["proto/**/*.proto"]),
    visibility = ["//visibility:public"],
)
