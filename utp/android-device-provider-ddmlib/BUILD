load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

kotlin_library(
    name = "android-device-provider-ddmlib",
    srcs = glob([
        "src/main/java/**/*.kt",
    ]),
    resource_strip_prefix = "tools/base/utp/android-device-provider-ddmlib/src/main/resources/",
    resources = glob(["src/main/resources/**"]),
    deps = [
        "//tools/base/common:tools.common",
        "//tools/base/ddmlib:tools.ddmlib",
        "//tools/base/utp/android-device-provider-ddmlib-proto",
        "//tools/base/utp/android-device-provider-profile",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:com.google.testing.platform.android-device-provider-local",
        "@maven//:com.google.testing.platform.launcher",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

kotlin_test(
    name = "tests",
    srcs = glob([
        "src/test/java/**/*.kt",
    ]),
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        # Don't change dependency order or the test fails. This launcher jar
        # should be loaded first.
        "@maven//:com.google.testing.platform.launcher",
        # Test dependencies.
        ":android-device-provider-ddmlib",
        "//tools/base/common:tools.common",
        "//tools/base/ddmlib:tools.ddmlib",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/utp/android-device-provider-ddmlib-proto",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:com.google.testing.platform.android-device-provider-local",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.mockito.kotlin.mockito-kotlin",
        "@maven//:org.mockito.mockito-core",
    ],
)

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "build.gradle",
    ],
    visibility = ["//tools/base/utp:__pkg__"],
)
