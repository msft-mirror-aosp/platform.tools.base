load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel/avd:avd.bzl", "avd")

exports_files([
    "emulator_launcher.sh.template",
])

java_library(
    name = "emulator_rule",
    srcs = [
        "src/com/android/tools/bazel/avd/Emulator.java",
    ],
    visibility = [
        "//visibility:public",
    ],
    deps = [
        "@maven//:junit.junit",
    ],
)

avd(
    name = "default_avd",
    image = select({
        "//tools/base/bazel/platforms:macos-arm64": "@system_image_android-33_default_arm64//:arm64-android-33-images",
        "//conditions:default": "@system_image_android-33_aosp_atd_x86_64//:x86_64-android-33AospAtd-images",
    }),
    visibility = ["//visibility:public"],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.base.bazel.avd",
    srcs = ["src"],
    iml_files = ["android.sdktools.base.bazel.avd.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
    ],
)
