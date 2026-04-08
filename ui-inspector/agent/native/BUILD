load("@rules_cc//cc:defs.bzl", "cc_binary")
load("//tools/base/bazel:android.bzl", "ANDROID_COPTS", "ANDROID_LINKOPTS", "android_cc_binary")

package(default_visibility = ["//visibility:public"])

cc_binary(
    name = "ui_inspector_agent_native",
    srcs = ["agent.cc"],
    copts = ANDROID_COPTS,
    linkopts = ANDROID_LINKOPTS,
    linkshared = 1,
    target_compatible_with = select({
        "@platforms//os:windows": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
    deps = [
        "//prebuilts/studio/jdk:jni_headers",
        "//tools/base/transport/native/jvmti",
        "//tools/base/transport/native/utils",
    ],
)

android_cc_binary(
    name = "lib_ui_inspector_agent.so",
    binary = ":ui_inspector_agent_native.stripped",
    filename = "lib_ui_inspector_agent.so",
)
