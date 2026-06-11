load("//tools/base/bazel:proto.bzl", "android_java_proto_library", "java_proto_library")

package(default_visibility = ["//visibility:public"])

android_java_proto_library(
    name = "adb_proxy_studio_proto",
    srcs = glob(["*.proto"]),
    grpc_support = True,
)

java_proto_library(
    name = "adb_proxy_java_proto",
    srcs = glob(["*.proto"]),
    grpc_support = True,
)
