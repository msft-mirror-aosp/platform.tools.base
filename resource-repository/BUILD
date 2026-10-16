load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.resource-repository",
    srcs = ["main/java"],
    iml_files = ["android.sdktools.resource-repository.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    exports = [
        "//tools/base/layoutlib-api:studio.android.sdktools.layoutlib-api",
        "//tools/base/sdk-common:studio.android.sdktools.sdk-common",
    ],
    # do not sort: must match IML order
    deps = [
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/base/layoutlib-api:studio.android.sdktools.layoutlib-api",
        "//tools/base/sdklib:studio.android.sdktools.sdklib",
        "//tools/base/sdk-common:studio.android.sdktools.sdk-common",
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/environment-services:studio.intellij.android.environment-services",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.resource-repository.tests",
    iml_files = ["android.sdktools.resource-repository.tests.iml"],
    test_data = [
        "//prebuilts/studio/layoutlib/data:framework_res.jar",
        "//prebuilts/studio/layoutlib/data/overlays",
        "//prebuilts/studio/layoutlib/data/res",
        "//prebuilts/studio/sdk:platforms/latest",
        "//tools/base/resource-repository/test/resources",
    ],
    # do not sort: must match IML order
    test_deps = [
        "//tools/base/resource-repository:studio.android.sdktools.resource-repository",
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_friends = ["//tools/base/resource-repository:studio.android.sdktools.resource-repository"],
    test_srcs = ["test/java"],
    test_tags = [
        "block-network",
        "cpu:3",
        "noci:studio-win",
    ],
    test_timeout = "long",  # "medium" is not sufficient on Mac b/155999536
    visibility = ["//visibility:public"],
)

# buildifier: disable=native-java-binary
java_binary(
    name = "framework-res-jar-creator",
    main_class = "com.android.resources.aar.FrameworkResJarCreator",
    runtime_deps = [":studio.android.sdktools.resource-repository"],
)

genrule(
    name = "framework_res",
    srcs = [
        "//prebuilts/studio/layoutlib/data/overlays",
        "//prebuilts/studio/layoutlib/data/res",
        "//prebuilts/studio/layoutlib/data/res:version",
    ],
    outs = ["framework_res.jar"],
    cmd = "$(execpath :framework-res-jar-creator) $(execpath //prebuilts/studio/layoutlib/data/res:version)/.. $(execpath //prebuilts/studio/layoutlib/data/res:version)/../../overlays $(OUTS)",
    tools = [":framework-res-jar-creator"],
    visibility = ["//visibility:public"],
)

# Build target to verify that resource-repository builds for tools/base
kotlin_library(
    name = "tools.resource-repository",
    srcs = glob([
        "main/java/**/*.java",
        "main/java/**/*.kt",
    ]),
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/common:tools.common",
        "//tools/base/environment-services:tools.environment-services",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "//tools/base/sdk-common:tools.sdk-common",
        "@maven//:com.google.guava.guava",
        "@maven//:it.unimi.dsi.fastutil",
        "@maven//:net.sf.kxml.kxml2",
    ],
)
