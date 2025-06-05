"""Presubmit related libraries."""

load("@rules_python//python:defs.bzl", "py_library", "py_test")

package(default_visibility = ["//tools/base/bazel/ci:__subpackages__"])

py_library(
    name = "bazel_diff",
    srcs = ["bazel_diff.py"],
)

py_library(
    name = "failure_retry",
    srcs = ["failure_retry.py"],
    deps = [
        ":gerrit",
        ":runs_per_test",
        "//tools/base/bazel/ci:bazel",
        "//tools/base/bazel/ci:gce",
    ],
)

py_test(
    name = "failure_retry_test",
    srcs = ["failure_retry_test.py"],
    deps = [
        ":failure_retry",
        ":gerrit",
        ":runs_per_test",
        "//tools/base/bazel/ci:fake_build_env",
        "//tools/base/bazel/ci:fake_gce",
        "//tools/base/bazel/ci:gce",
        "@absl-py//absl/testing:absltest",
        "@absl-py//absl/testing:parameterized",
    ],
)

py_library(
    name = "gerrit",
    srcs = ["gerrit.py"],
    deps = [
        "//tools/base/bazel/ci:bazel",
        "//tools/base/bazel/ci:gce",
    ],
)

py_test(
    name = "gerrit_test",
    srcs = ["gerrit_test.py"],
    deps = [
        ":gerrit",
        "//tools/base/bazel/ci:fake_build_env",
        "//tools/base/bazel/ci:fake_gce",
        "@absl-py//absl/testing:absltest",
    ],
)

py_library(
    name = "impacted_targets",
    srcs = ["impacted_targets.py"],
    deps = [
        ":bazel_diff",
        ":gerrit",
        "//tools/base/bazel/ci:bazel",
        "//tools/base/bazel/ci:gce",
    ],
)

py_test(
    name = "impacted_targets_test",
    srcs = ["impacted_targets_test.py"],
    deps = [
        ":bazel_diff",
        ":gerrit",
        ":impacted_targets",
        "//tools/base/bazel/ci:bazel",
        "//tools/base/bazel/ci:fake_build_env",
        "//tools/base/bazel/ci:fake_gce",
        "//tools/base/bazel/ci:gce",
        "@absl-py//absl/testing:absltest",
    ],
)

py_library(
    name = "presubmit",
    srcs = ["presubmit.py"],
    deps = [
        ":failure_retry",
        ":gerrit",
        ":impacted_targets",
        ":runs_per_test",
        "//tools/base/bazel/ci:bazel",
    ],
)

py_test(
    name = "presubmit_test",
    srcs = ["presubmit_test.py"],
    deps = [
        ":bazel_diff",
        ":gerrit",
        ":presubmit",
        "//tools/base/bazel/ci:bazel",
        "//tools/base/bazel/ci:fake_build_env",
        "//tools/base/bazel/ci:fake_gce",
        "//tools/base/bazel/ci:gce",
        "@absl-py//absl/testing:absltest",
        "@absl-py//absl/testing:parameterized",
    ],
)

py_library(
    name = "runs_per_test",
    srcs = ["runs_per_test.py"],
    deps = [
        ":gerrit",
        ":impacted_targets",
        "//tools/base/bazel/ci:bazel",
        "//tools/base/bazel/ci:gce",
    ],
)

py_test(
    name = "runs_per_test_test",
    srcs = ["runs_per_test_test.py"],
    deps = [
        ":gerrit",
        ":impacted_targets",
        ":runs_per_test",
        "//tools/base/bazel/ci:bazel",
        "//tools/base/bazel/ci:fake_build_env",
        "//tools/base/bazel/ci:fake_gce",
        "//tools/base/bazel/ci:gce",
        "@absl-py//absl/testing:absltest",
    ],
)
