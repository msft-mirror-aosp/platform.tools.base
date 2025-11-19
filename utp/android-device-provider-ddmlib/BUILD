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
        "//tools/base/utp:com.google.testing.platform.android-device-provider-local",
        "//tools/base/utp:com.google.testing.platform.launcher",
        "//tools/base/utp/android-device-provider-ddmlib-proto",
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
        # Put UTP protobuf-java runtime first in the list to ignore other versions in the deps.
        "//tools/base/utp:utp.protobuf-java",
        # Other dependencies.
        "//tools/base/common:tools.common",
        "//tools/base/ddmlib:tools.ddmlib",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/utp/android-device-provider-ddmlib-proto",
        "//tools/base/utp:com.google.testing.platform.android-device-provider-local",
        "//tools/base/utp:com.google.testing.platform.launcher",
        "//tools/base/utp:utp.dagger",
        ":android-device-provider-ddmlib",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
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
