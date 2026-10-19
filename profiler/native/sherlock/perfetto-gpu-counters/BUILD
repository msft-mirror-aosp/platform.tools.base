load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library", "cc_test")

package(default_visibility = ["//visibility:public"])

cc_library(
    name = "perfetto_gpu_counters_lib",
    srcs = ["perfetto_gpu_counters.cc"],
    hdrs = ["perfetto_gpu_counters.h"],
    deps = [
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/types:span",
        "@perfetto//:trace_processor",
    ],
)

cc_binary(
    name = "sherlock-perfetto-gpu-counters",
    srcs = ["sherlock_perfetto_gpu_counters_main.cc"],
    deps = [
        ":perfetto_gpu_counters_lib",
        "//tools/base/profiler/native/sherlock/utils:mapped_file",
        "@com_google_absl//absl/flags:flag",
        "@com_google_absl//absl/flags:parse",
        "@com_google_absl//absl/log",
        "@com_google_absl//absl/log:initialize",
        "@com_google_absl//absl/status",
    ],
)

cc_test(
    name = "perfetto_gpu_counters_test",
    size = "medium",
    srcs = [
        "perfetto_gpu_counters_test.cc",
    ],
    data = [
        "//tools/base/profiler/native/sherlock:testdata/cropper-test-trace.pftrace",
    ],
    linkstatic = 1,
    deps = [
        ":perfetto_gpu_counters_lib",
        "@googletest//:gtest_main",
    ],
)
