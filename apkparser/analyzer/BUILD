load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:coverage.bzl", "coverage_java_test")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.analyzer",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.analyzer.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:jimfs",
    ],
    test_resources = ["src/test/resources"],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/common:studio.android.sdktools.common",
        "//prebuilts/tools/common/archive-patcher:explainer",
        "//prebuilts/tools/common/archive-patcher:generator",
        "//prebuilts/tools/common/archive-patcher:shared",
        "//tools/adt/idea/.idea/libraries:google-dexlib2",
        "//tools/adt/idea/.idea/libraries:google-baksmali",
        "//tools/base/apkparser:studio.android.sdktools.binary-resources",
        "//tools/base/sdk-common:studio.android.sdktools.sdk-common",
        "//tools/base/zipflinger:studio.android.sdktools.zipflinger",
    ],
)

kotlin_library(
    name = "tools.analyzer",
    srcs = glob([
        "src/main/java/**/*.java",
        "src/main/java/**/*.kt",
    ]),
    jvm_target = "17",
    visibility = ["//visibility:public"],
    deps = [
        "//prebuilts/tools/common/archive-patcher:explainer",
        "//prebuilts/tools/common/archive-patcher:generator",
        "//prebuilts/tools/common/archive-patcher:shared",
        "//tools/base/apkparser:tools.binary-resources",
        "//tools/base/common:tools.common",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/zipflinger",
        "@maven//:com.android.tools.smali.smali-baksmali",
        "@maven//:com.android.tools.smali.smali-dexlib2",
        "@maven//:com.google.guava.guava",
        "@maven//:org.jetbrains.annotations",
    ],
)

# Sepeare for now as the archive-patcher jars are not shipped to maven.
maven_library(
    name = "tools.analyzer.maven",
    srcs = [],
    bundled_deps = [
        ":tools.analyzer",
    ],
    coordinates = "com.android.tools.apkparser:apkanalyzer",
    description = "Library for parsing and analyzing information from APK files",
    notice = "NOTICE",
    pom_name = "Apk Analyzer library",
    resource_strip_prefix = "tools/base/apkparser/analyzer",
    version = BASE_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/apkparser:tools.binary-resources",
        "//tools/base/common:tools.common",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "@maven//:com.android.tools.smali.smali-baksmali",
        "@maven//:com.android.tools.smali.smali-dexlib2",
        "@maven//:com.google.guava.guava",
    ],
)

kotlin_library(
    name = "tools.tests.lib",
    srcs = glob([
        "src/test/java/**/*.kt",
    ]),
    deps = [
        ":tools.analyzer",
        "//tools/base/apkparser:tools.binary-resources",
        "//tools/base/common:tools.common",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.android.tools.smali.smali-dexlib2",
        "@maven//:com.google.jimfs.jimfs",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.annotations",
    ],
)

coverage_java_test(
    name = "tools.tests",
    srcs = glob(["src/test/java/**/*.java"]),
    jvm_flags = ["-Dtest.suite.jar=tools.tests.jar"],
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":tools.analyzer",
        ":tools.tests.lib",
        "//tools/base/apkparser:tools.binary-resources",
        "//tools/base/common:tools.common",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.android.tools.smali.smali-dexlib2",
        "@maven//:com.google.jimfs.jimfs",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.annotations",
    ],
)
