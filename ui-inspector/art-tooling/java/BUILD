load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:android.bzl", "dex_library")
load("//tools/base/bazel:robolectric.bzl", "robolectric_test")

package(default_visibility = ["//tools/base/ui-inspector:__subpackages__"])

java_library(
    name = "arttooling_src",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = [
        "//prebuilts/studio/sdk:platforms/latest_jar",
    ],
)

dex_library(
    name = "libarttooling",
    jars = [
        ":arttooling_src",
    ],
)

robolectric_test(
    name = "arttooling_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    deps = [
        ":arttooling_src",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "@maven//:org.robolectric.robolectric",
    ],
)
