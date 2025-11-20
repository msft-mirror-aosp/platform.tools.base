load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

kotlin_library(
    name = "android-test-plugin-host-additional-test-output",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    resource_strip_prefix = "tools/base/utp/android-test-plugin-host-additional-test-output/src/main/resources/",
    resources = glob(["src/main/resources/**"]),
    deps = [
        "//tools/base/common:tools.common",
        "//tools/base/utp/android-test-plugin-host-additional-test-output-proto",
        "//tools/base/utp/utp-common",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:com.google.testing.platform.android-device-provider-local",
        "@maven//:com.google.testing.platform.launcher",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
    ],
)

kotlin_test(
    name = "tests",
    srcs = glob([
        "src/test/java/**/*.kt",
        "src/test/java/**/*.java",
    ]),
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":android-test-plugin-host-additional-test-output",
        "//tools/base/common:tools.common",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/utp/android-test-plugin-host-additional-test-output-proto",
        "//tools/base/utp/utp-common",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:com.google.testing.platform.launcher",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.mockito.kotlin.mockito-kotlin",
        "@maven//:org.mockito.mockito-core",
    ],
)

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/**/*.java",
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "build.gradle",
    ],
    visibility = ["//tools/base/utp:__pkg__"],
)
