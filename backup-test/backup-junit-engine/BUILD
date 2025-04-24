load("//tools/base/backup-test:version.bzl", "BACKUP_TEST_VERSION")
load("//tools/base/bazel:kotlin.bzl", "kotlin_test")
load("//tools/base/bazel:maven.bzl", "maven_library")

maven_library(
    name = "backup-junit-engine",
    srcs = glob([
        "src/main/java/**/*.kt",
    ]),
    coordinates = "com.android.tools.backup:backup-junit-engine",
    resource_strip_prefix = "tools/base/backup-test/backup-junit-engine/src/main/resources/",
    resources = glob(["src/main/resources/**"]),
    version = BACKUP_TEST_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/backup-test/backup-test-api",
        "//tools/base/common:tools.common",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.junit.platform.junit-platform-engine",
    ],
)

kotlin_test(
    name = "backup-junit-engine-tests",
    srcs = glob(
        ["src/test/java/**/*.kt"],
    ),
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":backup-junit-engine",
        "//tools/base/common:tools.common",
        "//tools/base/testutils:tools.testutils",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.jetbrains.kotlin.kotlin-test",
        "@maven//:org.junit.platform.junit-platform-engine",
    ],
)
