load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.template-engine",
    srcs = ["src"],
    iml_files = ["android.sdktools.template-engine.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:jimfs",
        "//tools/base/testutils:studio.android.sdktools.testutils",
    ],
    test_srcs = ["test/src"],
    # do not sort: must match IML order
    deps = ["@intellij//:intellij-sdk"],
)

kotlin_library(
    name = "tools.template-engine",
    srcs = glob(["src/**/*.kt"]),
    visibility = [
        "//tools/vendor/google/android:__subpackages__",
    ],
    deps = [
        "@maven//:com.google.code.gson.gson",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
    ],
)

kotlin_test(
    name = "tools.template-engine-test",
    srcs = glob([
        "test/**/*.java",
        "test/**/*.kt",
    ]),
    friends = [":tools.template-engine"],
    jvm_flags = ["-Dtest.suite.jar=tools.template-engine-test.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    visibility = [
        "//tools/vendor/google/android:__subpackages__",
    ],
    deps = [
        ":tools.template-engine",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.jimfs.jimfs",
        "@maven//:info.picocli.picocli",
        "@maven//:junit.junit",
    ],
)
