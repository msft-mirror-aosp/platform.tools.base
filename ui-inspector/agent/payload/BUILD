load("//tools/base/bazel:android.bzl", "dex_library")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel:robolectric.bzl", "robolectric_test")

package(default_visibility = ["//tools/base/ui-inspector:__subpackages__"])

kotlin_library(
    name = "ui_inspector_payload_src",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    deps = [
        "//prebuilts/studio/sdk:platforms/latest_jar",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//tools/base/bazel:studio-proto",
        "//tools/base/ui-inspector/agent/inspectors/view:view_inspector",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

dex_library(
    name = "lib_ui_inspector_payload",
    aars = [
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
    ],
    jars = [
        ":ui_inspector_payload_src",
        "//tools/base/ui-inspector/agent/inspectors/view:view_inspector",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
        "//tools/base/bazel:studio-proto",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

robolectric_test(
    name = "payload_robolectric_tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    friends = [":ui_inspector_payload_src"],
    deps = [
        ":ui_inspector_payload_src",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/bazel:studio-proto",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
        "@maven//:org.mockito.mockito-core",
        "@maven//:org.robolectric.robolectric",
    ],
)
