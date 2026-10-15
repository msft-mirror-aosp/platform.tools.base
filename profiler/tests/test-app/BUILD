load("//tools/base/bazel:android.bzl", "ANDROID_COPTS", "ANDROID_LINKOPTS")
load("//tools/base/transport/test-framework:transport_test.bzl", "transport_app")

package(default_visibility = ["//visibility:public"])

transport_app(
    name = "test-app",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = [
        "//tools/base/transport/test-framework/test-app:libtest-app",
    ],
)

cc_binary(
    name = "libmemorynativetest.so",
    srcs = ["src/main/cpp/jni_memory.cpp"],
    copts = ANDROID_COPTS,
    linkopts = ANDROID_LINKOPTS,
    linkshared = 1,
    target_compatible_with = select({
        "@platforms//os:windows": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
    visibility = ["//visibility:public"],
    deps = [
        "//prebuilts/studio/jdk:jni_headers",
    ],
)
