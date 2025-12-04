"""Continuous Integration scripts."""

load("@bazel_skylib//rules:common_settings.bzl", "bool_flag", "string_flag")
load("@rules_python//python:defs.bzl", "py_binary", "py_library", "py_test")

package(default_visibility = ["//tools/base/bazel/ci:__subpackages__"])

string_flag(
    name = "ab_target",
    build_setting_default = "",
    visibility = ["//tools/base/bazel/ci:__subpackages__"],
)

bool_flag(
    name = "is_ci",
    build_setting_default = False,
    visibility = ["//tools/base/bazel/ci:__subpackages__"],
)

py_library(
    name = "bazel",
    srcs = ["bazel.py"],
)

py_library(
    name = "fake_build_env",
    testonly = 1,
    srcs = ["fake_build_env.py"],
    deps = [":bazel"],
)

py_library(
    name = "fake_gce",
    testonly = 1,
    srcs = ["fake_gce.py"],
    deps = [
        ":fake_build_env",
        ":gce",
        "@absl-py//absl/testing:absltest",
    ],
)

py_library(
    name = "gce",
    srcs = ["gce.py"],
)

py_library(
    name = "errors",
    srcs = ["errors.py"],
)

py_library(
    name = "owners_checks",
    srcs = ["owners_checks.py"],
    deps = [
        ":bazel",
        ":errors",
    ],
)

# This is a temporary target while we need owners_exempt.txt
# Once that list is empty we can remove this.
py_binary(
    name = "update_owners_exempt",
    srcs = ["owners_checks.py"],
    main = "owners_checks.py",
    deps = [
        ":bazel",
        ":errors",
    ],
)

py_library(
    name = "query_checks",
    srcs = ["query_checks.py"],
    deps = [
        ":bazel",
        ":errors",
    ],
)

py_library(
    name = "studio",
    srcs = ["studio.py"],
    deps = [
        ":bazel",
        ":errors",
    ],
)

py_library(
    name = "studio_linux",
    srcs = ["studio_linux.py"],
    deps = [
        ":bazel",
        ":studio",
        "//tools/base/bazel/ci/presubmit",
        "//tools/base/bazel/ci/presubmit:failure_retry",
        "//tools/base/bazel/ci/presubmit:impacted_targets",
    ],
)

py_library(
    name = "studio_nightly",
    srcs = ["studio_nightly.py"],
    deps = [
        ":bazel",
        ":studio",
    ],
)

py_library(
    name = "studio_win",
    srcs = ["studio_win.py"],
    deps = [
        ":bazel",
        ":studio",
        "//tools/base/bazel/ci/presubmit",
        "//tools/base/bazel/ci/presubmit:failure_retry",
        "//tools/base/bazel/ci/presubmit:impacted_targets",
    ],
)

py_library(
    name = "studio_mac",
    srcs = ["studio_mac.py"],
    deps = [
        ":bazel",
        ":studio",
        "//tools/base/bazel/ci/presubmit:impacted_targets",
    ],
)

py_library(
    name = "ci_lib",
    srcs = ["ci.py"],
    deps = [
        ":bazel",
        ":errors",
        ":owners_checks",
        ":query_checks",
        ":studio_linux",
        ":studio_mac",
        ":studio_nightly",
        ":studio_win",
    ],
)

py_binary(
    name = "ci",
    srcs = ["ci.py"],
    data = glob(["data/*"]) + [
        "//tools/base/bazel:bazel-diff",
        "//tools/vendor/adt_infra_internal/rbe/logscollector:logs-collector",
    ],
    env = {
        "BAZEL_DIFF_BINARY": "$(location //tools/base/bazel:bazel-diff)",
        "LOGS_COLLECTOR_BINARY": "$(location //tools/vendor/adt_infra_internal/rbe/logscollector:logs-collector)",
    },
    deps = [":ci_lib"],
)

py_test(
    name = "ci_test",
    srcs = ["ci_test.py"],
    deps = [
        ":ci_lib",
        "@absl-py//absl/testing:absltest",
    ],
)

py_test(
    name = "gce_test",
    srcs = ["gce_test.py"],
    deps = [
        ":fake_build_env",
        ":fake_gce",
        ":gce",
        "@absl-py//absl/testing:absltest",
    ],
)
