load("//tools/base/backup-test:version.bzl", "BACKUP_TEST_VERSION")
load("//tools/base/bazel:kotlin.bzl", "kotlin_test")
load("//tools/base/bazel:maven.bzl", "maven_library")

maven_library(
    name = "android-backup-runner",
    srcs = glob([
        "src/main/java/**/*.kt",
    ]),
    coordinates = "com.android.tools.backup:android-backup-runner",
    version = BACKUP_TEST_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/backup-test/backup-test-api",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)

kotlin_test(
    name = "android-backup-runner-tests",
    srcs = glob(
        ["src/test/java/**/*.kt"],
    ),
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":android-backup-runner",
        "//tools/base/backup-test/backup-test-api",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.kotlin.kotlin-test",
    ],
)
