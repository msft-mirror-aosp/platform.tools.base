load("//tools/base/bazel:proto.bzl", "android_java_proto_library", "java_proto_library")

java_proto_library(
    name = "journeys-java-proto",
    srcs = glob(["src/main/proto/*.proto"]),
    visibility = ["//visibility:public"],
)

android_java_proto_library(
    name = "journeys-android-java-proto",
    srcs = glob(["src/main/proto/*.proto"]),
    visibility = ["//visibility:public"],
)
