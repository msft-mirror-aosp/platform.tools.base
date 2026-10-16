load("@bazel_skylib//rules:diff_test.bzl", "diff_test")
load("//tools/base/bazel:gradle.bzl", "gradle_build", "gradle_test")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel:maven.bzl", "maven_repository")
load("//tools/base/build-system:hybrid_build.bzl", "GRADLE_PROPERTIES")
load("//tools/base/preview/screenshot:version.bzl", "SCREENSHOT_TEST_PLUGIN_GRADLE_PROPERTIES")

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "build.gradle",
    ],
    visibility = ["//visibility:private"],
)

filegroup(
    name = "agp_gradle_test_files",
    srcs = glob([
        "src/test/**/*.kt",
        "src/test/resources/**",
    ]) + [
        "build.gradle",
    ],
    visibility = ["//visibility:private"],
)

# keep sorted
SCREENSHOT_TEST_GRADLE_PLUGIN_SOURCE_FILES = [
    ":agp_gradle_build_files",
    "//tools:agp_gradle_build_files",
    "//tools/base/build-system/builder-model:agp_gradle_build_files",
    "//tools/base/build-system/builder-test-api:agp_gradle_build_files",
    "//tools/base/build-system/gradle-api:agp_gradle_build_files",
    "//tools/base/build-system/gradle-common-api:agp_gradle_build_files",
    "//tools/base/common:agp_gradle_build_files",
    "//tools/base/preview/screenshot:release_version.gradle",
    "//tools/buildSrc:agp_gradle_build_files",
    "//tools/buildSrc/base:agp_gradle_build_files",
]

maven_repository(
    name = "maven_test_dependencies",
    # keep sorted
    artifacts = [
        "//tools/base/multipreview-asm:compose-preview-detector",
        "//tools/base/standalone-render/compose-cli:compose-preview-renderer",
        "//tools/base/testutils:tools.testutils",
        "@maven//:org.jetbrains.kotlin.kotlin-test-junit_1.7.10",
        "@maven//:org.mockito.kotlin.mockito-kotlin_5.4.0",
    ],
    visibility = [
        "//tools/base/build-system/integration-test:__subpackages__",
    ],
)

maven_repository(
    name = "maven_dependencies",
    # keep sorted
    artifacts = [
        "//tools/base/multipreview-asm:compose-preview-detector",
    ],
)

gradle_build(
    name = "screenshot-test-gradle-plugin",
    build_file = "//tools:agp_gradle_build_root_build_file",
    data = SCREENSHOT_TEST_GRADLE_PLUGIN_SOURCE_FILES,
    gradle_properties = GRADLE_PROPERTIES | SCREENSHOT_TEST_PLUGIN_GRADLE_PROPERTIES,
    output_file = "screenshot-test-gradle-plugin.zip",
    output_file_source = "dist/repo.zip",
    problems_report_path = "build/build/root/reports/problems/problems-report.html",
    repos = [
        ":maven_dependencies",
        "//tools/base:agp_artifacts",
        "//tools/base:agp_dependencies",
    ],
    tasks = [":base:preview:screenshot:screenshot-test-gradle-plugin:zipPlugin"],
    visibility = [
        "//tools/base/build-system/integration-test:__subpackages__",
        "//tools/base/preview/screenshot:__pkg__",
    ],
)

diff_test(
    name = "screenshot-test-gradle-plugin_problems_test",
    failure_message = """
       Replace the known_problems.txt file in this directory with screenshot-test-gradle-plugin_problems.txt from the artifacts of //tools/base/preview/screenshot/screenshot-test-gradle-plugin:screenshot-test-gradle-plugin target in this bazel invocation.

       Alternatively, run the following command to update (make sure build is executed remotely by adding --config=remote, or --config=remote-exec):
         bazel build --config=remote //tools/base/preview/screenshot/screenshot-test-gradle-plugin:screenshot-test-gradle-plugin_problems.txt && cp `bazel info bazel-genfiles`/tools/base/preview/screenshot/screenshot-test-gradle-plugin/screenshot-test-gradle-plugin_problems.txt `bazel info workspace`/tools/base/preview/screenshot/screenshot-test-gradle-plugin/known_problems.txt
    """,
    file1 = ":screenshot-test-gradle-plugin_problems.txt",
    file2 = "known_problems.txt",
    # Only run on linux, as the problems are uninterestingly different across platforms
    target_compatible_with = ["@platforms//os:linux"],
)

gradle_test(
    name = "screenshot-test-gradle-plugin_test",
    timeout = "long",
    build_file = "//tools:agp_gradle_build_root_build_file",
    data = [
        ":agp_gradle_test_files",
    ] + SCREENSHOT_TEST_GRADLE_PLUGIN_SOURCE_FILES,
    gradle_properties = GRADLE_PROPERTIES | SCREENSHOT_TEST_PLUGIN_GRADLE_PROPERTIES,
    repos = [
        ":maven_test_dependencies",
        "//tools/base:agp_artifacts",
        "//tools/base:agp_dependencies",
    ],
    tasks = [":base:preview:screenshot:screenshot-test-gradle-plugin:test"],
    test_output_dir = "build/base/preview/screenshot/screenshot-test-gradle-plugin/build/test-results/test",
    visibility = ["//visibility:public"],
)

kotlin_library(
    name = "lib",
    srcs = glob([
        "src/main/java/**/*.kt",
    ]),
    lint_baseline = "lint_baseline.xml",
    lint_custom_rules = [
        "@maven//:androidx.lint.lint-gradle",  # For gradle-specific lint checks
    ],
    visibility = [
        "//tools/base/build-system/integration-test:__subpackages__",
        "//tools/base/preview/screenshot:__pkg__",
    ],
    deps = [
        "//tools/analytics-library/shared:tools.analytics-shared",
        "//tools/base/build-system:gradle-api",
        "//tools/base/build-system/gradle-common-api",
        "//tools/base/build-system/gradle-core",
        "//tools/base/common:tools.common",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
    ],
)
