load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.intellij.android.wizardTemplate.plugin",
    srcs = ["src"],
    iml_files = ["intellij.android.wizardTemplate.plugin.iml"],
    lint_baseline = "lint_baseline.xml",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "@intellij//:com.intellij.gradle",
        "//tools/base/sdk-common:studio.android.sdktools.sdk-common",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.intellij.android.wizardTemplate.plugin.tests",
    iml_files = ["intellij.android.wizardTemplate.plugin.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/wizard/template-plugin:studio.intellij.android.wizardTemplate.plugin",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_friends = ["//tools/base/wizard/template-plugin:studio.intellij.android.wizardTemplate.plugin"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
