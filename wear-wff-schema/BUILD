load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/bazel:utils.bzl", "fileset")
load("//tools/base/common:version.bzl", "BASE_VERSION")

# managed by go/iml_to_build
iml_module(
    name = "studio.intellij.android.wear-wff-schema",
    srcs = ["src"],
    iml_files = ["intellij.android.wear-wff-schema.iml"],
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/common:studio.android.sdktools.common",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.intellij.android.wear-wff-schema.tests",
    iml_files = ["intellij.android.wear-wff-schema.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:kotlin-test",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "@intellij//:test-framework",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework",
    ],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/base/wear-wff-schema:studio.intellij.android.wear-wff-schema",
        "//tools/base/common:studio.android.sdktools.common",
        "@intellij//:intellij-sdk",
    ],
)

fileset(
    name = "tools.wear-wff-schema.resources",
    srcs = glob(["resources/**"]),
    mappings = {
        "resources/": "",
    },
)

maven_library(
    name = "tools.wear-wff-schema",
    srcs = glob([
        "src/**/*.kt",
        "src/**/*.java",
    ]),
    bundled_deps = [],
    coordinates = "com.android.tools:wear-wff-schema",
    description = "Library containing the Watch Face Format schema definition, used by other Android tools libraries.",
    module_name = "wear-wff-schema",
    notice = "NOTICE",
    resource_strip_prefix = "tools/base/wear-wff-schema",
    resources = [":tools.wear-wff-schema.resources"],
    version = BASE_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/common:tools.common",
        "//tools/base/sdk-common:tools.sdk-common",
        "@maven//:org.jetbrains.kotlin.kotlin-reflect",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)
