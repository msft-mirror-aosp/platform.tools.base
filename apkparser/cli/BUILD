load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:coverage.bzl", "coverage_java_library", "coverage_java_test")
load("//tools/base/sdklib:sdk.bzl", "sdk_java_binary")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.apkparser-cli",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.apkparser-cli.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/base/testutils:studio.android.sdktools.testutils",
    ],
    test_resources = ["src/test/resources"],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/base/sdklib:studio.android.sdktools.sdklib",
        "//tools/base/apkparser/analyzer:studio.android.sdktools.analyzer",
        "//tools/base/sdk-common:studio.android.sdktools.sdk-common",
        "//tools/adt/idea/.idea/libraries:google-dexlib2",
        "//tools/base/apkparser:studio.android.sdktools.binary-resources",
        "//prebuilts/tools/common/m2:jopt-simple-4.9",
    ],
)

coverage_java_library(
    name = "tools.analyzer-cli",
    srcs = glob(["src/main/java/**"]),
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/apkparser:tools.binary-resources",
        "//tools/base/apkparser/analyzer:tools.analyzer",
        "//tools/base/common:tools.common",
        "//tools/base/profgen/profgen",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "@maven//:com.android.tools.smali.smali-dexlib2",
        "@maven//:net.sf.jopt-simple.jopt-simple",
        "@maven//:org.jetbrains.annotations",
    ],
)

coverage_java_test(
    name = "tools.tests",
    srcs = glob(["src/test/java/**"]),
    jvm_flags = ["-Dtest.suite.jar=tools.tests.jar"],
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":tools.analyzer-cli",
        "//tools/base/apkparser/analyzer:tools.analyzer",
        "//tools/base/common:tools.common",
        "//tools/base/profgen/profgen",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/testutils:tools.testutils",
        "@maven//:junit.junit",
        "@maven//:org.mockito.mockito-core",
    ],
)

sdk_java_binary(
    name = "apkanalyzer",
    default_jvm_opts = {
        "linux": "-Dcom.android.sdklib.toolsdir=$APP_HOME",
        "mac": "-Dcom.android.sdklib.toolsdir=$APP_HOME",
        "win": "-Dcom.android.sdklib.toolsdir=%~dp0\\..",
    },
    main_class = "com.android.tools.apk.analyzer.ApkAnalyzerCli",
    visibility = ["//visibility:public"],
    runtime_deps = ["tools.analyzer-cli"],
)
