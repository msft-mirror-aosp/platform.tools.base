load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library", "cc_test")

package(default_visibility = ["//visibility:public"])

cc_library(
    name = "perfetto_cropper_lib",
    srcs = ["perfetto_cropper.cc"],
    hdrs = ["perfetto_cropper.h"],
    deps = [
        "@com_google_absl//absl/container:flat_hash_map",
        "@com_google_absl//absl/log",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/types:span",
        "@perfetto//:trace_processor",
    ],
)

cc_binary(
    name = "sherlock-perfetto-cropper",
    srcs = ["sherlock_perfetto_cropper_main.cc"],
    deps = [
        ":perfetto_cropper_lib",
        "//tools/base/profiler/native/sherlock/utils:mapped_file",
        "@com_google_absl//absl/flags:flag",
        "@com_google_absl//absl/flags:parse",
        "@com_google_absl//absl/log",
        "@com_google_absl//absl/log:initialize",
    ],
)

cc_test(
    name = "perfetto_cropper_test",
    size = "medium",
    srcs = [
        "perfetto_cropper_test.cc",
    ],
    data = [
        "//tools/base/profiler/native/sherlock:testdata/cropper-test-specifications.perfetto",
        "//tools/base/profiler/native/sherlock:testdata/cropper-test-trace.pftrace",
    ],
    linkstatic = 1,
    deps = [
        ":perfetto_cropper_lib",
        "@googletest//:gtest_main",
        "@perfetto//:trace_processor",
    ],
)
