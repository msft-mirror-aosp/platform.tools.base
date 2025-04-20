load("//tools/base/bazel:utils.bzl", "merged_zip")

merged_zip(
    name = "journeys_maven_repo",
    srcs = [
        "//tools/base/journeys/journeys-gradle-plugin:journeys-gradle-plugin.zip",
        "//tools/base/journeys/journeys-junit5-engine:journeys-junit5-engine.zip",
    ],
    visibility = ["//visibility:public"],
)
