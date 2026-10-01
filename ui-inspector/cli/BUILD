load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.ui-inspector.cli",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.ui-inspector.cli.iml"],
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/adblib-tools:studio.android.sdktools.adblib.tools",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/base/ui-inspector/core:studio.android.sdktools.ui-inspector.core",
        "@maven//:info.picocli.picocli",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

kotlin_library(
    name = "cli",
    srcs = glob(["src/main/java/**/*.kt"]),
    deps = [
        "//tools/base/adblib",
        "//tools/base/adblib-tools:adblib.tools",
        "//tools/base/common:tools.common",
        "//tools/base/ui-inspector/core",
        "@maven//:info.picocli.picocli",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

java_binary(
    name = "dump-ui",
    main_class = "com.android.tools.ui.inspector.CliHostKt",
    visibility = ["//visibility:public"],
    runtime_deps = [":cli"],
)
