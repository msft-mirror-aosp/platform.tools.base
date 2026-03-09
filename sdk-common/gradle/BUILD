load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.sdk-common.gradle",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.sdk-common.gradle.iml"],
    jvm_target = "8",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
    ],
)

kotlin_library(
    name = "tools.sdk-common.gradle.lib",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    module_name = "sdk-common.gradle",
    visibility = ["//visibility:public"],
    warn = "error",
    deps = [
        "@maven//:com.google.guava.guava",
    ],
)
