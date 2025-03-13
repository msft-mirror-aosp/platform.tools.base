load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.process-monitor",
    srcs = ["src/main"],
    data = [
        "//tools/base/process-monitor/process-tracker-agent:bundle",
    ],
    iml_files = ["android.sdktools.process-monitor.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/base/fakeadbserver:studio.android.sdktools.fakeadbserver",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:mockito-kotlin",
    ],
    test_resources = ["testResources"],
    test_srcs = ["src/test"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/adblib-tools:studio.android.sdktools.adblib.tools",
        "//tools/base/device-provisioner:studio.android.sdktools.device-provisioner",
        "//tools/base/ddmlib:studio.android.sdktools.ddmlib",
        "//tools/base/sdklib:studio.android.sdktools.sdklib",
    ],
)
