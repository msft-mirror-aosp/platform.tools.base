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
        "//tools/base/utp/gradle-work-action-api",
        "@maven//:com.android.tools.androidtest.android-test-engine",
        "@maven//:com.android.tools.androidtest.android-test-engine-result-listener",
        "@maven//:com.android.tools.androidtest.test-result-proto",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:commons-io.commons-io",
        "@maven//:org.junit.platform.junit-platform-engine",
        "@maven//:org.junit.platform.junit-platform-launcher",
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
        "//tools/base/utp/gradle-work-action-api",
        "@maven//:com.android.tools.androidtest.android-test-engine-result-listener",
        "@maven//:com.android.tools.androidtest.test-result-proto",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:com.google.truth.truth",
        "@maven//:commons-io.commons-io",
        "@maven//:junit.junit",
        "@maven//:org.apache.groovy.groovy-all",
        "@maven//:org.mockito.kotlin.mockito-kotlin",
        "@maven//:org.mockito.mockito-core",
    ],
)
