load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

kotlin_library(
    name = "tools.uicheck.config",
    srcs = glob(["src/main/java/**/*.kt"]),
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/standalone-render/lib:tools.standalone-render.lib",
        "//tools/base/uicheck/common:tools.uicheck.common",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)

kotlin_test(
    name = "tools.uicheck.config.tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    friends = [":tools.uicheck.config"],
    jvm_flags = ["-Dtest.suite.jar=tools.uicheck.config.tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":tools.uicheck.config",
        "//tools/base/standalone-render/lib:tools.standalone-render.lib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/uicheck/common:tools.uicheck.common",
        "@maven//:junit.junit",
    ],
)
