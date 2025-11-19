load("//tools/base/bazel:proto.bzl", "java_proto_library", "maven_proto_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")
load("//tools/base/utp:utp_proto.bzl", "UTP_PROTO_VERSION")

maven_proto_library(
    name = "android-test-plugin-host-emulator-control-proto",
    srcs = glob(["src/main/proto/*.proto"]),
    coordinates = "com.android.tools.utp:android-test-plugin-host-emulator-control-proto:" + BASE_VERSION,
    description = "A protobuf message used in UTP plugin to make the emulator gRPC endpoint .",
    java_exports = [
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
    notice = "NOTICE",
    proto_java_runtime_library = ["//tools/base/utp:utp.protobuf-java"],
    protoc_version = UTP_PROTO_VERSION,
    resource_strip_prefix = "tools/base/utp/android-test-plugin-host-emulator-control-proto/src/main/proto",
    resources = glob(["src/main/proto/*.proto"]),
    visibility = [
        "//tools/base:__pkg__",
        "//tools/base/build-system:__pkg__",
        "//tools/base/utp:__subpackages__",
    ],
)

java_proto_library(
    name = "studio.android-test-plugin-host-emulator-control-proto",
    srcs = glob(["src/main/proto/*.proto"]),
    proto_java_runtime_library = ["@maven//:com.google.protobuf.protobuf-java"],
    visibility = ["//visibility:public"],
)
