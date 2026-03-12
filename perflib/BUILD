load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.perflib",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.perflib.iml"],
    lint_baseline = "lint_baseline.xml",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_resources = ["src/test/resources"],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/ddmlib-studio:studio.android.sdktools.ddmlib-studio",
    ],
)
