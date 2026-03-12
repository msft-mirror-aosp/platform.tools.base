load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:proto.bzl", "java_proto_library")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.ddmlib-studio",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.ddmlib-studio.iml"],
    jvm_target = "8",
    lint_baseline = "lint_baseline.xml",
    test_data = ["//prebuilts/studio/sdk:platform-tools"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/fakeadbserver:studio.android.sdktools.fakeadbserver",
    ],
    test_resources = ["src/test/resources"],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    exports = [
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/adt/idea/.idea/libraries:kxml2",
    ],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/adt/idea/.idea/libraries:kxml2",
        "//tools/adt/idea/.idea/libraries:libam-instrumentation-data-proto",
        "//tools/adt/idea/.idea/libraries:libadb-server-proto",
    ],
)

java_proto_library(
    name = "am-instrumentation-data-proto",
    srcs = glob(["src/main/proto/instrumentation-data.proto"]),
    visibility = ["//visibility:public"],
)

java_proto_library(
    name = "adb-server-proto",
    srcs = glob([
        "src/main/proto/app_processes.proto",
        "src/main/proto/devices.proto",
    ]),
    visibility = ["//visibility:public"],
)
