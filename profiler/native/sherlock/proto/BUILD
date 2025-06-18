load("//tools/base/bazel:proto.bzl", "cc_grpc_proto_library")

package(default_visibility = ["//visibility:public"])

cc_grpc_proto_library(
    name = "service_proto",
    srcs = [
        "perfetto.proto",
        "service.proto",
    ],
    grpc_support = True,
    include_prefix = "proto",
)
