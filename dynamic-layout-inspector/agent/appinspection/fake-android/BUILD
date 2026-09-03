load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

package(default_visibility = ["//visibility:public"])

# Simulate an app that doesn't include androidx.window:window (which is needed
# for foldable device support).
kotlin_library(
    name = "fake_android",
    srcs = glob(
        [
            "src/**/*.kt",
            "src/**/*.java",
        ],
    ),
    lint_baseline = "lint_baseline.xml",
    deps = [
        "//prebuilts/studio/sdk:platforms/latest_jar",
        "@maven//:androidx.annotation.annotation",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)
