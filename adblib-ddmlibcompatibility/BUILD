load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.adblib.ddmlibcompatibility",
    srcs = ["src"],
    iml_files = ["android.sdktools.adblib.ddmlibcompatibility.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/adblib-tools:studio.android.sdktools.adblib.tools",
        "//tools/base/ddmlib-studio:studio.android.sdktools.ddmlib-studio",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.adblib.ddmlibcompatibility.tests",
    iml_files = ["android.sdktools.adblib.ddmlibcompatibility.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/base/adblib-ddmlibcompatibility:studio.android.sdktools.adblib.ddmlibcompatibility",
        "@intellij//:intellij-sdk",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/adblib-tools:studio.android.sdktools.adblib.tools",
        "//tools/base/ddmlib-studio:studio.android.sdktools.ddmlib-studio",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/base/fakeadbserver:studio.android.sdktools.fakeadbserver",
        "//tools/base/adblib:studio.android.sdktools.adblib.tests",
        "//tools/base/adblib-tools:studio.android.sdktools.adblib.tools.tests",
    ],
    test_friends = ["//tools/base/adblib-ddmlibcompatibility:studio.android.sdktools.adblib.ddmlibcompatibility"],
    test_srcs = ["test/src"],
    visibility = ["//visibility:public"],
)
