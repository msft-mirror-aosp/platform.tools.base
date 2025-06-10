load("//tools/base/bazel:gradle.bzl", "gradle_build")
load("//tools/base/bazel:maven.bzl", "maven_repository")
load("//tools/base/build-system:hybrid_build.bzl", "GRADLE_PROPERTIES")
load("//tools/base/journeys:version.bzl", "JOURNEYS_TEST_PLUGIN_GRADLE_PROPERTIES")

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

maven_repository(
    name = "maven_dependencies",
    # keep sorted
    artifacts = [
        "@maven//:io.grpc.grpc-inprocess_1.69.1",
    ],
)

# keep sorted
JOURNEYS_JUNIT_ENGINE_TEST_SUPPORT_SOURCE_FILES = [
    ":gradle_project_files",
    "//tools:agp_gradle_build_files",
    "//tools/base/common:agp_gradle_build_files",
    "//tools/base/journeys:release_version.gradle",
    "//tools/base/journeys/journeys-junit-engine:gradle_project_files",
    "//tools/buildSrc:agp_gradle_build_files",
    "//tools/buildSrc/base:agp_gradle_build_files",
]

gradle_build(
    name = "journeys-junit-engine-test-support",
    build_file = "//tools:agp_gradle_build_root_build_file",
    data = ["//prebuilts/studio/jdk/jdk11:jdk_runtime_files"] + JOURNEYS_JUNIT_ENGINE_TEST_SUPPORT_SOURCE_FILES,
    gradle_properties = GRADLE_PROPERTIES | JOURNEYS_TEST_PLUGIN_GRADLE_PROPERTIES,
    output_file = "journeys-junit-engine-test-support.zip",
    output_file_source = "dist/repo.zip",
    repos = [
        ":maven_dependencies",
        "//tools/base:agp_dependencies",
        "//tools/base/journeys/journeys-junit-engine:maven_test_dependencies",
    ],
    tasks = [":base:journeys:journeys-junit-engine-test-support:zipRepo"],
    visibility = ["//tools/base/journeys:__pkg__"],
)
