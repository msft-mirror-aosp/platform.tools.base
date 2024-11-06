load("//tools/base/bazel:android.bzl", "dex_library")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

kotlin_library(
    name = "kexter",
    srcs = glob([
        "src/**/*.kt",
    ]),
    visibility = [
    ],
    deps = [
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)

kotlin_library(
    name = "jar_kexter_test_resources",
    srcs = glob([
        "test/resources/src/**/*.kt",
    ]),
    visibility = [
    ],
)

dex_library(
    name = "dex_kexter_test_resources",
    flags = ["--min-api 26"],
    jars = [
        ":jar_kexter_test_resources",
    ],
    visibility = [],
)

# To allow testing of sequential dex, we create a second dex file
kotlin_library(
    name = "jar_kexter_test_resources_second_dex",
    srcs = glob([
        "test/resources_second_dex/src/**/*.kt",
    ]),
    visibility = [
    ],
)

# To allow testing of sequential dex, we create a second dex file
dex_library(
    name = "dex_kexter_test_resources_second_dex",
    flags = ["--min-api 26"],
    jars = [
        ":jar_kexter_test_resources_second_dex",
    ],
    visibility = [],
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
        ":dex_kexter_test_resources",
        ":dex_kexter_test_resources_second_dex",
    ],
    test_srcs = [
        "test/src",
        "test/resources/src",
        "test/resources_second_dex/src",
    ],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/.idea/libraries:junit4[test]",
        "@intellij//:intellij-sdk",
        "//tools/base/zipflinger:studio.android.sdktools.zipflinger[module]",
    ],
)
