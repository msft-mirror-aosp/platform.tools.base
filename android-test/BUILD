load("//tools/base/bazel:maven.bzl", "maven_repository")

filegroup(
    name = "agp_gradle_build_files",
    srcs = [
        "release_version.bzl",
    ],
    visibility = [
        "//tools/base/build-system:__subpackages__",
        "//tools/base/firebase/testlab/testlab-gradle-plugin:__pkg__",
        "//tools/base/journeys:__subpackages__",
        "//tools/base/preview/screenshot/screenshot-test-gradle-plugin:__pkg__",
        "//tools/base/utp:__pkg__",
        "//tools/data-binding:__pkg__",
    ],
)

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
