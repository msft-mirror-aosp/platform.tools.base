load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

package(default_visibility = ["//tools/base/ui-inspector:__subpackages__"])

kotlin_library(
    name = "common",
    srcs = glob(["src/main/java/**/*.kt"]),
    visibility = ["//visibility:public"],
)

kotlin_test(
    name = "common_tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    jvm_flags = ["-Dtest.suite.jar=common_tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":common",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/testutils:tools.testutils",
    ],
)
