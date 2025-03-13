load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.dynamic-layout-inspector.common",
    srcs = ["src/main"],
    iml_files = ["dynamic-layout-inspector.common.iml"],
    jvm_target = "8",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//prebuilts/tools/common/m2:junit-4.12",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_srcs = ["src/test"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/.idea/libraries:studio-proto",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/adt/idea/.idea/libraries:layoutinspector-view-proto",
        "//tools/adt/idea/.idea/libraries:layoutinspector-skia-proto",
    ],
)
