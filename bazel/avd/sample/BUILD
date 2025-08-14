load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "emulator_test",
    srcs = [
        "EmulatorTest.java",
        "EmulatorTestUtils.java",
    ],
    data = [
        "//prebuilts/studio/sdk:platform-tools",
        "//tools/base/bazel/avd:default_avd",
    ],
    tags = [
        "ci:studio-mac-arm",
        "cpu:2",
        "noci:studio-win",
    ],
    test_class = "EmulatorTest",
    deps = [
        "//tools/base/bazel/avd:emulator_rule",
        "@maven//:junit.junit",
    ],
)

java_test(
    name = "MultipleEmulatorTest",
    srcs = [
        "EmulatorTestUtils.java",
        "MultipleEmulatorTest.java",
    ],
    data = [
        "//prebuilts/studio/sdk:platform-tools",
        "//tools/base/bazel/avd:avd_34",
        "//tools/base/bazel/avd:default_avd",
    ],
    tags = [
        "noci:studio-win",
    ],
    test_class = "MultipleEmulatorTest",
    deps = [
        "//tools/base/bazel/avd:emulator_rule",
        "@maven//:junit.junit",
    ],
)
