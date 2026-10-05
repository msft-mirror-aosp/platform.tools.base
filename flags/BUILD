load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.flags",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.flags.iml"],
    lint_baseline = "lint_baseline.xml",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.flags.tests",
    iml_files = ["android.sdktools.flags.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:kotlin-test",
    ],
    test_friends = ["//tools/base/flags:studio.android.sdktools.flags"],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
)
