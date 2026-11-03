load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

kotlin_library(
    name = "tools.uicheck.validators",
    srcs = glob(["src/main/java/**/*.kt"]),
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/uicheck/common:tools.uicheck.common",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)

kotlin_test(
    name = "tools.uicheck.validators.tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    friends = [":tools.uicheck.validators"],
    jvm_flags = ["-Dtest.suite.jar=tools.uicheck.validators.tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":tools.uicheck.validators",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/uicheck/common:tools.uicheck.common",
        "@maven//:junit.junit",
    ],
)
