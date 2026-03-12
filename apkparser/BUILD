load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:jvm_import.bzl", "jvm_import")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")

java_library(
    name = "binary-resources",
    srcs = glob(["binary-resources/src/main/java/**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//prebuilts/tools/common/m2:auto-value-annotations-1.11.0",
        "//prebuilts/tools/common/m2:checker-compat-qual-2.5.5",
        "//prebuilts/tools/common/m2:error_prone_annotations-2.36.0",
        "//prebuilts/tools/common/m2:jsr305-2.0.1",
        "//tools/adt/idea/aswb/third_party/java/auto_value",
        "@intellij//:intellij-sdk",
    ],
)

maven_library(
    name = "tools.binary-resources",
    srcs = glob(["binary-resources/src/main/java/**"]),
    bundled_deps = [
        "@maven//:com.google.auto.value.auto-value-annotations",
        "@maven//:org.checkerframework.checker-compat-qual",
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
