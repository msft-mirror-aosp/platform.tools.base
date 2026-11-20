load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")
load("//tools/base/bazel:utils.bzl", "fileset")

fileset(
    name = "resources",
    srcs = glob([
        "src/main/resources/**",
    ]),
    mappings = {
        "src/main/resources/": "",
    },
)

kotlin_library(
    name = "gradle-settings",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    resources = [":resources"],
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/build-system:gradle-api_neverlink",
        "//tools/base/build-system/gradle-settings-api",
    ],
)

kotlin_test(
    name = "tests",
    srcs = glob(["src/test/kotlin/**/*.kt"]),
    add_opens = ["java.base/java.lang"],
    friends = [":gradle-settings"],
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":gradle-settings",
        "//tools/base/build-system:gradle-api",
        "//tools/base/build-system/gradle-settings-api",
        "//tools/base/common:tools.common",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.apache.groovy.groovy-all",
        "@maven//:org.mockito.mockito-core",
    ],
)

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/**/*.java",
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "NOTICE",
        "build.gradle",
    ],
    visibility = ["//tools/base/build-system:__pkg__"],
)
