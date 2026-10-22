load("@rules_pkg//pkg:mappings.bzl", "pkg_filegroup", "pkg_files")
load("@rules_pkg//pkg:pkg.bzl", "pkg_zip")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

kotlin_library(
    name = "core",
    srcs = glob(["src/main/java/**/*.kt"]),
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
    # DeviceArtifactsDirectoryTest checks that device_artifacts_pkg_files has the layout DeviceArtifactsDirectory reads.
    data = [":device_artifacts"],
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

# The files the host pushes to the device. The standalone CLI bundles them as classpath resources. Tools that don't bundle them, e.g. the
# Android CLI, ship them in a zip of device_artifacts_pkg_files instead. Keep it in sync with device_artifacts_pkg_files.
filegroup(
    name = "device_artifact_files",
    srcs = [
        ":device_artifact_jars",
        "//tools/base/ui-inspector/art-tooling/native:libarttooling_agent.so",
    ],
    visibility = ["//tools/base/ui-inspector:__subpackages__"],
)

filegroup(
    name = "device_artifact_jars",
    srcs = [
        "//tools/base/ui-inspector/agent/inspectors/view:view_inspector_jar",
        "//tools/base/ui-inspector/agent/payload:lib_ui_inspector_payload",
        "//tools/base/ui-inspector/art-tooling/java:libarttooling",
    ],
)

# The jars, at the root of the layout.
pkg_files(
    name = "device_artifact_jar_files",
    srcs = [":device_artifact_jars"],
)

# The files the host pushes to the device, laid out as DeviceArtifactsDirectory reads them, for tools that ship them in a zip, e.g. the
# Android CLI's pack.
pkg_filegroup(
    name = "device_artifacts_pkg_files",
    srcs = [
        ":device_artifact_jar_files",
        "//tools/base/ui-inspector/art-tooling/native:libarttooling_agent_files",
    ],
    visibility = [
        "//tools/base/ui-inspector:__subpackages__",
        "//tools/vendor/google/android-cli/packs:__pkg__",
    ],
)

# A zip of device_artifacts_pkg_files, for DeviceArtifactsDirectoryTest.
pkg_zip(
    name = "device_artifacts",
    testonly = True,
    srcs = [":device_artifacts_pkg_files"],
)
