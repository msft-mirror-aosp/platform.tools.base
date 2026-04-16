load("//tools/base/bazel:android.bzl", "dex_library")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")
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
        "//tools/base/ui-inspector/common",
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
        "//tools/base/ui-inspector/common",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

kotlin_test(
    name = "payload_tests",
    srcs = glob(
        ["src/test/java/**/*.kt"],
        # Exclude tests that require robolectric
        exclude = ["src/test/java/**/appinspection/*.kt"],
    ),
    jvm_flags = ["-Dtest.suite.jar=payload_tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":ui_inspector_payload_src",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/testutils:tools.testutils",
    ],
)

robolectric_test(
    name = "payload_robolectric_tests",
    srcs = glob(["src/test/java/**/appinspection/*.kt"]),
    friends = [":ui_inspector_payload_src"],
    deps = [
        ":ui_inspector_payload_src",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/ui-inspector/common",
        "@maven//:org.mockito.mockito-core",
        "@maven//:org.robolectric.robolectric",
    ],
)
