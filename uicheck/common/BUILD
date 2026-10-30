load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

kotlin_library(
    name = "tools.uicheck.common",
    srcs = glob(["src/main/java/**/*.kt"]),
    visibility = ["//visibility:public"],
    exports = [
        "//tools/base/visual-lint:visual-lint-core",
    ],
    deps = [
        "//tools/base/visual-lint:visual-lint-core",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)

kotlin_test(
    name = "tools.uicheck.common.tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    friends = [":tools.uicheck.common"],
    jvm_flags = ["-Dtest.suite.jar=tools.uicheck.common.tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":tools.uicheck.common",
        "//tools/base/testutils:tools.testutils",
        "@maven//:junit.junit",
    ],
)
