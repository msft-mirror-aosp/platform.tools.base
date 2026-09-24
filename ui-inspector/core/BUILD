load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.ui-inspector.core",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.ui-inspector.core.iml"],
    test_deps = [
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
    deps = [
        # Add Compose Inspector protobuf Java classes
        "//prebuilts/tools/common/app-inspection/androidx/compose/ui:layout_inspector_compose_java_proto-nojarjar",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto-nojarjar",
        "@maven//:com.google.code.gson.gson",
        "@maven//:com.google.guava.guava",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

kotlin_library(
    name = "core",
    srcs = glob(["src/main/java/**/*.kt"]),
    resources = [
        "//tools/base/ui-inspector/agent/inspectors/view:view_inspector_jar",
        "//tools/base/ui-inspector/agent/payload:lib_ui_inspector_payload",
        "//tools/base/ui-inspector/art-tooling/java:libarttooling",
        "//tools/base/ui-inspector/art-tooling/native:libarttooling_agent.so",
    ],
    visibility = [
        "//tools/base/ui-inspector:__subpackages__",
        "//tools/vendor/google/android-cli:__subpackages__",
    ],
    deps = [
        "//prebuilts/tools/common/app-inspection/androidx/compose/ui:layout_inspector_compose_java_proto-nojarjar",
        "//tools/base/adblib",
        "//tools/base/annotations",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto-nojarjar",
        "@maven//:com.google.code.gson.gson",
        "@maven//:com.google.guava.guava",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)
