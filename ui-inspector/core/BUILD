load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

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

kotlin_test(
    name = "core_tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    friends = [":core"],
    jvm_flags = ["-Dtest.suite.jar=core_tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    # The cli tests use the test fixtures in ModelFixtures.kt (via core_tests.testlib).
    visibility = ["//tools/base/ui-inspector/cli:__pkg__"],
    deps = [
        ":core",
        "//prebuilts/tools/common/app-inspection/androidx/compose/ui:layout_inspector_compose_java_proto-nojarjar",
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/adblib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto-nojarjar",
        "@maven//:com.google.code.gson.gson",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)
