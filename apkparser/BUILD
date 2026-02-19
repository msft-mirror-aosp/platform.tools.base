load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:jvm_import.bzl", "jvm_import")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.binary-resources",
    srcs = ["binary-resources/src/main/java"],
    iml_files = ["binary-resources/android.sdktools.binary-resources.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "//prebuilts/tools/common/m2:auto-value-annotations-1.11.0",
        "//prebuilts/tools/common/m2:jsr305-2.0.1",
        "//prebuilts/tools/common/m2:error_prone_annotations-2.36.0",
        "//prebuilts/tools/common/m2:checker-compat-qual-2.5.5",
        "//tools/base/apkparser:libbinary-resources",
    ],
)

java_library(
    name = "binary-resources",
    srcs = glob(["binary-resources/src/main/java/**/*.java"]),
    deps = [
        "//prebuilts/tools/common/m2:auto-value-annotations-1.11.0",
        "//prebuilts/tools/common/m2:checker-compat-qual-2.5.5",
        "//prebuilts/tools/common/m2:jsr305-2.0.1",
        "//tools/adt/idea/aswb/third_party/java/auto_value",
        "@maven//:com.google.guava.guava",
    ],
)

maven_library(
    name = "tools.binary-resources",
    srcs = glob(["binary-resources/src/main/java/**"]),
    bundled_deps = [
        "//prebuilts/tools/common/m2:checker-compat-qual-2.5.5",
        "//prebuilts/tools/common/m2:auto-value-annotations-1.11.0",
        "//tools/adt/idea/aswb/third_party/java/auto_value",
    ],
    coordinates = "com.android.tools.apkparser:binary-resources",
    description = "Library for parsing the resources.arsc file",
    javacopts = ["--release 14"],
    notice = "binary-resources/NOTICE",
    pom_name = "Binary Resources parser",
    resource_strip_prefix = "tools/base/apkparser/binary-resources",
    version = BASE_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:com.google.guava.guava",
    ],
)

# managed by go/iml_to_build
jvm_import(
    name = "libbinary-resources",
    jars = ["//tools/base/apkparser:libbinary-resources.jar"],
    visibility = ["//visibility:public"],
)
