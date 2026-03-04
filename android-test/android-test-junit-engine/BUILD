load("//tools/base/android-test:version.bzl", "ANDROID_TEST_ENGINE_VERSION")
load("//tools/base/bazel:kotlin.bzl", "kotlin_test")
load("//tools/base/bazel:maven.bzl", "maven_library")

maven_library(
    name = "android-test-engine",
    srcs = glob([
        "src/main/java/**/*.java",
        "src/main/java/**/*.kt",
    ]),
    coordinates = "com.android.tools.androidtest:android-test-engine",
    description = "A JUnit TestEngine for running Android instrumentation tests.",
    notice = "LICENSE",
    resource_strip_prefix = "tools/base/android-test/android-test-junit-engine/src/main/resources/",
    resources = glob(["src/main/resources/**"]),
    version = ANDROID_TEST_ENGINE_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/common:tools.common",
        # ddmlib is used for generating JUnit XML results via XmlTestRunListener.
        "//tools/base/ddmlib:tools.ddmlib",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
        "@maven//:org.junit.platform.junit-platform-engine",
    ],
)

kotlin_test(
    name = "tests",
    srcs = glob([
        "src/test/java/**/*.kt",
        "src/test/java/**/*.java",
    ]),
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    resource_strip_prefix = "tools/base/android-test/android-test-junit-engine/src/main/resources/",
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":android-test-engine",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.kotlin.kotlin-test",
        "@maven//:org.junit.platform.junit-platform-engine",
        "@maven//:org.mockito.kotlin.mockito-kotlin",
        "@maven//:org.mockito.mockito-core",
    ],
)
