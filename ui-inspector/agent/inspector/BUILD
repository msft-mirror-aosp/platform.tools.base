load("//tools/base/bazel:android.bzl", "dex_library")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

package(default_visibility = ["//tools/base/ui-inspector:__subpackages__"])

kotlin_library(
    name = "ui_inspector_payload_src",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    deps = [
        "//prebuilts/studio/sdk:platforms/latest_jar",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

dex_library(
    name = "lib_ui_inspector_payload",
    jars = [
        ":ui_inspector_payload_src",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)
