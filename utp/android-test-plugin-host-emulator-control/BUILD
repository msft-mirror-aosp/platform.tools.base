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
        "//tools/base/utp/android-test-plugin-host-emulator-control-proto",
        "@maven//:com.google.code.gson.gson",
        "@maven//:com.google.crypto.tink.tink",
        "@maven//:com.google.guava.guava",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:com.google.testing.platform.android-device-provider-local",
        "@maven//:com.google.testing.platform.android-driver-instrumentation",
        "@maven//:com.google.testing.platform.launcher",
        "@maven//:commons-io.commons-io",
        "@maven//:io.grpc.grpc-core",
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
        ":android-test-plugin-host-emulator-control",
        "//tools/base/common:tools.common",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/utp/android-test-plugin-host-emulator-control-proto",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:com.google.testing.platform.launcher",
        "@maven//:com.google.truth.truth",
        "@maven//:io.grpc.grpc-inprocess",
        "@maven//:io.grpc.grpc-stub",
        "@maven//:io.grpc.grpc-testing",
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
