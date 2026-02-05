load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:coverage.bzl", "coverage_java_library")
load("//tools/base/bazel:utils.bzl", "java_jarjar")

coverage_java_library(
    name = "tools.tracer",
    srcs = glob(["src/**/*.java"]),
    visibility = ["//visibility:public"],
)

java_binary(
    name = "agent_binary",
    srcs = glob(["agent/src/**/*.java"]),
    deploy_manifest_lines = [
        "Premain-Class: com.android.tools.tracer.agent.TraceAgent",
        "Boot-Class-Path: trace_agent.jar",
    ],
    main_class = "com.android.tools.tracer.agent.TraceAgent",
    deps = [
        "@maven//:org.ow2.asm.asm",
        "@maven//:org.ow2.asm.asm-commons",
    ],
)

# Note that the code depends at runtime on the name "trace_agent.jar"
java_jarjar(
    name = "trace_agent",
    srcs = [
        ":agent_binary_deploy.jar",
    ],
    rules = "jarjar_rules.txt",
    visibility = ["//visibility:public"],
)

java_binary(
    name = "trace_test",
    srcs = glob(["agent/testSrc/com/android/tools/tracer/**/*.java"]),
    main_class = "com.android.tools.tracer.MainTest",
    deps = [
        ":tools.tracer",
        "//tools/base/annotations",
    ],
)

sh_test(
    name = "test_agent",
    size = "small",
    srcs = [
        "agent/testSrc/test_agent.sh",
    ],
    data = [
        "agent/testSrc/com/android/tools/tracer/test.profile",
        ":trace_agent",
        ":trace_test",
    ],
    tags = [
        "noci:studio-win",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.tracer",
    srcs = ["src"],
    iml_files = ["android.sdktools.tracer.iml"],
    lint_baseline = "lint_baseline.xml",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//prebuilts/tools/common/m2:wire-runtime-jvm-5.4.0",
        "//prebuilts/tools/common/m2:tracing-desktop-2.0.0-alpha01",
        "//prebuilts/tools/common/m2:tracing-wire-desktop-2.0.0-alpha01",
    ],
)
