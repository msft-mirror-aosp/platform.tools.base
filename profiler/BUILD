load("//tools/base/bazel:proto.bzl", "cc_grpc_proto_library", "java_proto_library")
load("//tools/base/bazel:utils.bzl", "java_jarjar")

package(default_visibility = ["//visibility:public"])

# Perfetto protos are generated from the @perfetto module that also builds trace processor, see
# //tools/base/bazel/bzlmod:perfetto-export-protos.patch.

java_proto_library(
    name = "perfetto_config_java_proto",
    srcs = ["@perfetto//:protos/perfetto/config/perfetto_config.proto"],
)

# Studio only uses UiState of the trace schema. It detects Perfetto traces by parsing their first
# packet against the descriptor set of the full schema, shipped as a resource.
java_proto_library(
    name = "perfetto_trace_java_proto",
    srcs = ["@perfetto//:protos/perfetto/trace/ui_state.proto"],
    resource_strip_prefix = package_name(),
    resources = [":perfetto_trace_descriptor_set"],
)

# What proto_library would generate, but proto_library needs its sources in its own package.
# PerfettoParser loads the descriptor set by its file name.
genrule(
    name = "perfetto_trace_descriptor_set",
    srcs = ["@perfetto//:protos/perfetto/trace/perfetto_trace.proto"],
    outs = ["perfetto_trace_proto-descriptor-set.proto.bin"],
    cmd = "src=$<; $(execpath @com_google_protobuf//:protoc) --retain_options --descriptor_set_out=$@ " +
          "--proto_path=$${src%/protos/*} $$src",
    tools = ["@com_google_protobuf//:protoc"],
)

cc_grpc_proto_library(
    name = "perfetto_config_cc_proto",
    srcs = ["@perfetto//:protos/perfetto/config/perfetto_config.proto"],
    target_compatible_with = select({
        "@platforms//os:windows": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
)

java_jarjar(
    name = "perfetto-protos",
    srcs = [
        ":perfetto_config_java_proto",
        ":perfetto_trace_java_proto",
    ],
    rules = "//tools/base/bazel:jarjar_rules.txt",
)
