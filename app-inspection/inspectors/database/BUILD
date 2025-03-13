load("//tools/adt/idea/studio:studio.bzl", "studio_data")
load("//tools/base/app-inspection:app_inspection.bzl", "app_inspection_jar")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel:proto.bzl", "android_java_proto_library")
load("//tools/base/bazel:robolectric.bzl", "robolectric_test")

package(default_visibility = ["//visibility:public"])

android_java_proto_library(
    name = "database_inspector_java_proto",
    srcs = ["resources/proto/database-inspector.proto"],
)

agent_deps = [
    ":database_inspector_java_proto",
    "//prebuilts/studio/sdk:platforms/latest_jar",
    "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
    "//prebuilts/tools/common/m2:androidx-sqlite-2.5.0-beta01",
    "//prebuilts/tools/common/m2:androidx-sqlite-bundled-2.5.0-beta01",
    "//tools/base/app-inspection/inspectors/common:app-inspection.inspectors.common",
    "//tools/base/bazel:studio-proto",
    "@maven//:androidx.annotation.annotation",
    "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core-jvm",
]

kotlin_library(
    name = "agent-jar",
    srcs = glob([
        "src/**/*.java",
        "src/**/*.kt",
    ]),
    resource_strip_prefix = "tools/base/app-inspection/inspectors/database/resources",
    resources = glob(["resources/META-INF/**"]),
    deps = agent_deps,
)

app_inspection_jar(
    name = "agent",
    srcs = glob([
        "src/**/*.kt",
        "src/**/*.java",
    ]),
    out = "database-inspector.jar",
    bundle_srcs = [
        "//tools/base/app-inspection/inspectors/common:app-inspection.inspectors.common",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core-jvm",
    ],
    d8_flags = [
        "--min-api 26",  # Database inspector is only supported on O+ devices.
    ],
    inspection_resource_strip_prefix = "tools/base/app-inspection/inspectors/database/resources",
    inspection_resources = glob(["resources/META-INF/**"]),
    protos = [":database_inspector_java_proto"],
    deps = agent_deps,
)

# Robolectric test needs to use androidx-sqlite-bundled-jvm instead of androidx-sqlite-bundled so
# we can't depend on agent-jar target. Rather, we recompile the prod sources with the different dep.
robolectric_test(
    name = "agent_tests",
    srcs = glob([
        "src/**/*.kt",
        "testSrc/**/*.kt",
    ]),
    friends = [":agent-jar"],
    deps = [
        ":database_inspector_java_proto",
        "//prebuilts/studio/sdk:platforms/latest_jar",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//prebuilts/tools/common/m2:androidx-sqlite-2.5.0-beta01",
        "//prebuilts/tools/common/m2:androidx-sqlite-bundled-jvm-2.5.0-beta01",
        "//tools/base/app-inspection/inspectors/common:app-inspection.inspectors.common",
        "//tools/base/app-inspection/inspectors/common:app-inspection.inspectors.common.testing",
        "//tools/base/app-inspection/inspectors/lib:inspection-testing",
        "//tools/base/bazel:studio-proto",
        "//tools/base/testutils:tools.testutils",
        "@maven//:androidx.annotation.annotation",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.kotlin.kotlin-test",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core-jvm",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-test-jvm",
        "@maven//:org.mockito.mockito-core",
        "@maven//:org.robolectric.robolectric",
    ],
)

studio_data(
    name = "bundle",
    files = ["database-inspector.jar"],
    mappings = {"tools/base/app-inspection/inspectors/database/": "app-inspection/"},
)
