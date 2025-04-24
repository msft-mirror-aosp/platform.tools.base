"""Presubmit related libraries."""

load("@rules_python//python:defs.bzl", "py_library", "py_test")

package(default_visibility = ["//tools/base/bazel/ci:__subpackages__"])

py_library(
    name = "bazel_diff",
    srcs = ["bazel_diff.py"],
)

py_library(
    name = "presubmit",
    srcs = ["presubmit.py"],
    deps = [
        ":bazel_diff",
        "//tools/base/bazel/ci:bazel",
        "//tools/base/bazel/ci:gce",
    ],
)

py_test(
    name = "presubmit_test",
    srcs = ["presubmit_test.py"],
    deps = [
        ":bazel_diff",
        ":presubmit",
        "//tools/base/bazel/ci:bazel",
        "//tools/base/bazel/ci:fake_build_env",
        "//tools/base/bazel/ci:fake_gce",
        "//tools/base/bazel/ci:gce",
        "@absl-py//absl/testing:absltest",
        "@absl-py//absl/testing:parameterized",
    ],
)
