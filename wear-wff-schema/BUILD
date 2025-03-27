load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.intellij.android.wear-wff-schema",
    srcs = ["src"],
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
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:kotlin-test",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "@intellij//:test-framework",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework",
    ],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/base/wear-wff-schema:studio.intellij.android.wear-wff-schema",
        "//tools/base/common:studio.android.sdktools.common",
        "@intellij//:intellij-sdk",
    ],
)
