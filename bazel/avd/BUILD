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
        "//tools/base/bazel/platforms:macos-arm64": "//prebuilts/studio/sdk:system_image_android-33_default",
        "//conditions:default": "//prebuilts/studio/sdk:system_image_android-33_aosp_atd",
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
    image = "//prebuilts/studio/sdk:system_image_android-35_default",
    target_compatible_with = ["@platforms//os:linux"],
    visibility = ["//visibility:public"],
)

avd(
    name = "avd_34",
    image = "//prebuilts/studio/sdk:system_image_android-34_aosp_atd",
    target_compatible_with = ["@platforms//os:linux"],
    visibility = ["//visibility:public"],
)

avd(
    name = "avd_32",
    image = "//prebuilts/studio/sdk:system_image_android-32_aosp_atd",
    target_compatible_with = ["@platforms//os:linux"],
    visibility = ["//visibility:public"],
)

avd(
    name = "avd_30",
    image = "//prebuilts/studio/sdk:system_image_android-30_default",
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
