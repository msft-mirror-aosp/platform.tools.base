load("//tools/base/bazel:gradle.bzl", "gradle_build")
load("//tools/base/build-system:hybrid_build.bzl", "GRADLE_PROPERTIES")

# A file group that contains gradle build file and source files to build journeys gradle
# plugin using Gradle inside Bazel's sandbox.
filegroup(
    name = "gradle_project_files",
    # buildifier: disable=globs
    srcs = glob(["src/main/**/*.kt"]) + ["build.gradle"],
    visibility = ["//visibility:private"],
)

gradle_build(
    name = "journeys-junit5-engine",
    build_file = "//tools:agp_gradle_build_root_build_file",
    data = [
        ":gradle_project_files",
        "//prebuilts/studio/jdk/jdk11:jdk_runtime_files",
        "//tools:agp_gradle_build_files",
        "//tools/base/common:agp_gradle_build_files",
        "//tools/buildSrc:agp_gradle_build_files",
        "//tools/buildSrc/base:agp_gradle_build_files",
    ],
    gradle_properties = GRADLE_PROPERTIES,
    output_file = "journeys-junit5-engine.zip",
    output_file_source = "dist/repo.zip",
    repos = [
        "//tools/base:agp_dependencies",
    ],
    tasks = [":base:journeys:journeys-junit5-engine:zipRepo"],
    visibility = [
        "//tools/base/build-system/integration-test:__subpackages__",
    ],
)
