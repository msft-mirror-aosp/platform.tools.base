load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:maven.bzl", "maven_library")
load("//tools/base/common:version.bzl", "BASE_VERSION")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.backup.bmgr.parser",
    srcs = ["src"],
    iml_files = ["android.sdktools.backup.bmgr.parser.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = ["@intellij//:intellij-sdk"],
)

maven_library(
    name = "bmgr-parser",
    srcs = glob([
        "src/**/*.java",
    ]),
    coordinates = "com.android.tools.backup:bmgr-parser",
    description = "Library to parse output from BMGR command",
    module_name = "bmgr-parser",
    version = BASE_VERSION,
    visibility = [
        "//tools/base/backup:__subpackages__",
    ],
)
