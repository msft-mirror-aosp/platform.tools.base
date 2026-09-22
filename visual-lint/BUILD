load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.visual-lint",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.visual-lint.iml"],
    lint_baseline = "lint_baseline.xml",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/base/layoutlib-api:studio.android.sdktools.layoutlib-api",
        "//tools/adt/idea/.idea/libraries:layoutlib",
        "//tools/base/common:studio.android.sdktools.common",
        "@intellij//:intellij-sdk",
        "//tools/base/sdklib:studio.android.sdktools.sdklib",
        "//tools/base/sdk-common:studio.android.sdktools.sdk-common",
    ],
)

kotlin_library(
    name = "tools.visual-lint",
    srcs = glob(["src/main/java/**/*.kt"]),
    lint_baseline = "lint_baseline.xml",
    visibility = ["//visibility:public"],
    deps = [
        "//tools/adt/idea/.idea/libraries:layoutlib",
        "//tools/base/common:tools.common",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)
