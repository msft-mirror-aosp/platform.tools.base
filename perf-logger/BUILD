load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.perf-logger",
    iml_files = ["perf-logger.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "//tools/base/common:studio.android.sdktools.common",
    ],
    test_srcs = [
        "src/main/java",
        "src/test/java",
    ],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = ["@intellij//:intellij-sdk"],
)
