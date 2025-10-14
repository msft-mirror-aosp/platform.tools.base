load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:jvm_import.bzl", "jvm_import")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/bazel:merge_archives.bzl", "merge_jars")
load("//tools/base/bazel:utils.bzl", "fileset")
load("//tools/base/common:version.bzl", "BASE_VERSION")
load("xjc.bzl", "xsd_java_library")

exports_files(
    ["src/main/resources/xsd/global.xjb"],
    visibility = ["//visibility:public"],
)

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.repository",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.repository.iml"],
    jvm_target = "8",
    lint_baseline = "lint_baseline.xml",
    resources = ["src/main/resources"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:jimfs",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/base/testutils:studio.android.sdktools.testutils",
    ],
    test_resources = ["src/test/resources"],
    test_srcs = [
        "src/test/java",
        "src/testFixtures/java",
    ],
    visibility = ["//visibility:public"],
    exports = ["//tools/base/repository:schema-generated"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/analytics-library/shared:analytics-shared",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/base/repository:schema-generated",
    ],
)

fileset(
    name = "tools.resources",
    srcs = glob(
        ["src/main/resources/**"],
        exclude = ["**/BUILD"],
    ),
    mappings = {
        "src/main/resources/": "",
    },
)

kotlin_library(
    name = "tools.repository-core",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    lint_baseline = "lint_baseline.xml",
    module_name = "repository",
    deps = [
        "//tools/analytics-library/shared:tools.analytics-shared",
        "//tools/base/common:tools.common",
        "@maven//:com.google.jimfs.jimfs",
        "@maven//:org.apache.commons.commons-compress",
        "@maven//:org.glassfish.jaxb.jaxb-runtime",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

maven_library(
    name = "tools.repository",
    # buildifier: disable=bad-recursive-glob
    srcs = [],
    bundled_deps = [
        ":generic-1.lib",
        ":generic-2.lib",
        ":tools.repository-core",
        ":repo-common-1.lib",
        ":repo-common-2.lib",
        ":repo-sites-common-1.lib",
    ],
    coordinates = "com.android.tools:repository",
    description = "A library for downloading and managing package repositories",
    module_name = "repository",
    notice = "NOTICE",
    resource_strip_prefix = "tools/base/repository",
    resources = [":tools.resources"],
    version = BASE_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/analytics-library/shared:tools.analytics-shared",
        "//tools/base/common:tools.common",
        "@maven//:com.google.jimfs.jimfs",
        "@maven//:org.apache.commons.commons-compress",
        "@maven//:org.glassfish.jaxb.jaxb-runtime",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

kotlin_library(
    name = "tools.testlib",
    # buildifier: disable=bad-recursive-glob
    srcs = glob([
        "src/testFixtures/java/**/*.java",
        "src/testFixtures/java/**/*.kt",
    ]),
    visibility = ["//visibility:public"],  # contains com.android.repository.testframework
    deps = [
        ":tools.repository",
        "//tools/base/annotations",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.guava.guava",
        "@maven//:com.google.jimfs.jimfs",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

kotlin_test(
    name = "tools.tests",
    # buildifier: disable=bad-recursive-glob
    srcs = glob([
        "src/test/java/**/*.java",
        "src/test/java/**/*.kt",
    ]),
    add_opens = [
        "java.base/java.nio.charset",
    ],
    friends = [
        ":tools.repository",
        ":tools.testlib",
    ],
    jvm_flags = ["-Dtest.suite.jar=tools.tests.jar"],
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":tools.repository",
        ":tools.testlib",
        "//tools/base/annotations",
        "//tools/base/common:tools.common",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.guava.guava",
        "@maven//:com.google.jimfs.jimfs",
        "@maven//:com.google.truth.truth",
        "@maven//:jakarta.xml.bind.jakarta.xml.bind-api",
        "@maven//:junit.junit",
        "@maven//:org.apache.commons.commons-compress",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
        "@maven//:org.mockito.mockito-core",
    ],
)

xsd_java_library(
    name = "repo-common-1",
    binding = "src/main/resources/xsd/common-custom-01.xjb",
    java_deps = [":tools.repository-core"],
    package = "com.android.repository.impl.generated.v1",
    visibility = ["//tools/base/sdklib:__pkg__"],
    xsd = "src/main/resources/xsd/repo-common-01.xsd",
)

xsd_java_library(
    name = "repo-common-2",
    binding = "src/main/resources/xsd/common-custom-02.xjb",
    java_deps = [":tools.repository-core"],
    package = "com.android.repository.impl.generated.v2",
    visibility = ["//tools/base/sdklib:__pkg__"],
    xsd = "src/main/resources/xsd/repo-common-02.xsd",
)

xsd_java_library(
    name = "generic-1",
    binding = "src/main/resources/xsd/generic-custom-01.xjb",
    java_deps = [":tools.repository-core"],
    package = "com.android.repository.impl.generated.generic.v1",
    visibility = ["//tools/base/sdklib:__pkg__"],
    xsd = "src/main/resources/xsd/generic-01.xsd",
    xsd_deps = [":repo-common-1"],
)

xsd_java_library(
    name = "generic-2",
    binding = "src/main/resources/xsd/generic-custom-02.xjb",
    java_deps = [":tools.repository-core"],
    package = "com.android.repository.impl.generated.generic.v2",
    visibility = ["//tools/base/sdklib:__pkg__"],
    xsd = "src/main/resources/xsd/generic-02.xsd",
    xsd_deps = [":repo-common-2"],
)

xsd_java_library(
    name = "repo-sites-common-1",
    binding = "src/main/resources/xsd/sources/repo-sites-common-custom-1.xjb",
    java_deps = [":tools.repository-core"],
    package = "com.android.repository.impl.sources.generated.v1",
    visibility = ["//tools/base/sdklib:__pkg__"],
    xsd = "src/main/resources/xsd/sources/repo-sites-common-1.xsd",
)

java_binary(
    name = "xjc",
    main_class = "com.sun.tools.xjc.Driver",
    tags = ["manual"],
    visibility = ["//visibility:public"],
    runtime_deps = [
        "//tools/base/jaxb-inheritance-plugin",
    ],
)

# managed by go/iml_to_build
jvm_import(
    name = "schema-generated",
    # do not sort: must match IML order
    jars = ["//tools/base/repository:repository-sdk-schema.jar"],
    visibility = ["//visibility:public"],
)

merge_jars(
    name = "repository-sdk-schema",
    out = "repository-sdk-schema.jar",
    jars = [
        "//tools/base/repository:libgeneric-1.lib.jar",
        "//tools/base/repository:libgeneric-2.lib.jar",
        "//tools/base/repository:librepo-common-1.lib.jar",
        "//tools/base/repository:librepo-common-2.lib.jar",
        "//tools/base/repository:librepo-sites-common-1.lib.jar",
    ],
)
