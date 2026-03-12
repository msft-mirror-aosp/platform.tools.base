load("//tools/base/android-test:version.bzl", "ANDROID_TEST_ENGINE_VERSION")
load("//tools/base/bazel:proto.bzl", "android_java_proto_library", "maven_proto_library")

# Proto definitions for storing test results.

maven_proto_library(
    name = "test-result-proto",
    srcs = glob(["proto/**/*.proto"]),
    coordinates = "com.android.tools.androidtest:test-result-proto",
    description = "Proto definitions for storing test results.",
    notice = "LICENSE",
    resource_strip_prefix = "tools/base/android-test/test-result-proto/",
    resources = glob(["proto/**/*.proto"]),
    version = ANDROID_TEST_ENGINE_VERSION,
    visibility = [
        "//tools/base:__pkg__",
        "//tools/base/android-test/android-test-engine-result-listener:__pkg__",
        "//tools/base/android-test/android-test-junit-engine:__pkg__",
    ],
)

android_java_proto_library(
    name = "libstudio.test-result-proto",
    srcs = glob(["proto/**/*.proto"]),
    visibility = ["//visibility:public"],
)
