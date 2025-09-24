load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/journeys:version.bzl", "JOURNEYS_TEST_PLUGIN_VERSION")

maven_library(
    name = "journeys-junit-engine",
    srcs = glob([
        "src/main/java/**/*.java",
        "src/main/java/**/*.kt",
    ]),
    # keep sorted
    bundled_deps = [
        "//tools/base/journeys/journeys-protos:journeys-java-proto",
        "//tools/vendor/robo:crawler_apk",
        "//tools/vendor/robo/client:client",
        "//tools/vendor/robo/cloud/test/appcrawler/proto:crawler_service_java_grpc",
        "//tools/vendor/robo/cloud/test/appcrawler/proto:robo_config_java_proto",
        "//tools/vendor/robo/common:time_util",
        "//tools/vendor/robo/proto:android_platform_java_proto",
        "//tools/vendor/robo/proto:app_screen_style_java_proto",
        "//tools/vendor/robo/proto:crawl_outputs_java_proto",
        "//tools/vendor/robo/proto:guidance_java_proto",
        "//tools/vendor/robo/proto:robo_results_java_proto",
    ],
    coordinates = "com.android.tools.journeys:journeys-junit-engine",
    resource_strip_prefix = "tools/base/journeys/journeys-junit-engine/src/main/resources/",
    resources = glob(["src/main/resources/**"]),
    version = JOURNEYS_TEST_PLUGIN_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:com.github.rholder.guava-retrying",
        "@maven//:com.google.auth.google-auth-library-oauth2-http",
        "@maven//:com.google.flogger.flogger",
        "@maven//:com.google.flogger.flogger-system-backend",
        "@maven//:com.google.oauth-client.google-oauth-client",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:io.grpc.grpc-auth",
        "@maven//:io.grpc.grpc-core",
        "@maven//:io.grpc.grpc-netty",
        "@maven//:io.grpc.grpc-protobuf",
        "@maven//:io.grpc.grpc-stub",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.junit.platform.junit-platform-engine",
    ],
)
