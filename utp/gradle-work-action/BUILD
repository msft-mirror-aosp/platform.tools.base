load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/**/*.java",
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "NOTICE",
    ],
    visibility = ["//tools/base/utp:__pkg__"],
)

maven_library(
    name = "gradle-work-action",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    # gradle-api_neverlink is added to bundled_deps because it doesn't provide MavenInfo
    # (required by deps_compile_only). The maven_library macro detects the _neverlink suffix
    # and treats it as a compile-only dependency without actually bundling it.
    bundled_deps = [
        "//tools/base/build-system:gradle-api_neverlink",
    ],
    coordinates = "com.android.tools.utp:gradle-work-action",
    deps_compile_only = [
        "//tools/base/utp/gradle-work-action-api",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
    ],
    description = "Provides Gradle WorkAction implementations for executing the Unified Test Platform (UTP) in isolation.",
    module_name = "gradle-work-action",
    notice = "NOTICE",
    resource_strip_prefix = "tools/base/utp/gradle-work-action/src/main/resources",
    resources = glob(["src/main/resources/**"]),
    version = BASE_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/android-test/android-test-engine-result-listener",
        "//tools/base/android-test/android-test-junit-engine:android-test-engine",
        "//tools/base/android-test/test-result-proto",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:org.junit.platform.junit-platform-engine",
        "@maven//:org.junit.platform.junit-platform-launcher",
    ],
)

kotlin_test(
    name = "tests",
    srcs = glob([
        "src/test/java/**/*.kt",
        "src/test/java/**/*.java",
    ]),
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":gradle-work-action",
        "//tools/base/android-test/android-test-engine-result-listener",
        "//tools/base/android-test/test-result-proto",
        "//tools/base/build-system:gradle-api",
        "//tools/base/build-system/builder",
        "//tools/base/build-system/builder-model",
        "//tools/base/build-system/builder-test-api:tools.builder-test-api",
        "//tools/base/build-system/gradle-api",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/utp/gradle-work-action-api",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.apache.groovy.groovy-all",
        "@maven//:org.mockito.kotlin.mockito-kotlin",
        "@maven//:org.mockito.mockito-core",
    ],
)
