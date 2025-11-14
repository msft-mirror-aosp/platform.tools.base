load("//tools/base/bazel:proto.bzl", "maven_proto_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")
load("//tools/base/utp:utp_proto.bzl", "UTP_PROTO_VERSION")

maven_proto_library(
    name = "android-test-plugin-host-apk-installer-proto",
    srcs = glob(["src/main/proto/*.proto"]),
    coordinates = "com.android.tools.utp:android-test-plugin-host-apk-installer-proto",
    description = "A protobuf messages used in a UTP plugin to install APKs",
    notice = "NOTICE",
    protoc_version = UTP_PROTO_VERSION,
    resource_strip_prefix = "tools/base/utp/android-test-plugin-host-apk-installer-proto/src/main/proto",
    resources = glob(["src/main/proto/*.proto"]),
    version = BASE_VERSION,
    visibility = [
        "//tools/base:__pkg__",
        "//tools/base/build-system:__pkg__",
        "//tools/base/utp:__subpackages__",
    ],
)
