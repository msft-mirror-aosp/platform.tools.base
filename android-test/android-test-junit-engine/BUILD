load("//tools/base/bazel:maven.bzl", "maven_library")

maven_library(
    name = "android-test-test-engine",
    srcs = glob([
        "src/main/java/**/*.java",
        "src/main/java/**/*.kt",
    ]),
    coordinates = "com.android.tools.androidtest:test-engine",
    description = "A JUnit 5 TestEngine for running Android instrumentation tests.",
    notice = "LICENSE",
    resource_strip_prefix = "tools/base/androidtest/testengine/src/main/resources/",
    resources = glob(["src/main/resources/**"]),
    version = "0.1.0-dev",
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/common:tools.common",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.junit.platform.junit-platform-engine",
    ],
)
