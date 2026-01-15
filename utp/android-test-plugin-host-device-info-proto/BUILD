load("//tools/base/bazel:proto.bzl", "android_java_proto_library", "maven_proto_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")
load("//tools/base/utp:utp_proto.bzl", "UTP_PROTO_VERSION")

maven_proto_library(
    name = "android-test-plugin-host-device-info-proto",
    srcs = glob(["src/main/proto/*.proto"]),
    coordinates = "com.android.tools.utp:android-test-plugin-host-device-info-proto",
    description = "A protobuf message used in a UTP plugin to export additional Android device information",
    proto_java_runtime_library = ["//tools/base/utp:utp.protobuf-java"],
    protoc_version = UTP_PROTO_VERSION,
    resource_strip_prefix = "tools/base/utp/android-test-plugin-host-device-info-proto/src/main/proto",
    resources = glob(["src/main/proto/*.proto"]),
    version = BASE_VERSION,
    visibility = [
        "//tools/base:__pkg__",
        "//tools/base/build-system:__pkg__",
        "//tools/base/build-system/integration-test/utp:__pkg__",
        "//tools/base/firebase/testlab/testlab-gradle-plugin:__subpackages__",
        "//tools/base/gmaven:__pkg__",
        "//tools/base/utp:__subpackages__",
    ],
)

android_java_proto_library(
    name = "libstudio.android-test-plugin-host-device-info-proto",
    srcs = glob(["src/main/proto/*.proto"]),
    visibility = ["//visibility:public"],
)
