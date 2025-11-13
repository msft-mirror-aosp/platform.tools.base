load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

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

kotlin_library(
    name = "gradle-work-action-api",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    visibility = [
        "//tools/base/build-system:__subpackages__",
        "//tools/base/utp/gradle-work-action:__pkg__",
    ],
    deps = [
        "//tools/base/build-system:gradle-api_neverlink",
    ],
)
