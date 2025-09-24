load("//tools/base/bazel:maven.bzl", "maven_repository")
load("//tools/base/bazel:utils.bzl", "merged_zip")

merged_zip(
    name = "journeys_maven_repo",
    srcs = [
        ":maven_engine_no_transitive_deps.zip",
    ],
    visibility = ["//visibility:public"],
)

maven_repository(
    name = "maven_engine_no_transitive_deps",
    # keep sorted
    artifacts = [
        "//tools/base/journeys/journeys-junit-engine",
    ],
    include_transitive_deps = False,
)

maven_repository(
    name = "maven_test_dependencies",
    # keep sorted
    artifacts = [
        "//tools/base/journeys/journeys-junit-engine-test-support",
        "@maven//:com.google.http-client.google-http-client-bom_1.43.3",
        "@maven//:com.google.http-client.google-http-client-bom_1.45.0",
    ],
)

# This includes transitive maven dependencies.
# Use :journeys_maven_repo for release.
merged_zip(
    name = "journeys_maven_repo_for_integration_test",
    srcs = [
        ":maven_test_dependencies.zip",
        "//tools/base/journeys/journeys-gradle-plugin:journeys-gradle-plugin.zip",
    ],
    visibility = [
        "//tools/base/build-system/integration-test:__subpackages__",
    ],
)

exports_files(["release_version.gradle"])
