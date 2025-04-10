load("//tools/base/backup-test:version.bzl", "BACKUP_TEST_VERSION")
load("//tools/base/bazel:maven.bzl", "maven_library")

maven_library(
    name = "backup-test-api",
    srcs = glob([
        "src/main/java/**/*.kt",
    ]),
    coordinates = "com.android.tools.backup:backup-test-api",
    version = BACKUP_TEST_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.junit.platform.junit-platform-commons",
    ],
)
