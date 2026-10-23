load("//tools/base/bazel:proto.bzl", "android_java_proto_library", "cc_grpc_proto_library")
load("//tools/base/studio-leakcanary:protobuf_version.bzl", "STUDIO_LEAKCANARY_PROTOBUF_VERSION")

package(default_visibility = ["//visibility:public"])

android_java_proto_library(
    name = "transport_java_proto",
    srcs = glob(["**/*.proto"]),
    grpc_support = True,
    java_deps = [
        "@maven//:io.grpc.grpc-all",
        "@perfetto_repo//:perfetto_config_java_proto",
    ],
    proto_deps = [
        "@perfetto_repo//:perfetto_config_java_proto_srcs",
    ],
)

cc_grpc_proto_library(
    name = "cc_proto",
    srcs = glob(["**/*.proto"]),
    grpc_support = True,
    include_prefix = "proto",
    target_compatible_with = select({
        "@platforms//os:windows": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
    deps = ["@perfetto_repo//:cc_proto"],
)

# Makes small (lite) Java classes from leakcanary_data.proto for the studio-leakcanary library.
# The protoc version comes from studio-leakcanary/protobuf_version.bzl.
_LEAKCANARY_PROTOC = "//prebuilts/tools/common/m2:com.google.protobuf.protoc.%s_exe" % STUDIO_LEAKCANARY_PROTOBUF_VERSION

genrule(
    name = "leakcanary_data_java_lite_srcjar",
    srcs = ["leakcanary_data.proto"],
    outs = ["leakcanary_data_java_lite.srcjar"],
    # protoc can only write a .jar file, so write a .jar and rename it to .srcjar.
    cmd = "$(execpath %s) " % _LEAKCANARY_PROTOC +
          "--proto_path=\"$$(dirname $<)\" --java_out=lite:\"$@.jar\" \"$<\" " +
          "&& mv \"$@.jar\" \"$@\"",
    tools = [_LEAKCANARY_PROTOC],
    visibility = ["//tools/base/studio-leakcanary:__pkg__"],
)
