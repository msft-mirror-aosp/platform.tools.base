load("//tools/base/bazel:android.bzl", "dex_library")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel:merge_archives.bzl", "merge_jars")
load("//tools/base/bazel:robolectric.bzl", "robolectric_test")
load("//tools/base/bazel:utils.bzl", "java_jarjar")

package(default_visibility = ["//tools/base/ui-inspector:__subpackages__"])

exports_files(["jarjar_rules.txt"])

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
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core-jvm",
    ],
)

merge_jars(
    name = "ui_inspector_payload_unjarjared",
    out = "ui_inspector_payload_unjarjared.jar",
    allow_duplicates = True,
    jars = [
        ":ui_inspector_payload_src",
        "//tools/base/ui-inspector/common",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core-jvm",
    ],
)

java_jarjar(
    name = "ui_inspector_payload_jarjared",
    srcs = [
        ":ui_inspector_payload_unjarjared",
    ],
    rules = "jarjar_rules.txt",
)

dex_library(
    name = "lib_ui_inspector_payload",
    aars = [
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
    ],
    jars = [
        ":ui_inspector_payload_jarjared",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
        "//tools/base/bazel:studio-proto",
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
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core-jvm",
        "@maven//:org.mockito.mockito-core",
        "@maven//:org.robolectric.robolectric",
    ],
)
