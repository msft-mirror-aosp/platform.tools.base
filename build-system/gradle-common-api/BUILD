load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BUILD_VERSION")

maven_library(
    name = "gradle-common-api",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    bundled_deps = [
        "//tools/base/build-system:gradle-api_neverlink",  # Workaround for maven provider checks
    ],
    coordinates = "com.android.tools.build:gradle-common-api",
    version = BUILD_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/**/*.kt",
        "previous-gradle-apis/**",
    ]) + [
        "NOTICE",
        "build.gradle",
    ],
    visibility = [
        "//tools/base/build-system:__pkg__",
        "//tools/base/firebase/testlab/testlab-gradle-plugin:__pkg__",
        "//tools/base/preview/screenshot/screenshot-test-gradle-plugin:__pkg__",
    ],
)

filegroup(
    name = "agp_gradle_metalava_test_files",
    srcs = glob([
        "src/metalavaTest/**/*.kt",
        "api/**/*.txt",
    ]),
    visibility = ["//tools/base/build-system:__pkg__"],
)
