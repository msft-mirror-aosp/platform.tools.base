load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.ui-inspector",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.ui-inspector.iml"],
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
    ],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/common:studio.android.sdktools.common",
        "@intellij//:com.intellij.java",
        "@intellij//:intellij-sdk",
        "@maven//:info.picocli.picocli",
    ],
)
