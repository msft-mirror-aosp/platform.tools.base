load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.device-provisioner",
    srcs = ["src/main"],
    iml_files = ["android.sdktools.device-provisioner.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/adblib-tools:studio.android.sdktools.adblib.tools",
        "//tools/base/sdklib:studio.android.sdktools.sdklib",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/.idea/libraries:studio-proto",
        "//tools/analytics-library/shared:analytics-shared",
        "//tools/adt/idea/.idea/libraries:emulator-proto",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.device-provisioner.tests",
    iml_files = ["android.sdktools.device-provisioner.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/base/device-provisioner:studio.android.sdktools.device-provisioner",
        "@intellij//:intellij-sdk",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/sdklib:studio.android.sdktools.sdklib",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/.idea/libraries:studio-proto",
        "//tools/adt/idea/.idea/libraries:emulator-proto",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/base/fakeadbserver:studio.android.sdktools.fakeadbserver",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/base/adblib:studio.android.sdktools.adblib.tests",
        "//tools/base/sdklib:studio.android.sdktools.sdklib.tests",
    ],
    test_friends = ["//tools/base/device-provisioner:studio.android.sdktools.device-provisioner"],
    test_srcs = ["src/test"],
    visibility = ["//visibility:public"],
)
