load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

filegroup(
    name = "agp_gradle_build_files",
    srcs = glob([
        "src/main/**/*.java",
        "src/main/**/*.kt",
        "src/main/resources/**",
    ]) + [
        "NOTICE",
        "build.gradle",
    ],
    visibility = ["//tools/base/utp:__pkg__"],
)

kotlin_library(
    name = "gradle-work-action",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    deps = [
        "//tools/base/build-system:gradle-api_neverlink",
        "//tools/base/build-system/gradle-api",
        "//tools/base/common:tools.common",
        "//tools/base/ddmlib:tools.ddmlib",
        "//tools/base/sdk-common:tools.sdk-common",
        "//tools/base/utp:com.google.testing.platform.core-proto",
        "//tools/base/utp:com.google.testing.platform.launcher",
        "//tools/base/utp:utp.tink",
        "//tools/base/utp/android-device-provider-ddmlib-proto",
        "//tools/base/utp/android-test-plugin-host-additional-test-output-proto",
        "//tools/base/utp/android-test-plugin-host-apk-installer-proto",
        "//tools/base/utp/android-test-plugin-host-coverage-proto",
        "//tools/base/utp/android-test-plugin-host-emulator-control-proto",
        "//tools/base/utp/android-test-plugin-host-logcat-proto",
        "//tools/base/utp/android-test-plugin-result-listener-gradle-proto",
        "//tools/base/utp/gradle-work-action-api",
        "//tools/base/utp/utp-common",
        "@maven//:commons-io.commons-io",
    ],
)

kotlin_test(
    name = "tests",
    srcs = glob([
        "src/test/java/**/*.kt",
        "src/test/java/**/*.java",
    ]),
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        # Put UTP protobuf-java runtime first in the list to ignore other versions in the deps.
        "//tools/base/utp:utp.tink",
        # Other dependencies.
        ":gradle-work-action",
        "//tools/base/build-system:gradle-api",
        "//tools/base/build-system/builder",
        "//tools/base/build-system/builder-model",
        "//tools/base/build-system/builder-test-api:tools.builder-test-api",
        "//tools/base/build-system/gradle-api",
        "//tools/base/common:tools.common",
        "//tools/base/ddmlib:tools.ddmlib",
        "//tools/base/sdklib:tools.sdklib",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/utp:com.google.testing.platform.core-proto",
        "//tools/base/utp:com.google.testing.platform.launcher",
        "//tools/base/utp/android-device-provider-ddmlib-proto",
        "//tools/base/utp/android-test-plugin-host-additional-test-output-proto",
        "//tools/base/utp/android-test-plugin-host-apk-installer-proto",
        "//tools/base/utp/android-test-plugin-host-coverage-proto",
        "//tools/base/utp/android-test-plugin-host-emulator-control-proto",
        "//tools/base/utp/android-test-plugin-host-logcat-proto",
        "//tools/base/utp/android-test-plugin-result-listener-gradle-proto",
        "//tools/base/utp/gradle-work-action-api",
        "//tools/base/utp/utp-common",
        "@maven//:com.google.crypto.tink.tink",
        "@maven//:com.google.truth.truth",
        "@maven//:commons-io.commons-io",
        "@maven//:junit.junit",
        "@maven//:org.codehaus.groovy.groovy-all",
        "@maven//:org.mockito.kotlin.mockito-kotlin",
        "@maven//:org.mockito.mockito-core",
    ],
)
