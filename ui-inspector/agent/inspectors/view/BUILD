load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

package(default_visibility = ["//tools/base/ui-inspector:__subpackages__"])

kotlin_library(
    name = "view_inspector",
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
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

kotlin_test(
    name = "view_inspector_tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    jvm_flags = ["-Dtest.suite.jar=view_inspector_tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":view_inspector",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/bazel:studio-proto",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
    ],
)
