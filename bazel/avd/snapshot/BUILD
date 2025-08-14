load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")
load("//tools/base/bazel:proto.bzl", "java_proto_library")

java_binary(
    name = "proto-patcher",
    main_class = "com.android.tools.bazel.avd.snapshot.ProtoPatcherKt",
    visibility = ["//tools/base/bazel/avd:__subpackages__"],
    runtime_deps = [":ProtoPatcher"],
)

kotlin_library(
    name = "ProtoPatcher",
    srcs = glob(["java/**/*.kt"]),
    deps = ["@maven//:com.google.protobuf.protobuf-java"],
)

kotlin_test(
    name = "ProtoPatcherTest",
    srcs = ["javatests/com/android/tools/bazel/avd/snapshot/ProtoPatcherTest.kt"],
    test_class = "com.android.tools.bazel.avd.snapshot.ProtoPatcherTest",
    deps = [
        ":ProtoPatcher",
        ":test_data_proto",
        "@maven//:io.grpc.grpc-protobuf",
        "@maven//:junit.junit",
    ],
)

java_proto_library(
    name = "test_data_proto",
    testonly = 1,
    srcs = glob(["javatests/**/*.proto"]),
)
