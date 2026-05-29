load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.diff",
    srcs = ["src/main/kotlin"],
    iml_files = ["android.sdktools.diff.iml"],
    jvm_target = "8",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:kotlin-test",
    ],
    test_srcs = ["src/test/kotlin"],
    visibility = ["//visibility:public"],
    deps = ["@intellij//:intellij-sdk"],
)

# A standard Kotlin library compilation target compiled under JVM 8 (1.8).
# This exists so that downstream public Maven libraries (like tools.sdk-common)
# which are restricted to JVM 8 packaging rules can cleanly bundle the diff core
# classes inside their final distributed jar without target mismatches, circular
# dependencies, or the need to register new public Maven coordinates.
#
# Suffix kotlinc_opts overrides the compiler macro default version conflict.
kotlin_library(
    name = "diff",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    jvm_target = "8",
    kotlinc_opts = [
        "-jvm-target",
        "1.8",
    ],
    visibility = ["//visibility:public"],
)
