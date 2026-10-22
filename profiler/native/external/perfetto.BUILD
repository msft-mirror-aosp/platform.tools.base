load("@//tools/base/bazel:proto.bzl", "cc_grpc_proto_library", "java_proto_library")
load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")

package(default_visibility = ["//visibility:public"])

java_proto_library(
    name = "perfetto_config_java_proto",
    srcs = ["protos/perfetto/config/perfetto_config.proto"],
)

# Studio only uses UiState and FrameTimelineEvent of the trace schema. It detects Perfetto traces by
# parsing their first packet against the descriptor set of the full schema, shipped as a resource.
java_proto_library(
    name = "java_proto",
    srcs = [
        "protos/perfetto/trace/android/frame_timeline_event.proto",
        "protos/perfetto/trace/ui_state.proto",
    ],
    resources = [":perfetto_trace_proto"],
)

# PerfettoParser loads the descriptor set by its file name, which is derived from this target's name.
proto_library(
    name = "perfetto_trace_proto",
    srcs = ["protos/perfetto/trace/perfetto_trace.proto"],
)

cc_grpc_proto_library(
    name = "cc_proto",
    srcs = ["protos/perfetto/config/perfetto_config.proto"],
    target_compatible_with = select({
        "@platforms//os:windows": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
)
