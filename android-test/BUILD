load("//tools/base/bazel:maven.bzl", "maven_repository")

maven_repository(
    name = "android-test-maven-repo",
    artifacts = [
        "//tools/base/android-test/android-test-engine-result-listener:android-test-engine-result-listener",
        "//tools/base/android-test/android-test-engine-result-listener:test_result_event_proto",
        "//tools/base/android-test/android-test-junit-engine:android-test-engine",
        "//tools/base/android-test/test-result-proto:test-result-proto",
    ],
    include_transitive_deps = False,
    visibility = ["//visibility:public"],
)
