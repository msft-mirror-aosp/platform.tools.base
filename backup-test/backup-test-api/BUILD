load("@rules_android//rules:rules.bzl", "android_library")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

android_library(
    name = "android_api",
    neverlink = 1,
)

kotlin_library(
    name = "backup-test-api",
    srcs = glob([
        "src/main/java/**/*.kt",
    ]),
    visibility = ["//visibility:public"],
    deps = [
        ":android_api",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)
