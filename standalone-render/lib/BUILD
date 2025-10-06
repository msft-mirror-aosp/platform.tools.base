load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

# Build target to verify that standalone-render.lib builds for tools/base
kotlin_library(
    name = "tools.standalone-render.lib",
    srcs = glob([
        "src/**/*.java",
        "src/**/*.kt",
    ]),
    lint_baseline = "lint_baseline.xml",
    resource_strip_prefix = "tools/base/standalone-render/lib/resources",
    resources = glob(["resources/**"]),
    visibility = ["//visibility:public"],
    deps = [
        "//prebuilts/tools/common/lint-psi/intellij-core",
        "//tools/adt/idea/layoutlib:tools.layoutlib",
        "//tools/adt/idea/layoutlib-loader:tools.layoutlib-loader",
        "//tools/adt/idea/preview-elements:tools.preview-elements",
        "//tools/adt/idea/render-resources:tools.render-resources",
        "//tools/adt/idea/rendering:tools.rendering",
        "//tools/adt/idea/wear-preview-elements:tools.wear-preview-elements",
        "//tools/analytics-library/crash:tools.analytics-crash",
        "//tools/base/common:tools.common",
        "//tools/base/environment-services:tools.environment-services",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "@maven//:com.google.code.gson.gson",
        "@maven//:org.apache.httpcomponents.httpmime",
        "@maven//:org.jetbrains.kotlin.kotlin-reflect",
        "@maven//:org.ow2.asm.asm",
        "@maven//:org.ow2.asm.asm-commons",
    ],
)

# Build target to verify that standalone-render.lib tests succeed for tools/base
kotlin_test(
    name = "tools.standalone-render.lib.tests",
    srcs = glob([
        "testSrc/**/*.kt",
        "testSrc/**/*.java",
    ]),
    # keep sorted
    data = [
        "//prebuilts/studio/layoutlib:runtime",
        "//prebuilts/studio/layoutlib/data:framework_res.jar",
        "//tools/base/standalone-render/lib/testData",
    ],
    jvm_flags = ["-Dtest.suite.jar=tools.standalone-render.lib.tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":tools.standalone-render.lib",
        "//prebuilts/tools/common/lint-psi/intellij-core",
        "//tools/adt/idea/preview-elements:tools.preview-elements",
        "//tools/adt/idea/render-resources:tools.render-resources",
        "//tools/adt/idea/rendering:tools.rendering",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "@maven//:junit.junit",
    ],
)
