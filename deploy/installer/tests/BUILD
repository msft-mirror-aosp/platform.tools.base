load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library", "cc_test")
load("//tools/base/bazel:proto.bzl", "android_java_proto_library", "cc_grpc_proto_library")

filegroup(
    name = "artifacts",
    srcs = [
        ":fake_app",
        ":fake_device_proto",
        ":fake_runas",
        ":fake_shell",
    ],
    visibility = ["//visibility:public"],
)

cc_grpc_proto_library(
    name = "proto",
    srcs = ["fake_device.proto"],
    grpc_support = 1,
    include_prefix = "proto",
    target_compatible_with = select({
        "@platforms//os:windows": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
)

android_java_proto_library(
    name = "fake_device_proto",
    srcs = ["fake_device.proto"],
    grpc_support = True,
    java_deps = ["@maven//:io.grpc.grpc-all"],
    visibility = ["//visibility:public"],
)

genrule(
    name = "gen_dex_bytes",
    srcs = [
        "data/min.dex",
    ],
    outs = [
        "min.dex.cc",
    ],
    cmd = "$(location //tools/base/bazel:bin2c) -lang=cxx -variable=min_dex" +
          " -output=$(location min.dex.cc)" +
          " $(location data/min.dex)",
    tools = [
        "//tools/base/bazel:bin2c",
    ],
    visibility = ["//visibility:private"],
)

cc_library(
    name = "dex_header",
    hdrs = [
        ":min.dex.cc",
    ],
)

cc_binary(
    name = "fake_app",
    srcs = [
        "fake_app.cc",
        "fake_jni.cc",
        "fake_jni.h",
        "fake_jvmti.cc",
        "fake_jvmti.h",
        "fake_vm.cc",
        "fake_vm.h",
    ],
    linkopts = ["-ldl"],
    target_compatible_with = select({
        "@platforms//os:windows": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
    visibility = ["//visibility:public"],
    deps = [
        ":dex_header",
        ":proto",
        "//prebuilts/studio/jdk:jni_headers",
        "//tools/base/deploy/agent/native:jvmti_android",
        "//tools/base/deploy/common:common_lib",
        "//tools/base/deploy/common:log",
        "@slicer",
    ],
)

cc_binary(
    name = "fake_shell",
    srcs = ["fake_shell.cc"],
    visibility = ["//visibility:public"],
    deps = [
        ":fake_device",
        ":proto",
        "//tools/base/deploy/common:log",
    ],
)

cc_binary(
    name = "fake_runas",
    srcs = ["fake_runas.cc"],
    visibility = ["//visibility:public"],
    deps = [
        ":fake_device",
        ":proto",
        "//tools/base/deploy/common:log",
    ],
)

cc_library(
    name = "fake_device",
    srcs = ["fake_device.cc"],
    hdrs = ["fake_device.h"],
    deps = [
        ":proto",
        "//tools/base/deploy/common:log",
    ],
)

cc_test(
    name = "integration_test",
    size = "small",
    srcs = ["tests.cc"],
    data = [
        "data/app/my.fake.app/sample.apk",
        "data/dumpOutput.txt",
        "data/patchTest.txt",
    ],
    linkstatic = 1,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/deploy/installer:installer_lib",
        "//tools/base/profiler/native/test:testutils",
        "@googletest//:gtest_main",
    ],
)
