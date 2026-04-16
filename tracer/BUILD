load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:coverage.bzl", "coverage_java_library")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

coverage_java_library(
    name = "tools.tracer",
    srcs = glob(["src/**/*.java"]),
    visibility = ["//visibility:public"],
)

java_binary(
    name = "agent_binary",
    srcs = [
        "agent/src/com/android/tools/tracer/agent/TraceAgent.java",
        "agent/src/com/android/tools/tracer/agent/Tracer.java",
    ],
    deploy_env = [":trace_agent_impl"],
    deploy_manifest_lines = [
        "Premain-Class: com.android.tools.tracer.agent.TraceAgent",
        "Boot-Class-Path: trace_agent.jar",
    ],
    main_class = "com.android.tools.tracer.agent.TraceAgent",
    resource_strip_prefix = "tools/base/tracer",
    resources = [":trace_agent_impl_deploy.jar"],
)

java_binary(
    name = "trace_agent_impl",
    srcs = glob(
        ["agent/src/**/*.java"],
        exclude = ["agent/src/com/android/tools/tracer/agent/TraceAgent.java"],
    ),
    main_class = "com.android.tools.tracer.agent.TraceAgentImpl",
    deps = [
        ":tools.tracing",
        "@maven//:org.ow2.asm.asm",
        "@maven//:org.ow2.asm.asm-commons",
    ],
)

# Note that the code depends at runtime on the name "trace_agent.jar"
genrule(
    name = "trace_agent",
    srcs = [":agent_binary_deploy.jar"],
    outs = ["trace_agent.jar"],
    cmd = "cp $< $@",
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

kotlin_library(
    name = "tools.tracing",
    srcs = glob(["src/**/*"]),
    runtime_deps = ["@maven//:com.squareup.wire.wire-runtime-jvm"],
    deps = ["@maven//:androidx.tracing.tracing-wire-desktop"],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.tracer",
    srcs = ["src"],
    iml_files = ["android.sdktools.tracer.iml"],
    lint_baseline = "lint_baseline.xml",
    # do not sort: must match IML order
    test_deps = [
        "//prebuilts/tools/common/m2:collection-jvm-1.4.5",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//prebuilts/tools/common/m2:wire-runtime-jvm-6.0.0",
        "//prebuilts/tools/common/m2:tracing-desktop-2.0.0-alpha05",
        "//prebuilts/tools/common/m2:tracing-wire-desktop-2.0.0-alpha05",
        "//prebuilts/tools/common/m2:okio-jvm-3.9.0",
    ],
)
