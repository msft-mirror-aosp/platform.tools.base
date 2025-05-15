load("//tools/base/bazel:gradle.bzl", "gradle_build", "gradle_test")
load("//tools/base/bazel:maven.bzl", "maven_repository")
load("//tools/base/build-system:hybrid_build.bzl", "GRADLE_PROPERTIES")
load("//tools/base/journeys:version.bzl", "JOURNEYS_TEST_PLUGIN_GRADLE_PROPERTIES")

# A file group that contains gradle build file and source files to build journeys gradle
# plugin using Gradle inside Bazel's sandbox.
filegroup(
    name = "gradle_project_files",
    srcs = glob(["src/main/**/*.kt"]) + ["build.gradle"],
    visibility = ["//visibility:private"],
)

filegroup(
    name = "gradle_test_files",
    srcs = glob([
        "src/test/**/*.kt",
        "src/test/resources/**",
    ]) + [
        "build.gradle",
    ],
    visibility = ["//visibility:private"],
)

maven_repository(
    name = "maven_dependencies",
    # keep sorted
    artifacts = [
        "@maven//:com.android.tools.build.builder-test-api_8.7.0",
        "@maven//:com.android.tools.build.gradle-api_8.7.0",
    ],
)

maven_repository(
    name = "maven_test_dependencies",
    # keep sorted
    artifacts = [
        "@maven//:com.android.tools.build.builder-test-api_8.7.0",
        "@maven//:com.android.tools.build.gradle-api_8.7.0",
        "@maven//:org.jetbrains.kotlin.kotlin-test-junit_1.7.10",
        "@maven//:org.mockito.kotlin.mockito-kotlin_5.4.0",
    ],
    visibility = [
        "//tools/base/build-system/integration-test:__subpackages__",
    ],
)

# keep sorted
JOURNEYS_GRADLE_PLUGIN_SOURCE_FILES = [
    ":gradle_project_files",
    "//tools:agp_gradle_build_files",
    "//tools/base/common:agp_gradle_build_files",
    "//tools/base/journeys:release_version.gradle",
    "//tools/buildSrc:agp_gradle_build_files",
    "//tools/buildSrc/base:agp_gradle_build_files",
]

gradle_build(
    name = "journeys-gradle-plugin",
    build_file = "//tools:agp_gradle_build_root_build_file",
    data = ["//prebuilts/studio/jdk/jdk11:jdk_runtime_files"] + JOURNEYS_GRADLE_PLUGIN_SOURCE_FILES,
    gradle_properties = GRADLE_PROPERTIES | JOURNEYS_TEST_PLUGIN_GRADLE_PROPERTIES,
    output_file = "journeys-gradle-plugin.zip",
    output_file_source = "dist/repo.zip",
    repos = [
        ":maven_dependencies",
        "//tools/base:agp_artifacts",
        "//tools/base:agp_dependencies",
    ],
    tasks = [":base:journeys:journeys-gradle-plugin:zipPlugin"],
    visibility = ["//visibility:public"],
)

gradle_test(
    name = "journeys-gradle-plugin_test",
    build_file = "//tools:agp_gradle_build_root_build_file",
    data = [
        ":gradle_test_files",
        "//prebuilts/studio/jdk/jdk11:jdk_runtime_files",
    ] + JOURNEYS_GRADLE_PLUGIN_SOURCE_FILES,
    gradle_properties = GRADLE_PROPERTIES | JOURNEYS_TEST_PLUGIN_GRADLE_PROPERTIES,
    repos = [
        ":maven_test_dependencies",
        "//tools/base:agp_artifacts",
        "//tools/base:agp_dependencies",
    ],
    tasks = [":base:journeys:journeys-gradle-plugin:test"],
    test_output_dir = "build/base/journeys/journeys-gradle-plugin/build/test-results/test",
    visibility = ["//visibility:public"],
)
