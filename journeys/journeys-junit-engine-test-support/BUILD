load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/journeys:version.bzl", "JOURNEYS_TEST_PLUGIN_VERSION")

maven_library(
    name = "journeys-junit-engine-test-support",
    srcs = glob([
        "src/main/java/**/*.java",
        "src/main/java/**/*.kt",
    ]),
    bundled_deps = [
        "//tools/vendor/robo/cloud/test/appcrawler/proto:crawler_service_java_grpc",
        "//tools/vendor/robo/proto:android_platform_java_proto",
    ],
    coordinates = "com.android.tools.journeys:journeys-junit-engine-test-support",
    module_name = "com.android.tools.journeys.testsupport",
    resource_strip_prefix = "tools/base/journeys/journeys-junit-engine-test-support/src/main/resources/",
    resources = glob(["src/main/resources/**"]),
    version = JOURNEYS_TEST_PLUGIN_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/journeys/journeys-junit-engine",
        "@maven//:com.google.guava.guava",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:io.grpc.grpc-core",
        "@maven//:io.grpc.grpc-inprocess",
        "@maven//:io.grpc.grpc-stub",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)
