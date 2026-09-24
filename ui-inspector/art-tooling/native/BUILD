load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library", "cc_test")
load("//tools/base/bazel:android.bzl", "ANDROID_COPTS", "ANDROID_LINKOPTS", "android_cc_binary")

cc_library(
    name = "agent_options",
    srcs = ["agent_options.cc"],
    hdrs = [
        "agent_options.h",
        "art_tooling_constants.h",
    ],
)

cc_binary(
    name = "arttooling_agent_native",
    srcs = [
        "agent.cc",
        "array_params_entry_hook.cc",
        "array_params_entry_hook.h",
        "art_tooling_constants.h",
        "art_tooling_jni.cc",
        "art_tooling_jni.h",
        "art_tooling_transform.h",
        "jvmti_art_tooling.cc",
        "jvmti_art_tooling.h",
    ],
    copts = ANDROID_COPTS,
    linkopts = ANDROID_LINKOPTS,
    linkshared = 1,
    target_compatible_with = select({
        "@platforms//os:windows": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
    deps = [
        ":agent_options",
        "//prebuilts/studio/jdk:jni_headers",
        "//tools/base/transport/native/agent:jni_wrappers",
        "//tools/base/transport/native/jvmti",
        "//tools/base/transport/native/utils",
        "@slicer",
    ],
)

android_cc_binary(
    name = "libarttooling_agent.so",
    binary = ":arttooling_agent_native.stripped",
    filename = "libarttooling_agent.so",
    visibility = ["//tools/base/ui-inspector:__subpackages__"],
)

cc_test(
    name = "agent_options_test",
    srcs = ["agent_options_test.cc"],
    linkstatic = 1,
    deps = [
        ":agent_options",
        "@googletest//:gtest_main",
    ],
)
