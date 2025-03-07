load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel:utils.bzl", "java_jarjar")

java_binary(
    name = "agent_binary",
    srcs = glob([
        "src/**/*.java",
    ]),
    deploy_manifest_lines = [
        "Premain-Class: com.android.tools.instrumentation.threading.agent.Agent",
        "Agent-Class: com.android.tools.instrumentation.threading.agent.Agent",
        "Boot-Class-Path: threading_agent.jar",
    ],
    main_class = "com.android.tools.instrumentation.threading.agent.Agent",
    deps = [
        "//tools/base/annotations",
        "//tools/base/threading-agent-callback:agent_callback",
        "@maven//:org.ow2.asm.asm",
        "@maven//:org.ow2.asm.asm-commons",
    ],
)

kotlin_library(
    name = "sample_kotlin_classes",
    srcs = glob(["testSrc/**/SampleClassesKotlin.kt"]),
    deps = [
        "//tools/base/annotations",
    ],
)

# Rule to build the `threading_agent.jar` that will be injected into the target JVM.
# It creates a single jar file with no external dependencies. It also repackages
# java libraries so that their versions loaded with the java agent by the boot classloader
# do not conflict with the versions of the same libraries loaded by the system class loader.
java_jarjar(
    name = "threading_agent",
    srcs = [
        ":agent_binary_deploy.jar",
    ],
    rules = "jarjar_rules.txt",
    visibility = ["//visibility:public"],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.threading-agent",
    srcs = ["src"],
    iml_files = ["android.sdktools.threading-agent.iml"],
    test_class = "com.android.tools.instrumentation.threading.agent.AgentTestSuiteBase",
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/base/threading-agent-callback:studio.android.sdktools.threading-agent-callback",
        "//tools/adt/idea/.idea/libraries:asm-tools",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "@intellij//:com.intellij.java",
        "@intellij//:intellij-sdk[test]",
        "//tools/adt/idea/.idea/libraries:truth[test]",
        "//tools/adt/idea/.idea/libraries:mockito[test]",
        "//tools/adt/idea/.idea/libraries:junit4[test]",
        "@intellij//:test-framework[test]",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils[test]",
        "//tools/base/testutils:studio.android.sdktools.testutils[test]",
    ],
)
