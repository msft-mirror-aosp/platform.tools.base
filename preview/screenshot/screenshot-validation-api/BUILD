load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/preview/screenshot:version.bzl", "SCREENSHOT_TEST_PLUGIN_VERSION")

maven_library(
    name = "screenshot-validation-api",
    srcs = glob([
        "src/main/java/**/*.java",
        "src/main/java/**/*.kt",
    ]),
    coordinates = "com.android.tools.screenshot:screenshot-validation-api",
    version = SCREENSHOT_TEST_PLUGIN_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.junit.platform.junit-platform-commons",
    ],
)
