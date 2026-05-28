load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.diff",
    srcs = ["src/main/kotlin"],
    iml_files = ["android.sdktools.diff.iml"],
    jvm_target = "8",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:kotlin-test",
    ],
    test_srcs = ["src/test/kotlin"],
    visibility = ["//visibility:public"],
    deps = ["@intellij//:intellij-sdk"],
)
