load("//tools/base/bazel:kotlin.bzl", "kotlin_test")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BUILD_VERSION")

maven_library(
    name = "standalone-tools",
    srcs = glob([
        "src/main/java/**/*.java",
    ]),
    coordinates = "com.android.tools.build:standalone-tools",
    description = "Android build tools that can be used without the Android Gradle Plugin.",
    notice = "NOTICE",
    version = BUILD_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:org.ow2.asm.asm",
        "@maven//:org.ow2.asm.asm-tree",
    ],
)

kotlin_test(
    name = "tests",
    srcs = glob([
        "src/test/java/**/*.java",
    ]),
    data = ["//tools/base/build-system:android_platform_for_agp_unit_tests"],
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":standalone-tools",
        "//tools/base/common:tools.common",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
    ],
)
