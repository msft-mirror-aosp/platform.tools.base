load("//tools/base/transport/test-framework:transport_test.bzl", "transport_test")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

# Profiler-specific additions to the transport test framework.
kotlin_library(
    name = "test-framework",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/bazel:studio-grpc",
        "//tools/base/bazel:studio-proto",
        "//tools/base/fakeandroid",
        "//tools/base/transport/proto:transport_java_proto",
        "//tools/base/transport/test-framework",
        "@maven//:com.google.truth.truth",
    ],
)
