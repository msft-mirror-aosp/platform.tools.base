load("//tools/base/bazel:maven.bzl", "maven_repository")
load("//tools/base/bazel:utils.bzl", "merged_zip")
load("//tools/base/journeys:release_version.bzl", "JOURNEYS_TEST_PLUGIN_VERSION_RELEASE")

merged_zip(
    name = "journeys_maven_repo",
    srcs = [
        "//tools/base/journeys/journeys-gradle-plugin:journeys-gradle-plugin.zip",
        "//tools/base/journeys/journeys-junit-engine:journeys-junit-engine.zip",
    ],
    visibility = ["//visibility:public"],
)

# This includes transitive maven dependencies.
# Use :journeys_maven_repo for release.
merged_zip(
    name = "journeys_maven_repo_for_integration_test",
    srcs = [
        ":journeys_maven_repo.zip",
        ":maven_dependencies.zip",
    ],
    visibility = [
        "//tools/base/build-system/integration-test:__subpackages__",
    ],
)

maven_repository(
    name = "maven_dependencies",
    # keep sorted
    artifacts = [
        "@maven//:com.google.android.appcrawler.appcrawler-app_" + JOURNEYS_TEST_PLUGIN_VERSION_RELEASE,
        "@maven//:com.google.android.appcrawler.appcrawler-platform_" + JOURNEYS_TEST_PLUGIN_VERSION_RELEASE,
        "@maven//:com.google.auth.google-auth-library-oauth2-http_1.24.0",
        "@maven//:com.google.guava.guava_33.2.1-jre",
        "@maven//:com.google.oauth-client.google-oauth-client_1.34.1",
    ],
)

exports_files(["release_version.gradle"])
