load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

kotlin_library(
    name = "cli",
    srcs = glob(["src/main/java/**/*.kt"]),
    # The standalone CLI bundles the files the host ships to the device.
    resources = ["//tools/base/ui-inspector/core:device_artifact_files"],
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

kotlin_test(
    name = "cli_tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    friends = [":cli"],
    jvm_flags = ["-Dtest.suite.jar=cli_tests.jar"],
    lint_baseline = "lint_baseline_tests.xml",
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":cli",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/adblib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/ui-inspector/core",
        "//tools/base/ui-inspector/core:core_tests.testlib",
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
