load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

kotlin_library(
    name = "kexter",
    srcs = glob([
        "src/**/*.kt",
    ]),
    visibility = [],
    deps = [
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)

kotlin_library(
    name = "apkdumperlib",
    srcs = [
        "tools/src/ApkDumper.kt",
    ],
    visibility = [],
    deps = [
        ":kexter",
        "//tools/base/zipflinger",
    ],
)

java_binary(
    name = "apkdumper",
    main_class = "kexter.tools.ApkDumper",
    runtime_deps = [
        ":apkdumperlib",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.kexter",
    # do not sort: must match IML order
    srcs = [
        "src",
        "tools/src",
    ],
    iml_files = ["android.sdktools.kexter.iml"],
    test_data = [
        "//tools/base/kexter/test/resources:container_dex",
        "//tools/base/kexter/test/resources:main_container_dex",
        "//tools/base/kexter/test/resources:main_dex",
        "//tools/base/kexter/test/resources:other_dex",
    ],
    test_deps = ["//tools/adt/idea/.idea/libraries:junit4"],
    # do not sort: must match IML order
    test_srcs = [
        "test/src",
        "test/resources/container/src",
        "test/resources/main/src",
        "test/resources/other/src",
    ],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/zipflinger:studio.android.sdktools.zipflinger",
    ],
)
