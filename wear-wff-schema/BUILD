load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.intellij.android.wear-wff-schema",
    iml_files = ["intellij.android.wear-wff-schema.iml"],
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/common:studio.android.sdktools.common",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.intellij.android.wear-wff-schema.tests",
    iml_files = ["intellij.android.wear-wff-schema.tests.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = ["//tools/base/wear-wff-schema:studio.intellij.android.wear-wff-schema"],
)
