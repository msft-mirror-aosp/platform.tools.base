load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.backup",
    srcs = ["src/main"],
    iml_files = ["android.sdktools.backup.iml"],
    test_srcs = ["src/test"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/base/environment-services:studio.intellij.android.environment-services",
        "//tools/adt/idea/.idea/libraries:junit4[test]",
        "//tools/adt/idea/.idea/libraries:jimfs[test]",
        "//tools/adt/idea/.idea/libraries:truth[test]",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.backup.cli",
    srcs = ["src/cli"],
    iml_files = ["android.sdktools.backup.cli.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/adblib-tools:studio.android.sdktools.adblib.tools",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/base/environment-services:studio.intellij.android.environment-services",
        "//tools/base/backup:studio.android.sdktools.backup",
    ],
)

java_binary(
    name = "android_backup",
    main_class = "com.android.backup.cli.AndroidBackup",
    runtime_deps = [
        ":studio.android.sdktools.backup.cli",
    ],
)

java_binary(
    name = "android_restore",
    main_class = "com.android.backup.cli.AndroidRestore",
    runtime_deps = [
        ":studio.android.sdktools.backup.cli",
    ],
)
