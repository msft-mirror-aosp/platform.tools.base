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
    target_compatible_with = select({
        "@platforms//os:osx": [],
        "@platforms//os:linux": [],
        "//conditions:default": ["@platforms//:incompatible"],
    }),
    visibility = ["//visibility:public"],
)

avd(
    name = "avd_35",
    image = "@system_image_android-35_default_x86_64//:x86_64-android-35-images",
    target_compatible_with = ["@platforms//os:linux"],
    visibility = ["//visibility:public"],
)

avd(
    name = "avd_34",
    image = "@system_image_android-34_aosp_atd_x86_64//:x86_64-android-34AospAtd-images",
    target_compatible_with = ["@platforms//os:linux"],
    visibility = ["//visibility:public"],
)

avd(
    name = "avd_32",
    image = "@system_image_android-32_aosp_atd_x86_64//:x86_64-android-32AospAtd-images",
    target_compatible_with = ["@platforms//os:linux"],
    visibility = ["//visibility:public"],
)

avd(
    name = "avd_30",
    image = "@system_image_android-30_default_x86_64//:x86_64-android-30-images",
    target_compatible_with = ["@platforms//os:linux"],
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
