load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.backup",
    srcs = ["src/main"],
    iml_files = ["android.sdktools.backup.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:jimfs",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_srcs = ["src/test"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/backup/bmgr-parser:studio.android.sdktools.backup.bmgr.parser",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/base/environment-services:studio.intellij.android.environment-services",
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

maven_library(
    name = "backup-restore",
    srcs = glob([
        "src/main/**/*.java",
        "src/main/**/*.kt",
    ]),
    bundled_deps = [
        "//tools/base/environment-services:tools.environment-services",
    ],
    coordinates = "com.android.tools.backup:backup-restore",
    description = "Library to perform backup and restore operations",
    module_name = "backup-restore",
    version = BASE_VERSION,
    visibility = [
        "//tools/base/backup-test:__subpackages__",
    ],
    deps = [
        "//tools/base/adblib",
        "//tools/base/backup/bmgr-parser",
        "//tools/base/common:tools.common",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)
