load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

kotlin_library(
    name = "android-test-plugin-host-emulator-control",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/com/android/tools/utp/plugins/host/emulatorcontrol/*java",
    ]),
    resource_strip_prefix = "tools/base/utp/android-test-plugin-host-emulator-control/src/main/resources/",
    resources = glob(["src/main/resources/**"]),
    deps = [
        "//tools/base/common:tools.common",
        "//tools/base/emulator/proto",
        "//tools/base/utp:com.google.testing.platform.android-device-provider-local",
        "//tools/base/utp:com.google.testing.platform.android-driver-instrumentation",
        "//tools/base/utp:com.google.testing.platform.launcher",
        "//tools/base/utp:utp.tink",
        "//tools/base/utp/android-test-plugin-host-emulator-control-proto",
        "@maven//:com.google.code.gson.gson",
        "@maven//:com.google.guava.guava",
        "@maven//:commons-io.commons-io",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib-jdk8",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

kotlin_test(
    name = "tests",
    srcs = glob([
        "src/test/java/**/*.kt",
        "src/test/java/com/android/tools/utp/plugins/host/emulatorcontrol/*java",
    ]),
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        # Put UTP protobuf-java runtime first in the list to ignore other versions in the deps.
        "//tools/base/utp:utp.tink",
        # Other dependencies.
        ":android-test-plugin-host-emulator-control",
        "//tools/base/common:tools.common",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/utp:com.google.testing.platform.launcher",
        "//tools/base/utp/android-test-plugin-host-emulator-control-proto",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.mockito.kotlin.mockito-kotlin",
        "@maven//:org.mockito.mockito-core",
    ],
)

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/java/com/android/tools/utp/plugins/host/emulatorcontrol/*java",
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "build.gradle",
    ],
    visibility = ["//tools/base/utp:__pkg__"],
)
