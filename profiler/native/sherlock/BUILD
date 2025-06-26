load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library")

package(default_visibility = ["//visibility:public"])

cc_binary(
    name = "sherlock_trace_processor",
    srcs = [
        "sherlock_trace_processor.cc",
    ],
    deps = [
        ":processor",
        "//tools/base/profiler/native/sherlock/proto:service_proto",
        "@com_google_absl//absl/flags:flag",
        "@com_google_absl//absl/flags:parse",
        "@com_google_absl//absl/time",
        "@perfetto//:libpprofbuilder",
        "@perfetto//:trace_processor",
    ],
)

cc_library(
    name = "processor",
    srcs = ["processor.cc"],
    hdrs = ["processor.h"],
    deps = [
        "//tools/base/profiler/native/sherlock/proto:service_proto",
        "@perfetto//:trace_processor",
    ],
)
