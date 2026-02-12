load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:jvm_import.bzl", "jvm_import")
load("//tools/base/bazel:kotlin.bzl", "kotlin_test")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")

maven_library(
    name = "lint-tests",
    # buildifier: disable=bad-recursive-glob
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    coordinates = "com.android.tools.lint:lint-tests",
    description = "API to build lint check tests",
    module_name = "lint-tests",
    notice = "NOTICE",
    resource_strip_prefix = "tools/base/lint/libs/lint-tests",
    version = BASE_VERSION,
    visibility = ["//visibility:public"],
    exports = [
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
    deps = [
        "//prebuilts/tools/common/lint-psi/intellij-core",
        "//prebuilts/tools/common/lint-psi/kotlin-compiler",
        "//prebuilts/tools/common/lint-psi/uast",
        "//tools/base/common:tools.common",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "//tools/base/lint/cli",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "@maven//:com.google.code.gson.gson",
        "@maven//:junit.junit",
        "@maven//:net.sf.kxml.kxml2",
        "@maven//:org.codehaus.groovy.groovy",
        "@maven//:org.ow2.asm.asm",
        "@maven//:org.ow2.asm.asm-tree",
    ],
)

kotlin_test(
    name = "tests",
    timeout = "long",
    # buildifier: disable=bad-recursive-glob
    srcs = glob([
        "src/test/java/**/*.kt",
        "src/test/java/**/*.java",
    ]),
    add_opens = [
        "java.base/java.nio.charset",
    ],
    data = [
        "//prebuilts/studio/sdk:platform-tools",
        "//prebuilts/studio/sdk:platforms/latest",
        "//tools/adt/idea/android/annotations",
    ],
    jvm_flags = [
        "-Dtest.suite.jar=tests.jar",
        # TODO: Inject the cache directory into tests.
        "-Duser.home=/tmp",
    ],
    lint_baseline = "lint_baseline.xml",
    resources = glob(["src/test/resources/**"]),
    shard_count = 6,
    tags = [
        "slow",
    ],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":liblint-checks-proto",
        ":lint-tests",
        "//prebuilts/tools/common/lint-psi/intellij-core",
        "//prebuilts/tools/common/lint-psi/kotlin-compiler",
        "//prebuilts/tools/common/lint-psi/uast",
        "//tools/adt/idea/.idea/libraries:mockito-kotlin",
        "//tools/analytics-library/protos/src/main/proto",
        "//tools/analytics-library/shared:tools.analytics-shared",
        "//tools/analytics-library/testing:tools.analytics-testing",
        "//tools/analytics-library/tracker:tools.analytics-tracker",
        "//tools/base/annotations",
        "//tools/base/apkparser:tools.binary-resources",
        "//tools/base/apkparser/analyzer:tools.analyzer",
        "//tools/base/bazel:langtools",
        "//tools/base/build-system/builder-model",
        "//tools/base/build-system/manifest-merger:tools.manifest-merger",
        "//tools/base/common:tools.common",
        "//tools/base/common:tools.fixtures",
        "//tools/base/layoutlib-api:tools.layoutlib-api",
        "//tools/base/lint:tools.lint-api",
        "//tools/base/lint:tools.lint-checks",
        "//tools/base/lint:tools.lint-model",
        "//tools/base/lint/cli",
        "//tools/base/repository:tools.repository",
        "//tools/base/repository:tools.testlib",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.code.gson.gson",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:net.sf.kxml.kxml2",
        "@maven//:org.codehaus.groovy.groovy",
        "@maven//:org.jetbrains.annotations",
        "@maven//:org.jetbrains.kotlin.kotlin-reflect",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlin.kotlin-test",
        "@maven//:org.mockito.mockito-core",
        "@maven//:org.ow2.asm.asm-tree",
        "@maven//:org.ow2.asm.asm-util",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.lint.testinfrastructure",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.lint.testinfrastructure.iml"],
    lint_baseline = "testinfrastructure_lint_baseline.xml",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/base/lint:studio.android.sdktools.lint-api",
        "//tools/base/lint:studio.android.sdktools.lint-model",
        "//tools/base/lint:studio.android.sdktools.lint-checks",
        "//tools/base/lint/cli:studio.android.sdktools.lint.cli",
        "//tools/adt/idea/.idea/libraries:junit4",
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.lint.tests",
    iml_files = ["android.sdktools.lint.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//prebuilts/tools/common/m2:groovy-3.0.9",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/base/lint/cli:studio.android.sdktools.lint.cli",
        "//tools/base/lint:studio.android.sdktools.lint-api",
        "//tools/base/lint/libs/lint-tests:studio.android.sdktools.lint.testinfrastructure",
        "//tools/base/apkparser/analyzer:studio.android.sdktools.analyzer",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/analytics-library/testing:android.sdktools.analytics-testing",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:truth-java8-extension",
        "//tools/analytics-library/shared:analytics-shared",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/.idea/libraries:mockito-kotlin",
        "//tools/base/common:studio.android.sdktools.common.testfixtures",
        "//tools/base/lint:studio.android.sdktools.lint-checks",
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/lint/libs/lint-tests:liblint-checks-proto",
        "//tools/adt/idea/.idea/libraries:kotlin-test",
        "//tools/adt/idea/.idea/libraries:agp-version",
    ],
    test_resources = ["src/test/resources"],
    test_srcs = ["src/test/java"],
    # The cannonical test target for this is :tests (//tools/base/lint/libs/lint-tests:tests)
    # as that runs with the correct dependencies. This iml_module is only defined for development
    test_tags = ["manual"],
    visibility = ["//visibility:public"],
)

# managed by go/iml_to_build
jvm_import(
    name = "liblint-checks-proto",
    jars = ["//tools/base/lint:liblint-checks-proto.lib.jar"],
    visibility = ["//visibility:public"],
)
