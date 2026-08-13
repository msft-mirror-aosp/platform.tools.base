load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/**/*.java",
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "NOTICE",
        "build.gradle",
    ],
    visibility = [
        "//tools/base/build-system:__subpackages__",
        "//tools/base/utp:__pkg__",
    ],
)

maven_library(
    name = "gradle-work-action-api",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    # gradle-api_neverlink is added to bundled_deps because it doesn't provide MavenInfo
    # (required by deps_compile_only). The maven_library macro detects the _neverlink suffix
    # and treats it as a compile-only dependency without actually bundling it.
    bundled_deps = [
        "//tools/base/build-system:gradle-api_neverlink",
    ],
    coordinates = "com.android.tools.utp:gradle-work-action-api",
    description = "Provides Gradle WorkAction API for executing the Unified Test Platform (UTP) in isolation.",
    notice = "NOTICE",
    version = BASE_VERSION,
    visibility = [
        "//tools/base/build-system:__subpackages__",
        "//tools/base/utp/gradle-work-action:__pkg__",
    ],
    deps = [
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
    ],
)
