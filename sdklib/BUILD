load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:expand_template.bzl", "expand_template")
load("//tools/base/bazel:jvm_import.bzl", "jvm_import")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/bazel:merge_archives.bzl", "merge_jars")
load(
    "//tools/base/common:version.bzl",
    "BASE_VERSION",
    "DEV_COMMANDLINE_TOOLS_VERSION",
    "RELEASE_COMMANDLINE_TOOLS_VERSION",
)
load("//tools/base/repository:xjc.bzl", "xsd_java_library")
load(":sdk.bzl", "sdk_java_binary", "sdk_package")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.sdklib",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.sdklib.iml"],
    jvm_target = "8",
    lint_baseline = "lint_baseline.xml",
    resources = ["src/main/resources"],
    test_data = ["//prebuilts/studio/sdk:platforms/latest"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:guava-testlib",
        "//tools/adt/idea/.idea/libraries:jimfs",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/testutils:studio.android.sdktools.testutils",
    ],
    test_resources = ["src/test/resources"],
    test_srcs = [
        "src/integrationTest/java",
        "src/test/java",
    ],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    exports = [
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/base/device_validator:studio.android.sdktools.dvlib",
        "//tools/base/layoutlib-api:studio.android.sdktools.layoutlib-api",
        "//tools/base/repository:studio.android.sdktools.repository",
        "//tools/base/sdklib:schema-generated",
    ],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/.idea/libraries:agp-version",
        "@intellij//:com.intellij.java",
        "@intellij//:intellij-sdk",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/base/device_validator:studio.android.sdktools.dvlib",
        "//tools/base/layoutlib-api:studio.android.sdktools.layoutlib-api",
        "//tools/base/repository:studio.android.sdktools.repository",
        "//tools/base/sdklib:schema-generated",
    ],
)

kotlin_library(
    name = "tools.sdklib.core",
    srcs = glob(
        [
            "src/main/java/**/*.java",
            "src/main/java/**/*.kt",
        ],
        exclude = [
            "src/main/java/**/tool/**/*.java",
        ],
    ),
    lint_baseline = "lint_baseline_core.xml",
    module_name = "sdklib",
    resource_strip_prefix = "tools/base/sdklib/src/main/resources",
    resources = glob(["src/main/resources/**"]),
    deps = [
        "//tools/base/common:tools.common",
        "//tools/base/device_validator:tools.dvlib",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "//tools/base/repository:tools.repository",
        "@maven//:com.google.code.gson.gson",
        "@maven//:org.apache.commons.commons-compress",
        "@maven//:org.apache.httpcomponents.httpcore",
        "@maven//:org.apache.httpcomponents.httpmime",
        "@maven//:org.glassfish.jaxb.jaxb-runtime",
        "@maven//:org.jetbrains.kotlin.kotlin-reflect",
    ],
)

generated_libs = [
    ":sdk-addon-1.lib",
    ":sdk-addon-2.lib",
    ":sdk-addon-3.lib",
    ":sdk-addon-4.lib",
    ":sdk-common-1.lib",
    ":sdk-common-2.lib",
    ":sdk-common-3.lib",
    ":sdk-common-4.lib",
    ":sdk-repository-1.lib",
    ":sdk-repository-2.lib",
    ":sdk-repository-3.lib",
    ":sdk-repository-4.lib",
    ":sdk-sites-list-1.lib",
    ":sdk-sites-list-2.lib",
    ":sdk-sites-list-3.lib",
    ":sdk-sites-list-4.lib",
    ":sdk-sites-list-5.lib",
    ":sdk-sites-list-6.lib",
    ":sdk-sites-list-7.lib",
    ":sdk-sysimg-1.lib",
    ":sdk-sysimg-2.lib",
    ":sdk-sysimg-3.lib",
    ":sdk-sysimg-4.lib",
    ":sdk-sysimg-5.lib",
]

maven_library(
    name = "tools.sdklib",
    srcs = [],
    bundled_deps = [
        ":avdmanager_lib",
        ":sdkmanager_lib",
        ":tools.sdklib.core",
    ] + generated_libs,
    coordinates = "com.android.tools:sdklib",
    description = "A library to parse and download the Android SDK.",
    notice = "NOTICE",
    version = BASE_VERSION,
    visibility = ["//visibility:public"],
    exports = [
        "//tools/base/repository:tools.repository",
    ],
    deps = [
        "//tools/base/common:tools.common",
        "//tools/base/device_validator:tools.dvlib",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "@maven//:com.google.code.gson.gson",
        "@maven//:org.apache.commons.commons-compress",
        "@maven//:org.apache.httpcomponents.httpcore",
        "@maven//:org.apache.httpcomponents.httpmime",
        "@maven//:org.glassfish.jaxb.jaxb-runtime",
    ],
)

kotlin_test(
    name = "tools.tests",
    srcs = glob([
        "src/test/java/**/*.kt",
        "src/test/java/**/*.java",
    ]),
    data = ["//prebuilts/studio/sdk:platforms/latest"],
    friends = [":tools.sdklib.core"],
    jvm_flags = ["-Dtest.suite.jar=tools.tests.jar"],
    lint_baseline = "lint_baseline_tests.xml",
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    runtime_deps = ["//tools/base/testutils:tools.testutils"],
    deps = [
        ":avdmanager_lib",
        ":sdkmanager_lib",
        ":tools.sdklib.core",
        "//tools/base/annotations",
        "//tools/base/common:tools.common",
        "//tools/base/device_validator:tools.dvlib",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "//tools/base/repository:tools.repository",
        "//tools/base/repository:tools.testlib",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.guava.guava-testlib",
        "@maven//:com.google.jimfs.jimfs",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.apache.httpcomponents.httpcore",
        "@maven//:org.jetbrains.kotlin.kotlin-reflect",
        "@maven//:org.mockito.mockito-core",
    ],
)

java_library(
    name = "avdmanager_lib",
    srcs = glob(["src/main/java/**/tool/AvdManagerCli.java"]),
    javacopts = ["--release 11"],
    deps = [
        ":tools.sdklib.core",
        "//tools/base/annotations",
        "//tools/base/common:tools.common",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "//tools/base/repository:tools.repository",
        "@maven//:com.google.guava.guava",
    ] + generated_libs,
)

java_library(
    name = "sdkmanager_lib",
    srcs = glob(["src/main/java/**/tool/sdkmanager/*.java"]),
    javacopts = ["--release 11"],
    deps = [
        ":tools.sdklib.core",
        "//tools/base/annotations",
        "//tools/base/common:tools.common",
        "//tools/base/repository:tools.repository",
        "@maven//:com.google.guava.guava",
    ] + generated_libs,
)

sdk_java_binary(
    name = "avdmanager",
    default_jvm_opts = {
        "linux": "\"-Dcom.android.sdkmanager.toolsdir=$APP_HOME\"",
        "mac": "\"-Dcom.android.sdkmanager.toolsdir=$APP_HOME\"",
        "win": "\"-Dcom.android.sdkmanager.toolsdir=%~dp0\\..\"",
    },
    main_class = "com.android.sdklib.tool.AvdManagerCli",
    runtime_deps = [":avdmanager_lib"],
)

sdk_java_binary(
    name = "sdkmanager",
    default_jvm_opts = {
        "linux": "\"-Dcom.android.sdklib.toolsdir=$APP_HOME\"",
        "mac": "\"-Dcom.android.sdklib.toolsdir=$APP_HOME\"",
        "win": "\"-Dcom.android.sdklib.toolsdir=%~dp0\\..\"",
    },
    main_class = "com.android.sdklib.tool.sdkmanager.SdkManagerCli",
    runtime_deps = [":sdkmanager_lib"],
)

sdk_java_binary(
    name = "resourceshrinker",
    main_class = "com.android.build.shrinker.ResourceShrinkerCli",
    runtime_deps = ["//tools/base/build-system/shrinker"],
)

sdk_java_binary(
    name = "d8",
    main_class = "com.android.tools.r8.D8",
    runtime_deps = ["//prebuilts/r8"],
)

sdk_java_binary(
    name = "r8",
    main_class = "com.android.tools.r8.R8",
    runtime_deps = ["//prebuilts/r8"],
)

sdk_java_binary(
    name = "retrace",
    main_class = "com.android.tools.r8.retrace.Retrace",
    runtime_deps = ["//prebuilts/r8"],
)

expand_template(
    name = "source_properties",
    out = "source.properties",
    substitutions = select({
        "//tools/base/bazel:release": {
            "{VERSION}": RELEASE_COMMANDLINE_TOOLS_VERSION,
        },
        "//conditions:default": {
            "{VERSION}": DEV_COMMANDLINE_TOOLS_VERSION,
        },
    }),
    template = "tools_source.properties",
)

sdk_package(
    name = "commandlinetools",
    binaries = [
        ":avdmanager",
        ":sdkmanager",
        ":d8",
        ":r8",
        ":retrace",
        ":resourceshrinker",
        "//tools/base/apkparser/cli:apkanalyzer",
        "//tools/base/lint/cli:lint",
        "//tools/base/misc/screenshot2:screenshot2",
        "//tools/base/profgen/profgen-cli:profgen",
    ],
    sourceprops = ":source.properties",
    visibility = ["//visibility:public"],
)

test_suite(
    name = "commandlinetoolstest",
    tests = ["//tools/base/sdklib/src/integrationTest:integration-test.test"],
)

xsd_java_library(
    name = "sdk-common-1",
    binding = "src/main/resources/xsd/sdk-common-custom-01.xjb",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.common.v1",
    xsd = "src/main/resources/xsd/sdk-common-01.xsd",
    xsd_deps = ["//tools/base/repository:repo-common-1"],
)

xsd_java_library(
    name = "sdk-common-2",
    binding = "src/main/resources/xsd/sdk-common-custom-02.xjb",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.common.v2",
    xsd = "src/main/resources/xsd/sdk-common-02.xsd",
    xsd_deps = ["//tools/base/repository:repo-common-2"],
)

xsd_java_library(
    name = "sdk-repository-1",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.repository.v1",
    xsd = "src/main/resources/xsd/sdk-repository-01.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-1",
        "//tools/base/repository:generic-1",
        ":sdk-common-1",
    ],
)

xsd_java_library(
    name = "sdk-repository-2",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.repository.v2",
    xsd = "src/main/resources/xsd/sdk-repository-02.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        "//tools/base/repository:generic-2",
        ":sdk-common-2",
    ],
)

xsd_java_library(
    name = "sdk-addon-1",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.addon.v1",
    xsd = "src/main/resources/xsd/sdk-addon-01.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-1",
        ":sdk-common-1",
    ],
)

xsd_java_library(
    name = "sdk-addon-2",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.addon.v2",
    xsd = "src/main/resources/xsd/sdk-addon-02.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        ":sdk-common-2",
    ],
)

xsd_java_library(
    name = "sdk-sysimg-1",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.sysimg.v1",
    xsd = "src/main/resources/xsd/sdk-sys-img-01.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-1",
        ":sdk-common-1",
    ],
)

xsd_java_library(
    name = "sdk-sysimg-2",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.sysimg.v2",
    xsd = "src/main/resources/xsd/sdk-sys-img-02.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        ":sdk-common-2",
    ],
)

xsd_java_library(
    name = "sdk-sites-list-1",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.sources.generated.v1",
    xsd = "src/main/resources/xsd/sources/sdk-sites-list-1.xsd",
    xsd_deps = ["//tools/base/repository:repo-sites-common-1"],
)

xsd_java_library(
    name = "sdk-sites-list-2",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.sources.generated.v2",
    xsd = "src/main/resources/xsd/sources/sdk-sites-list-2.xsd",
    xsd_deps = ["//tools/base/repository:repo-sites-common-1"],
)

xsd_java_library(
    name = "sdk-sites-list-3",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.sources.generated.v3",
    xsd = "src/main/resources/xsd/sources/sdk-sites-list-3.xsd",
    xsd_deps = ["//tools/base/repository:repo-sites-common-1"],
)

xsd_java_library(
    name = "sdk-sites-list-4",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.sources.generated.v4",
    xsd = "src/main/resources/xsd/sources/sdk-sites-list-4.xsd",
    xsd_deps = ["//tools/base/repository:repo-sites-common-1"],
)

xsd_java_library(
    name = "sdk-common-3",
    binding = "src/main/resources/xsd/sdk-common-custom-03.xjb",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.common.v3",
    xsd = "src/main/resources/xsd/sdk-common-03.xsd",
    xsd_deps = ["//tools/base/repository:repo-common-2"],
)

xsd_java_library(
    name = "sdk-common-4",
    binding = "src/main/resources/xsd/sdk-common-custom-04.xjb",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.common.v4",
    xsd = "src/main/resources/xsd/sdk-common-04.xsd",
    xsd_deps = ["//tools/base/repository:repo-common-2"],
)

xsd_java_library(
    name = "sdk-repository-3",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.repository.v3",
    xsd = "src/main/resources/xsd/sdk-repository-03.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        "//tools/base/repository:generic-2",
        ":sdk-common-3",
    ],
)

xsd_java_library(
    name = "sdk-repository-4",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.repository.v4",
    xsd = "src/main/resources/xsd/sdk-repository-04.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        "//tools/base/repository:generic-2",
        ":sdk-common-4",
    ],
)

xsd_java_library(
    name = "sdk-addon-3",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.addon.v3",
    xsd = "src/main/resources/xsd/sdk-addon-03.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        ":sdk-common-3",
    ],
)

xsd_java_library(
    name = "sdk-addon-4",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.addon.v4",
    xsd = "src/main/resources/xsd/sdk-addon-04.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        ":sdk-common-4",
    ],
)

xsd_java_library(
    name = "sdk-sysimg-3",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.sysimg.v3",
    xsd = "src/main/resources/xsd/sdk-sys-img-03.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        ":sdk-common-3",
    ],
)

xsd_java_library(
    name = "sdk-sysimg-4",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.sysimg.v4",
    xsd = "src/main/resources/xsd/sdk-sys-img-04.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        ":sdk-common-3",
    ],
)

xsd_java_library(
    name = "sdk-sysimg-5",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.generated.sysimg.v5",
    xsd = "src/main/resources/xsd/sdk-sys-img-05.xsd",
    xsd_deps = [
        "//tools/base/repository:repo-common-2",
        ":sdk-common-4",
    ],
)

xsd_java_library(
    name = "sdk-sites-list-5",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.sources.generated.v5",
    xsd = "src/main/resources/xsd/sources/sdk-sites-list-5.xsd",
    xsd_deps = ["//tools/base/repository:repo-sites-common-1"],
)

xsd_java_library(
    name = "sdk-sites-list-6",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.sources.generated.v6",
    xsd = "src/main/resources/xsd/sources/sdk-sites-list-6.xsd",
    xsd_deps = ["//tools/base/repository:repo-sites-common-1"],
)

xsd_java_library(
    name = "sdk-sites-list-7",
    java_deps = [
        ":tools.sdklib.core",
        "//tools/base/repository:tools.repository",
    ],
    package = "com.android.sdklib.repository.sources.generated.v7",
    xsd = "src/main/resources/xsd/sources/sdk-sites-list-7.xsd",
    xsd_deps = ["//tools/base/repository:repo-sites-common-1"],
)

# managed by go/iml_to_build
jvm_import(
    name = "schema-generated",
    # do not sort: must match IML order
    jars = ["//tools/base/sdklib:sdklib-sdk-schema.jar"],
    visibility = ["//visibility:public"],
)

merge_jars(
    name = "sdklib-sdk-schema",
    out = "sdklib-sdk-schema.jar",
    jars = [
        ":libsdk-addon-1.lib.jar",
        ":libsdk-addon-2.lib.jar",
        ":libsdk-addon-3.lib.jar",
        ":libsdk-addon-4.lib.jar",
        ":libsdk-common-1.lib.jar",
        ":libsdk-common-2.lib.jar",
        ":libsdk-common-3.lib.jar",
        ":libsdk-common-4.lib.jar",
        ":libsdk-repository-1.lib.jar",
        ":libsdk-repository-2.lib.jar",
        ":libsdk-repository-3.lib.jar",
        ":libsdk-repository-4.lib.jar",
        ":libsdk-sites-list-1.lib.jar",
        ":libsdk-sites-list-2.lib.jar",
        ":libsdk-sites-list-3.lib.jar",
        ":libsdk-sites-list-4.lib.jar",
        ":libsdk-sites-list-5.lib.jar",
        ":libsdk-sites-list-6.lib.jar",
        ":libsdk-sites-list-7.lib.jar",
        ":libsdk-sysimg-1.lib.jar",
        ":libsdk-sysimg-2.lib.jar",
        ":libsdk-sysimg-3.lib.jar",
        ":libsdk-sysimg-4.lib.jar",
        ":libsdk-sysimg-5.lib.jar",
    ],
)
