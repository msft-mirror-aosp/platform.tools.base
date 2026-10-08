load("//tools/base/bazel:coverage.bzl", "coverage_java_test")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BUILD_VERSION")

maven_library(
    name = "tools.builder-test-api",
    srcs = glob([
        "src/main/java/com/android/builder/testing/api/*.java",
    ]),
    coordinates = "com.android.tools.build:builder-test-api",
    notice = "NOTICE",
    resource_strip_prefix = "tools/base/build-system/builder-test-api",
    version = BUILD_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/annotations",
        "//tools/base/common:tools.common",
        "//tools/base/ddmlib:tools.ddmlib",
        "@maven//:com.google.guava.guava",
    ],
)

coverage_java_test(
    name = "tools.builder-test-api_tests",
    srcs = glob(["src/test/java/**"]),
    jvm_flags = ["-Dtest.suite.jar=tools.builder-test-api_tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    runtime_deps = ["//tools/base/testutils:tools.testutils"],
    deps = [
        ":tools.builder-test-api",
        "@maven//:com.google.guava.guava",
        "@maven//:junit.junit",
    ],
)

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/java/com/android/builder/testing/api/*.java",
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "NOTICE",
        "build.gradle",
    ],
    visibility = [
        "//tools/base/build-system:__pkg__",
        "//tools/base/firebase/testlab/testlab-gradle-plugin:__pkg__",
        "//tools/base/preview/screenshot/screenshot-test-gradle-plugin:__pkg__",
    ],
)
