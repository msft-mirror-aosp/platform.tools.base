load("//tools/base/android-test:version.bzl", "ANDROID_TEST_ENGINE_VERSION")
load("//tools/base/bazel:kotlin.bzl", "kotlin_test")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/bazel:proto.bzl", "maven_proto_library")

maven_proto_library(
    name = "test_result_event_proto",
    srcs = glob(["proto/**/*.proto"]),
    coordinates = "com.android.tools.androidtest:test-result-event-proto",
    description = "A protobuf messages used to listen test results",
    notice = "LICENSE",
    resource_strip_prefix = "tools/base/android-test/android-test-engine-result-listener/proto",
    resources = glob(["proto/**/*.proto"]),
    version = ANDROID_TEST_ENGINE_VERSION,
    visibility = ["//visibility:public"],
)

maven_library(
    name = "android-test-engine-result-listener",
    srcs = glob([
        "src/main/java/**/*.java",
        "src/main/java/**/*.kt",
    ]),
    coordinates = "com.android.tools.androidtest:android-test-engine-result-listener",
    description = "A JUnit Platform TestExecutionListener that emits test results as base64 encoded protos.",
    notice = "LICENSE",
    resource_strip_prefix = "tools/base/android-test/android-test-engine-result-listener/src/main/resources/",
    resources = glob(["src/main/resources/**"]),
    version = ANDROID_TEST_ENGINE_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        ":test_result_event_proto",
        "//tools/base/android-test/test-result-proto",
        "//tools/base/common:tools.common",
        "@maven//:com.google.guava.guava",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
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
        ":android-test-engine-result-listener",
        ":test_result_event_proto",
        "//tools/base/android-test/test-result-proto",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.kotlin.kotlin-test",
        "@maven//:org.junit.platform.junit-platform-engine",
        "@maven//:org.junit.platform.junit-platform-launcher",
        "@maven//:org.mockito.kotlin.mockito-kotlin",
        "@maven//:org.mockito.mockito-core",
    ],
)
