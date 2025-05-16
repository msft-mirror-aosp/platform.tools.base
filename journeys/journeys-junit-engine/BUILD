load("//tools/base/bazel:gradle.bzl", "gradle_build", "gradle_test")
load("//tools/base/bazel:maven.bzl", "maven_repository")
load("//tools/base/build-system:hybrid_build.bzl", "GRADLE_PROPERTIES")
load("//tools/base/journeys:release_version.bzl", "JOURNEYS_TEST_PLUGIN_VERSION_RELEASE")
load("//tools/base/journeys:version.bzl", "JOURNEYS_TEST_PLUGIN_GRADLE_PROPERTIES")

# A file group that contains gradle build file and source files to build journeys gradle
# plugin using Gradle inside Bazel's sandbox.
filegroup(
    name = "gradle_project_files",
    # buildifier: disable=globs
    srcs = glob([
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "build.gradle",
    ],
    visibility = ["//visibility:private"],
)

filegroup(
    name = "gradle_test_files",
    srcs = glob([
        "src/test/**/*.kt",
    ]) + [
        "build.gradle",
    ],
    visibility = ["//visibility:private"],
)

maven_repository(
    name = "maven_dependencies",
    # keep sorted
    artifacts = [
        "@maven//:com.google.android.appcrawler.appcrawler-platform_" + JOURNEYS_TEST_PLUGIN_VERSION_RELEASE,
        "@maven//:com.google.auth.google-auth-library-oauth2-http_1.24.0",
        "@maven//:com.google.guava.guava_33.2.1-jre",
        "@maven//:com.google.oauth-client.google-oauth-client_1.34.1",
    ],
)

maven_repository(
    name = "maven_test_dependencies",
    # keep sorted
    artifacts = [
        "@maven//:com.google.android.appcrawler.appcrawler-platform_" + JOURNEYS_TEST_PLUGIN_VERSION_RELEASE,
        "@maven//:com.google.auth.google-auth-library-oauth2-http_1.24.0",
        "@maven//:com.google.guava.guava_33.2.1-jre",
        "@maven//:com.google.oauth-client.google-oauth-client_1.34.1",
    ],
    visibility = [
        "//tools/base/build-system/integration-test:__subpackages__",
    ],
)

# keep sorted
JOURNEYS_JUNIT_ENGINE_SOURCE_FILES = [
    ":gradle_project_files",
    "//tools:agp_gradle_build_files",
    "//tools/base/common:agp_gradle_build_files",
    "//tools/base/journeys:release_version.gradle",
    "//tools/buildSrc:agp_gradle_build_files",
    "//tools/buildSrc/base:agp_gradle_build_files",
]

gradle_build(
    name = "journeys-junit-engine",
    build_file = "//tools:agp_gradle_build_root_build_file",
    data = ["//prebuilts/studio/jdk/jdk11:jdk_runtime_files"] + JOURNEYS_JUNIT_ENGINE_SOURCE_FILES,
    gradle_properties = GRADLE_PROPERTIES | JOURNEYS_TEST_PLUGIN_GRADLE_PROPERTIES,
    output_file = "journeys-junit-engine.zip",
    output_file_source = "dist/repo.zip",
    repos = [
        ":maven_dependencies",
        "//tools/base:agp_dependencies",
    ],
    tasks = [":base:journeys:journeys-junit-engine:zipRepo"],
    visibility = ["//visibility:public"],
)

gradle_test(
    name = "journeys-junit-engine_test",
    build_file = "//tools:agp_gradle_build_root_build_file",
    data = [
        ":gradle_test_files",
        "//prebuilts/studio/jdk/jdk11:jdk_runtime_files",
    ] + JOURNEYS_JUNIT_ENGINE_SOURCE_FILES,
    gradle_properties = GRADLE_PROPERTIES | JOURNEYS_TEST_PLUGIN_GRADLE_PROPERTIES,
    repos = [
        ":maven_test_dependencies",
        "//tools/base:agp_dependencies",
    ],
    tasks = [":base:journeys:journeys-junit-engine:test"],
    test_output_dir = "build/base/journeys/journeys-junit-engine/build/test-results/test",
    visibility = ["//visibility:public"],
)
